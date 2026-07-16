# 결제 장애 복구 설계 (CONFIRMING · CANCELING 두 회색지대 통합 + 보정 스케줄러)

> **상태: 반영됨.** `CONFIRMING`(승인 미확정)과 `CANCELING`(취소 미확정) 두 durable 회색지대, PG 호출 전후 트랜잭션 분리, 두 회색지대를 한 스케줄러 주기에서 보정하는 통합 스케줄러가 코드에 반영되었습니다. 아래에는 현재 구현 기준, 트레이드오프, 남은 후보를 함께 정리합니다.

## 문서 목적

PG 호출(승인/취소) 이후 내부 상태가 반영되지 않는 장애를 어떻게 복구할지 정리합니다. 핵심은 승인과 취소 각각에 `CONFIRMING`/`CANCELING` durable 마커를 두고, 보정 스케줄러가 이를 기준으로 내부 상태를 PG 진실에 수렴시키는 것입니다. 승인과 취소는 "외부 호출을 시도했지만 결과가 미확정"이라는 같은 문제를 공유하므로 **대칭 구조**로 설계했습니다.

## 문제 정의

결제 승인과 취소는 모두 두 시스템에 걸쳐 있어 하나의 트랜잭션으로 묶을 수 없습니다.

```
PG(외부) 승인 = 돈 빠짐 / 취소 = 돈 돌아감   ──┐
                                              ├─ 원자적으로 묶을 수 없음
내부 DB 상태 확정                            ──┘
```

| 실패 모드 | 설명 | 코드가 살아있나 |
| --- | --- | --- |
| ① PG 호출 실패(응답 불명) | 타임아웃·네트워크 | O |
| ② PG 성공 + 커밋 실패 | 호출 직후 DB 커밋 실패 | O |
| ③ PG 성공 + 프로세스 크래시 | 호출 직후 강제 종료(전원·OOM·kill) | X |

승인이 미확정이면 결제는 `CONFIRMING`에, 취소가 미확정이면 `CANCELING`에 남습니다.

## 핵심 아이디어 — `CONFIRMING`/`CANCELING` durable 마커

confirm 진입 시 `READY -> CONFIRMING`을, 취소 진입 시 `APPROVED -> CANCELING`을 각각 **PG 호출 전에 먼저 커밋**합니다. PG 호출 이후 커밋 실패나 크래시가 나도 결제는 회색지대 상태에 남아, "승인/취소를 시도했지만 확정되지 못한" 결제를 식별할 수 있습니다. 이 `CONFIRMING`/`CANCELING` row가 복구 대상입니다.

`CANCELING`을 한 번 통과한 결제는 어떤 경로로도 `APPROVED`로 되돌아가지 않습니다(**REVERT 없음**). 사용자의 취소 의도를 보존하기 위해 탈출구를 `CANCELED`(또는 수동 검토용 `CANCELING` 유지)로만 좁혔습니다.

## 공통 위상 — 3단계 (mark → PG → apply)

승인과 취소는 같은 3단계 위상을 공유합니다. **어떤 트랜잭션·행 락 안에서도 PG를 호출하지 않습니다**(confirm 보정의 환불 분기 포함, 아래 [반영됨](#반영됨) 참고).

```text
동기: mark(write tx: 락 → 상태 검증 → 회색지대 마커 커밋)
      → 외부 PG 호출 (멱등키, tx 밖)
      → apply(write tx: 재락 + 상태 재확인 → 결정 적용)
보정: snapshot(readonly tx) → decide(순수 함수) → 외부 PG 호출(tx 밖) → apply(재락 + 재확인)
```

## 방어 계층

| 계층 | 언제 | 무엇 | 비고 |
| --- | --- | --- | --- |
| 동기 — confirm 서비스 | 코드 살아있음 | confirm PG 호출이 `RestClientException`이면 `paymentKey`로 PG 최종 조회 → `DONE`이면 내부 상태 확정 | `PaymentConfirmService`에서 처리 |
| 동기 — confirm 재진입 | 코드 살아있음 | `CONFIRMING`이면 같은 멱등키로 confirm 재호출, `APPROVED`면 기존 결과 재사용 | 중복 완료 요청 방어 |
| 동기 — 컨트롤러 최종 조회 | 코드 살아있음 | confirm이 `PaymentGatewayException`/`IllegalStateException`으로 빠져나오면, 결제가 `CONFIRMING`일 때만 보정 로직을 즉시 1회 적용. 해소되면 그 상태, 아니면 `CONFIRMING` 응답 후 스케줄러 위임 | `PaymentApiController` try-catch → `PaymentRecoveryService.reconcileConfirmingPaymentByOrderId`. `CONFIRMING`이 아니면(정상 비즈니스 거절) 원래 예외 전파 |
| 동기 — cancel 서비스 | 코드 살아있음 | 취소 PG 호출이 `RestClientException`이면 `paymentKey`로 조회 폴백. 취소·조회 모두 미확정이면 `CANCELING` 유지 후 `200`(예외 아님) | `PaymentCancelService`에서 처리 |
| 동기 — cancel 재진입 | 코드 살아있음 | 이미 `CANCELING`이면 추가 마킹 없이 같은 멱등키로 재취소, 이미 `CANCELED`면 즉시 멱등 종료(외부 호출 없음) | 중복 취소 요청 방어 |
| 비동기 — 보정 스케줄러 | 크래시 등으로 동기 복구 불가 | 나중에 DB 스캔 → PG 재조회 → 수렴. 한 주기에서 confirm 배치 → cancel 배치 → backlog gauge 순차 처리 | 최종 일관성 보장 |

> 자동 Retry는 도입하지 않습니다. 승인·취소 중복 호출과 지연을 늘리지 않고, 결과 불명은 중간 상태와 보정 스케줄러가 담당합니다. Circuit Breaker는 승인·조회·취소별로 분리해 외부 장애 중 반복 호출만 차단합니다.

## confirm 흐름

PG 호출은 DB 트랜잭션 밖에서 수행합니다. 트랜잭션은 PG 호출 전 `CONFIRMING` 마커를 남기는 구간과, PG 결과를 내부 상태에 반영하는 구간으로 나눕니다.

```text
Tx1: 락 → READY 검증 → READY -> CONFIRMING → 커밋        // durable 마커 확보
PG confirm 호출 (멱등키, 트랜잭션 밖)
Tx2: 락 → CONFIRMING -> APPROVED + 예매 CONFIRMED + 좌석 BOOKED → 커밋
```

Tx2에서 커밋 실패/크래시 시 결제는 `CONFIRMING`에 남습니다.

## cancel 흐름

취소도 confirm과 대칭입니다. PG 취소 호출을 트랜잭션 밖에서 수행하고, 마커 커밋과 결과 반영 트랜잭션을 나눕니다.

```text
Tx1: 락 → 소유자 검증 + APPROVED 검증 → APPROVED -> CANCELING → 커밋   // durable 마커 확보
PG cancel 호출 (멱등키 "cancel:{id}", 트랜잭션 밖. 실패 시 paymentKey 조회 폴백)
Tx2: 락 → CANCELING 재확인 → CANCELING -> CANCELED + 예매 CANCELED + 좌석 release → 커밋
```

Tx2에서 커밋 실패/크래시 시 결제는 `CANCELING`에 남고, 다음 주기 보정이 같은 멱등키로 재취소해 `CANCELED`로 수렴합니다. Tx1 통과(소유자·상태·`paymentKey` 사전 검사 성공) 이후에는 미확정이어도 예외를 던지지 않고 `CANCELING`을 유지한 채 `200`으로 응답합니다 — 사용자의 취소 의도를 되돌리지 않기 위해서입니다.

## 보정 스케줄러 설계

스케줄러(`PaymentRecoveryScheduler.recoverGrayZonePayments`)는 한 주기에서 **confirm 배치 → cancel 배치 → backlog gauge 갱신**을 순차 실행합니다. Spring 기본 `TaskScheduler`가 단일 스레드라 `@Scheduled`를 쪼개도 직렬로 돌기 때문에, 두 배치와 게이지 갱신을 각각 예외로 격리해 하나의 실패가 나머지를 건너뛰지 않게 합니다. 설정 노브 `payment.recovery-scheduler.{grace-ms, batch-size, fixed-delay-ms}`는 두 배치가 공유합니다(취소 전용 노브는 두지 않았습니다).

PG 상태 조회 회로가 OPEN이면 외부 상태를 판단할 수 없으므로 confirm/cancel 보정 배치를 시작하지 않고 backlog gauge만 갱신합니다. 배치 처리 중 lookup 회로가 열리면 남은 건도 다음 주기로 넘깁니다. cancel 회로만 OPEN인 경우에는 PG 조회로 이미 취소된 건을 내부 확정할 수 있으므로 배치 전체를 미리 차단하지 않습니다.

### 1. 후보 선정 — `CONFIRMING` / `CANCELING` 상태

```text
status = CONFIRMING AND confirming_at < now - grace   // confirm 배치
status = CANCELING  AND canceling_at  < now - grace   // cancel 배치
```

- `grace = 1분`: 정상 confirm/cancel은 수초 안에 끝나므로, 진행 중 요청과 보정 스케줄러가 과하게 겹치지 않도록 둔 완충 시간입니다(두 배치 공유).
- 스케줄러는 최종 반영 직전에 `Payment` row를 다시 잠그고 각각 `CONFIRMING`/`CANCELING` 상태인지 재확인합니다.

### 2. 조회 키 — confirm=`orderId`, cancel=`paymentKey`

- confirm 보정: `orderId`는 ready 시점에 저장되어 항상 살아남습니다. `paymentKey`는 PG 재조회 응답에서 얻습니다. 멱등키 `"confirm:" + orderId`는 deterministic이라 재호출해도 안전합니다(15일 윈도우 = 안전 상한).
- cancel 보정: 취소 대상은 이미 `APPROVED`를 거쳤으므로 `paymentKey`가 존재합니다. `paymentKey`로 PG를 조회하고, 재취소 멱등키 `"cancel:" + paymentId`를 사용자 취소·보정 재취소·confirm 보정 환불이 **공유**합니다(아래 트레이드오프 3).

### 3. 락은 PG 조회 밖에서

```text
[락 없음] PG 상태 조회(confirm=orderId, cancel=paymentKey)  // 외부 호출
[락]      상태 재확인(CONFIRMING?/CANCELING?) → 적용/환불/재취소  // 짧게만 락
```

### 4. 결정 매트릭스 (정책 순수 함수)

결정 로직은 트랜잭션·외부 호출과 분리한 두 순수 함수에 있습니다: confirm은 `RecoveryPolicy.decide`, cancel은 `PaymentCancelPolicy.decide`. 부수효과 없이 스냅샷 + PG 상태만으로 액션을 반환합니다.

**confirm — `RecoveryPolicy.decide`**

| PG 조회 | 필드 일치 | 좌석 | 액션 |
| --- | --- | --- | --- |
| `DONE` | `orderId`+금액+통화 일치 | 유효(`HELD`) | `APPROVE`: `CONFIRMING -> APPROVED` (내정립) |
| `DONE` | `orderId`+금액+통화 일치 | 소실(만료·재판매) | `REFUND_THEN_FAIL(SEAT_UNAVAILABLE)`: 환불 → `CONFIRMING -> FAILED` + 좌석 release |
| `DONE` | `orderId` 일치, 금액/통화 불일치 | - | `REFUND_THEN_FAIL(PG_DATA_MISMATCH)`: 환불 → `CONFIRMING -> FAILED` + `log.error` |
| `DONE` | `orderId` 불일치 | - | `HOLD_MANUAL`: 자동 처리 안 함 + `log.error`, 보류(수동 검토) |
| `READY` / `IN_PROGRESS` / `WAITING_FOR_DEPOSIT` | - | - | `RETRY_LATER`: `CONFIRMING` 유지 후 다음 주기 재조회 |
| `ABORTED` / `EXPIRED` / `CANCELED` | - | - | `FAIL`: `CONFIRMING -> FAILED` + 좌석 release |
| 알 수 없는 상태 | - | - | `HOLD_MANUAL`: 자동 상태 변경 없이 운영 확인 |
| 불명(PG 미응답) | - | - | 보류, 다음 주기 |

**cancel — `PaymentCancelPolicy.decide`**

| PG 조회 | 필드 일치 | 액션 |
| --- | --- | --- |
| 취소됨(`CANCELED`/`PARTIAL_CANCELED`) | `paymentKey`+통화 일치 | `FINALIZE`: `CANCELING -> CANCELED` + 좌석 release |
| 취소됨 | `paymentKey`/통화 불일치 | `HOLD_MANUAL`: 무쓰기, `CANCELING` 유지(다른 결제건 의심) |
| 승인(`DONE`, 아직 취소 안 먹음) | `paymentKey` 일치 | `CANCEL_AGAIN`: 같은 멱등키로 재취소, `CANCELING` 유지 |
| 승인 | `paymentKey` 불일치 | `HOLD_MANUAL` |
| 그 외 status | - | `HOLD_MANUAL` |

- 만료로 좌석이 풀린 경우 무리하게 재확정하지 않고 환불합니다. 환불 호출 자체가 실패하면 다음 주기에 재시도합니다(멱등 cancel).
- **금액/통화 불일치**는 대개 우리 계산/파싱 문제입니다. 조회는 `orderId`로 하므로 응답 row는 우리 결제건이 확실하고, 고객이 낸 금액 그대로 환불(`PG_DATA_MISMATCH`)하면 고객 피해가 없습니다. 단, 가격 버그를 은폐하지 않도록 기대값 vs PG값을 `log.error`로 남깁니다.
- **`orderId` 불일치**는 우리 결제건 여부 자체가 의심스러워(다른 주문 환불 위험) 자동 환불/실패를 하지 않고 알림 후 보류합니다. `orderId`로 조회하는 한 현실적으로 거의 발생하지 않는 방어 분기입니다.
- **`REVERT` 없음**: cancel 결정에는 `CANCELING -> APPROVED` 복귀가 없습니다. 탈출구는 `FINALIZE`(→`CANCELED`) 또는 `HOLD_MANUAL`/`CANCEL_AGAIN`(→`CANCELING` 유지)뿐입니다.
- **전액취소 전제**: `TossPaymentStatus.isCanceled`가 `PARTIAL_CANCELED`를 포함하지만 본 시스템은 취소를 항상 전액으로만 발행합니다. `PARTIAL_CANCELED`가 관측되면 운영 예외 신호이며, 여기서는 `FINALIZE`(전량 좌석 release)로 수렴합니다(트레이드오프 4).

### 5. 주기

`fixedDelay` 약 30초~1분(`payment.recovery-scheduler.fixed-delay-ms`, 두 배치 공유). 만료 스케줄러 패턴(`ReservationExpirationScheduler`)을 재사용합니다. 한 주기에 confirm 배치와 cancel 배치가 순차로 돌고, 마지막에 backlog gauge를 갱신합니다.

### 6. 재기동 후 backlog 보호

서버가 중단된 동안 오래된 `CONFIRMING` 결제나 만료 예약이 많이 쌓일 수 있습니다. 이때 중요한 목표는 "한 번에 얼마나 많이 복구할 수 있는가"가 아니라, **backlog 처리 자체가 서버를 다시 압박해 재장애를 만들지 않도록 하는 것**입니다.

그래서 스케줄러는 한 주기에 처리할 후보 수를 설정값으로 제한합니다.

```text
payment.recovery-scheduler.batch-size
reservation.expire-scheduler.batch-size
```

처리하지 못한 후보는 상태를 유지하고 다음 주기에 이어서 처리합니다. 이 방식은 복구 속도를 일부 포기하는 대신, 재기동 직후 DB 커넥션·외부 PG 호출·락 경합이 한 번에 몰리는 위험을 줄입니다. batch-size는 최적값이 아니라 안전한 시작점이며, backlog 테스트와 운영 지표를 보고 조정합니다.

운영 기본값:

| 작업 | 기본 batch-size | 이유 |
| --- | ---: | --- |
| 결제 보정 | `20` | PG 조회/취소 외부 호출이 포함되므로 작게 시작합니다. |
| 예약 만료(스케줄러) | `100` | 외부 호출 없이 DB 상태 전이가 중심이라 결제 보정보다 크게 둡니다. |

> batch-size 제한은 **저빈도·전역 일괄 처리인 스케줄러 경로**(`expireAll`)에만 적용합니다. 좌석 조회(`/seats`)마다 호출되는 **고빈도 수동 만료**(`expireByScheduleId`)는 만료되는 족족 비워져 후보가 얕게 유지되므로 상한을 두지 않고 해당 회차를 즉시 최신화합니다. 사람이 보지 않는 회차의 backlog는 스케줄러가 백스톱으로 정리합니다.

## 만료 스케줄러와의 경합

- **만료 스케줄러는 결제가 `CONFIRMING`/`CANCELING`인 예약의 좌석을 풀지 않습니다.** 두 회색지대는 예매가 각각 `PENDING`(승인 진행)/`CONFIRMED`(취소 진행)를 유지하고 만료 대상 조건(결제 `READY` + 예매 `PENDING`)에 걸리지 않아 자연히 면제됩니다. `CANCELING`은 아직 `CONFIRMED`/`BOOKED`라 좌석이 유효하므로, 오래 남아도 이중 판매·돈 손실이 없습니다.
- 생애주기 분리: 만료는 결제 `READY`(시도 안 함)인 예약을, 보정은 `CONFIRMING`/`CANCELING`을 다룹니다.
- 겹쳐도 락 순서 일관(`Payment` 먼저) + 상태 재확인으로 데드락·중복 처리를 막습니다.

## 멱등키 배타성 불변식

한 결제는 **CONFIRMING 계열(→환불→`FAILED`) 또는 APPROVED 계열(→취소→`CANCELED`) 중 하나만** 탑니다. `CONFIRMING`에서 승인이 확정돼야 `APPROVED`가 되고, 그래야만 취소를 시작할 수 있으므로 두 흐름은 시간상 배타적입니다. 그래서 confirm 보정의 환불과 cancel 취소가 같은 멱등키 `"cancel:" + paymentId`를 공유해도 충돌하지 않습니다. 오히려 재취소 경로들(사용자 취소·보정 재취소·confirm 보정 환불)이 **반드시 동일 키**여야 동시 요청 레이스에서 Toss가 원취소로 dedupe합니다 — **키 프리픽스를 분리하면 안 됩니다**(트레이드오프 3).

## 메트릭 (Micrometer)

confirm/cancel 두 회색지대를 대칭 이름으로 노출하는 얇은 래퍼(`PaymentRecoveryMetrics`)로 기록합니다.

| 메트릭 | 태그 | 의미 |
| --- | --- | --- |
| `payment_gray_zone_recovery_total` | `operation`=confirm\|cancel, `outcome` | 보정 결과(approved/canceled/held_manual/…) |
| `payment_gray_zone_pg_failure_total` | `operation`, `call`=lookup\|cancel | 외부 PG 호출 실패 |
| `payment_gray_zone_backlog` | `status`=confirming\|canceling | 회색지대 잔량 |

- `backlog` gauge는 `AtomicLong`을 스케줄러 주기마다 `countByStatus`로 갱신합니다. Prometheus 스크레이프 시 DB 조회가 없습니다(트레이드오프 5).
- **동기 취소는 메트릭을 기록하지 않습니다**(메트릭 스코프가 "보정"). 사용자 취소 볼륨은 backlog gauge로 간접 관측합니다(트레이드오프 8).
- `/actuator/prometheus`에서 회색지대 지표와 Resilience4j의 연산별 회로 상태·호출 결과를 함께 수집합니다. 운영에서는 nginx가 `/actuator`를 차단하고 내부 Prometheus만 접근합니다.

## DB 마이그레이션 (Flyway)

`V3__add_payment_confirming_state.sql`가 `CONFIRMING`과 `confirming_at`을, `V10__add_payment_canceling_state.sql`가 `CANCELING`과 `canceling_at`을 추가했습니다. 최종 CHECK 제약은 아래와 같습니다.

```sql
-- V3: 상태 CHECK 제약에 CONFIRMING 추가 + confirming_at 컬럼
-- V10: 상태 CHECK 제약에 CANCELING 추가 + canceling_at 컬럼
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
  CHECK (status IN ('READY','CONFIRMING','APPROVED','FAILED','CANCELED','CANCELING'));
ALTER TABLE payments ADD COLUMN IF NOT EXISTS confirming_at timestamp NULL;  -- V3
ALTER TABLE payments ADD COLUMN IF NOT EXISTS canceling_at  timestamp NULL;  -- V10
```

`confirming_at`/`canceling_at`은 각각 회색지대 진입 시각으로 grace 판정에 씁니다.

## 반영된 코드

| 영역 | 파일 |
| --- | --- |
| 상태/도메인 가드 | `Payment`(`startCanceling`/`cancel` 가드 CANCELING→CANCELED), `PaymentStatus` |
| 마이그레이션 | `V3__add_payment_confirming_state.sql`, `V10__add_payment_canceling_state.sql` |
| 동기 승인 오케스트레이션 | `PaymentConfirmService` |
| confirm 트랜잭션 경계 | `PaymentConfirmTransactionService` |
| PG 승인 응답 검증 | `PaymentPgApprovalValidator` |
| confirm 결정 정책(순수 함수) | `payment.application.recovery.RecoveryPolicy` |
| cancel 오케스트레이션 | `payment.application.cancel.PaymentCancelService` |
| cancel 트랜잭션 경계 | `payment.application.cancel.PaymentCancelTransactionService` |
| cancel 결정 정책(순수 함수) | `payment.application.cancel.PaymentCancelPolicy` |
| 통합 보정 스케줄러 | `PaymentRecoveryScheduler`(confirm→cancel→gauge) |
| 비동기 보정 | `PaymentRecoveryService`, `PaymentRecoveryTransactionService` |
| 메트릭 | `PaymentRecoveryMetrics` |
| PG 장애 차단 | `PaymentGatewayCircuitState`, `Resilience4jPaymentGatewayCircuitState`, `PaymentGatewayCircuitBreakers` |
| 취소 응답 DTO | `CancelPaymentResponse(paymentId, paymentStatus)` |

## 남은 리팩토링 후보

| 후보 | 이유 |
| --- | --- |
| `orderId` 불일치 보류 건 알림/지표화 | 현재 `log.error`만 남깁니다. 운영 규모가 커지면 모니터링 지표·알림으로 승격할 후보입니다. |
| 인덱스 후보 | `payments(status, confirming_at)` / `(status, canceling_at)`가 없어 주기당 status 기준 조회가 4회(stale 2 + count 2) 발생합니다. 저볼륨에선 무해하지만, 볼륨이 커지면 부분/복합 인덱스를 검토합니다(트레이드오프 6). |
| 패키지 결합 정리 | `payment.application.cancel` ↔ `payment.application.recovery`가 공용 `PaymentRecoveryMetrics`를 두고 양방향 참조합니다. DI 사이클은 없으나, 향후 관측성 등 중립 패키지로 이동할 후보입니다(트레이드오프 7). |

> **아래 항목은 향후 분산 아키텍처에서 함께 설계합니다** (Kafka + 분산락 + 분산 트랜잭션 + 서비스/DB 분해 도입 시):
> retry 카운터·백오프·최대 횟수, dead-letter, 알림 임계, `HOLD_MANUAL` 핫루프 차단, ShedLock(다중 인스턴스 가드), `CANCELING` 전용 워커 분리. 현재 규모에서는 durable 마커 + 단일 주기 보정으로 정합성이 보장되므로 도입하지 않습니다.

### 반영됨

- **confirm/cancel 두 회색지대 통합**: 취소도 승인과 같은 회색지대(`CANCELING`)로 다루고, 한 스케줄러 주기에서 confirm 배치 → cancel 배치 → backlog gauge를 순차 처리합니다. `Payment.cancel()` 가드를 `CANCELING -> CANCELED`로 좁혀 `APPROVED` 직행을 금지했습니다(REVERT 없음).
- **cancel 크래시 갭(CANCELING 마커) 해소**: PG 취소 성공 직후 커밋 전 크래시로 결제가 `APPROVED`로 남던 갭을, `APPROVED -> CANCELING`을 PG 호출 전에 커밋하는 durable 마커로 메웠습니다. 이제 취소 미확정은 `CANCELING`에 남아 보정이 `CANCELED`로 수렴합니다.
- **confirm 보정 환불을 트랜잭션 밖으로 분리**: 좌석 소실/데이터 불일치 환불 분기의 Toss cancel 호출을 보정 트랜잭션 밖으로 빼, **어떤 트랜잭션·행 락 안에서도 PG를 호출하지 않는** 구조로 통일했습니다(`PaymentRecoveryService.recover`가 apply 트랜잭션 전에 환불을 호출). 이전에는 환불이 보정 트랜잭션 안에서 실행됐습니다.
- **결정 규칙 정책 객체화(순수 함수)**: confirm은 `RecoveryPolicy.decide`, cancel은 `PaymentCancelPolicy.decide`로 결정 로직을 순수 함수로 추출해 트랜잭션·외부 호출과 분리했습니다(닫힌 고정 매트릭스라 전략 패턴까지는 가지 않고 메서드 추출 수준 유지).
- **보정 메트릭 노출(Micrometer)**: `payment_gray_zone_recovery_total`/`pg_failure_total`/`backlog`를 confirm/cancel 대칭으로 기록합니다(위 [메트릭](#메트릭-micrometer) 참고). 단, actuator 엔드포인트는 아직 열지 않았습니다.
- **소유자 검증(403)**: cancel 엔드포인트가 `@AuthenticationPrincipal Long userId`를 받아 `ReservationGroup.user.id`와 대조하고, 불일치 시 `ForbiddenAccessException` → `403`을 반환합니다. 검증은 `markCanceling` 트랜잭션 안에서 마킹 전에 수행합니다.
- **보정 스케줄러 건별 예외 격리**: confirm/cancel 배치 루프를 건별 try-catch로 감싸(`runRecoveryBatch`), 한 건의 실패(PG 오류·NPE·데이터 이상 등)가 배치 전체를 중단시키지 않도록 했습니다. 실패 건은 회색지대로 남아 다음 주기에 재시도되며 `log.error` + `recovery_total{outcome=batch_exception}`로 노출됩니다. 이 격리 전에는 한 건의 결정적 예외(poison pill)가 그 뒤 결제들을 영구히 방치할 수 있었습니다. 배치·게이지 갱신도 서로 독립 격리됩니다.
- **보정/만료 스케줄러 batch-size 제한**: 재기동 후 backlog가 한 번에 몰려 서버를 다시 압박하지 않도록, 결제 보정과 예약 만료 후보 조회를 한 주기당 설정된 개수로 제한했습니다.
- **Toss Payments timeout 명시**: 외부 PG 호출이 무한 대기하지 않도록 connect/read timeout을 설정했습니다. timeout은 결제 실패 확정이 아니라 결과 불명 상태로 보고, 기존 회색지대 조회/보정 흐름으로 수렴시킵니다.
- **연산별 PG Circuit Breaker**: 승인·조회·취소 회로를 분리하고, 승인 permit을 `CONFIRMING` 전이에 앞서 확보합니다. lookup 회로가 OPEN이면 보정 배치를 다음 주기로 넘기며, 일반 4xx는 외부 장애율에서 제외합니다. 자동 Retry는 추가하지 않았습니다.

## 트레이드오프

1. **(수용) confirm 보정 APPROVE 결정 + apply 시점 좌석 유실 → 환불 최대 1주기(~60s) 지연.** 예전엔 같은 트랜잭션에서 즉시 환불했습니다. 근거: 돈은 PG가 보관하고, 좌석은 `CONFIRMING`이라 만료가 skip되어 이중 판매가 없습니다. 다음 주기에 `REFUND_THEN_FAIL`로 자가 치유합니다.
2. **(수용) cancel 미확정(timeout/5xx/불일치) → 예외 대신 `200` + `CANCELING` durable 유지.** 스케줄러가 `CANCELED`로 수렴시킵니다. `markCanceling`을 통과한 뒤에는 `APPROVED`로 복귀하지 않아 사용자의 취소 의도가 보존됩니다. 사전 검사(소유자/상태/`paymentKey`) 실패만 예외를 던집니다.
3. **멱등키 배타성 불변식**: 한 결제는 CONFIRMING 계열(→환불→`FAILED`) 또는 APPROVED 계열(→취소→`CANCELED`) 중 하나만 타므로, `cancel:{id}`를 공유해도 충돌이 없습니다. 재취소 경로들끼리는 **반드시 동일 키**여야 동시 요청 레이스가 방어됩니다 — 키 프리픽스를 분리하면 안 됩니다.
4. **전액취소 전제**: `TossPaymentStatus.isCanceled`가 `PARTIAL_CANCELED`를 포함하지만 본 시스템은 취소를 항상 전액으로만 발행합니다. `PARTIAL_CANCELED`가 관측되면 운영 예외 신호이며, `FINALIZE`로 수렴합니다.
5. **gauge push 방식**: 스크레이프 시 DB 조회가 없다는 장점 대신, 스케줄러가 지속 실패하면 gauge가 stale해지는 단점이 있습니다(배치와 독립 격리로 완화).
6. **인덱스 부재**: `payments(status, confirming_at)`/`(status, canceling_at)`가 없어 주기당 status 기준 조회 4회(stale 2 + count 2)가 풀스캔에 가깝습니다. 저볼륨에선 무해하나 볼륨 증가 시 부분/복합 인덱스를 검토합니다.
7. **패키지 결합**: `payment.application.cancel` ↔ `payment.application.recovery`가 공용 `PaymentRecoveryMetrics`로 양방향 참조합니다. DI 사이클은 없습니다. 향후 중립(관측성) 패키지로 이동할 후보입니다.
8. **동기 취소 메트릭 미기록**: 메트릭 스코프가 "보정"이라 사용자 동기 취소는 카운터를 남기지 않습니다. 사용자 취소 볼륨은 backlog gauge로 간접 관측합니다.
9. **Circuit Breaker와 보정의 역할 분리**: 회로는 장애 중 신규 외부 호출을 줄이고, 중간 상태와 보정은 이미 시작된 결제의 최종 일관성을 담당합니다. 회로가 정합성 복구를 대신하지 않습니다.

## 관련 문서

- [상태 전이 설계](state-design.md)
- [PG 연동 클라이언트 트레이드오프](external-api-client-tradeoffs.md)
