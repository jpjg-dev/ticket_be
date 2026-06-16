# 결제 장애 복구 설계 (CONFIRMING + 보정 스케줄러)

> **상태: 반영됨.** `CONFIRMING` 중간 상태, PG 호출 전후 트랜잭션 분리, 오래된 `CONFIRMING` 결제 보정 스케줄러가 코드에 반영되었습니다. 아래에는 현재 구현 기준과 남은 리팩토링 후보를 함께 정리합니다.

## 문서 목적

PG 승인 이후 내부 상태가 반영되지 않는 장애를 어떻게 복구할지 정리합니다. 핵심은 `CONFIRMING` 중간 상태를 durable 마커로 두고, 보정 스케줄러가 이를 기준으로 내부 상태를 PG 진실에 수렴시키는 것입니다.

## 문제 정의

결제 승인은 두 시스템에 걸쳐 있어 하나의 트랜잭션으로 묶을 수 없습니다.

```
PG(외부) 승인 = 돈 빠짐   ──┐
                            ├─ 원자적으로 묶을 수 없음
내부 DB 상태 확정          ──┘
```

| 실패 모드 | 설명 | 코드가 살아있나 |
| --- | --- | --- |
| ① PG 호출 실패(응답 불명) | 타임아웃·네트워크 | O |
| ② PG 성공 + 커밋 실패 | 승인 직후 DB 커밋 실패 | O |
| ③ PG 성공 + 프로세스 크래시 | 승인 직후 강제 종료(전원·OOM·kill) | X |

## 핵심 아이디어 — `CONFIRMING` durable 마커

confirm 진입 시 `READY -> CONFIRMING`을 **PG 호출 전에 먼저 커밋**합니다. PG 호출 이후 커밋 실패나 크래시가 나도 결제는 `CONFIRMING`에 남아, "승인을 시도했지만 확정되지 못한" 결제를 식별할 수 있습니다. 이 `CONFIRMING` row가 복구 대상입니다.

## 방어 계층

| 계층 | 언제 | 무엇 | 비고 |
| --- | --- | --- | --- |
| 동기 — confirm 서비스 | 코드 살아있음 | confirm 실패 시 `orderId`로 PG 최종 조회 → `DONE`이면 내부 상태 확정 | `PaymentConfirmService`에서 처리 |
| 동기 — confirm 재진입 | 코드 살아있음 | `CONFIRMING`이면 같은 멱등키로 confirm 재호출, `APPROVED`면 기존 결과 재사용 | 중복 완료 요청 방어 |
| 비동기 — 보정 스케줄러 | 크래시 등으로 동기 복구 불가 | 나중에 DB 스캔 → PG 재조회 → 수렴 | 최종 일관성 보장 |

> **재시도 + 지수 백오프, Circuit Breaker는 의도적으로 도입하지 않습니다(고려사항/roadmap).** 단일 PG·모놀리식 규모에서는 과설계이며, durable 마커 + 보정 스케줄러로 정합성은 보장됩니다.

## confirm 흐름

PG 호출은 DB 트랜잭션 밖에서 수행합니다. 트랜잭션은 PG 호출 전 `CONFIRMING` 마커를 남기는 구간과, PG 결과를 내부 상태에 반영하는 구간으로 나눕니다.

```text
Tx1: 락 → READY 검증 → READY -> CONFIRMING → 커밋        // durable 마커 확보
PG confirm 호출 (멱등키, 트랜잭션 밖)
Tx2: 락 → CONFIRMING -> APPROVED + 예매 CONFIRMED + 좌석 BOOKED → 커밋
```

Tx2에서 커밋 실패/크래시 시 결제는 `CONFIRMING`에 남습니다.

## 보정 스케줄러 설계

### 1. 후보 선정 — `CONFIRMING` 상태

```text
status = CONFIRMING AND confirming_at < now - grace
```

- `grace = 1분`: 정상 confirm은 수초 안에 끝나므로, 진행 중 요청과 보정 스케줄러가 과하게 겹치지 않도록 둔 완충 시간입니다.
- 스케줄러는 최종 반영 직전에 `Payment` row를 다시 잠그고 `CONFIRMING` 상태인지 재확인합니다.

### 2. 조회 키 — `orderId`

`orderId`는 ready 시점에 저장되어 항상 살아남습니다. `paymentKey`는 PG 재조회 응답에서 얻습니다. 멱등키 `"confirm:" + orderId`는 deterministic이라 재호출해도 안전합니다(15일 윈도우 = 안전 상한).

### 3. 락은 PG 조회 밖에서

```text
[락 없음] orderId로 PG 상태 조회       // 외부 호출
[락]      상태 재확인(CONFIRMING?) → 적용/환불  // 짧게만 락
```

### 4. 결정 매트릭스

| PG 조회 | 좌석 | 액션 |
| --- | --- | --- |
| `DONE` | 유효(`HELD`) | `CONFIRMING -> APPROVED` (내정립) |
| `DONE` | 소실(만료·재판매) | 환불(cancel) → `CONFIRMING -> FAILED` |
| 미승인 | - | `CONFIRMING -> FAILED` + 좌석 release |
| 불명 | - | 보류, 다음 주기 |

만료로 좌석이 풀린 경우 무리하게 재확정하지 않고 환불합니다. 환불 호출 자체가 실패하면 다음 주기에 재시도합니다(멱등 cancel).

### 5. 주기

`fixedDelay` 약 30초~1분. 만료 스케줄러 패턴(`ReservationExpirationScheduler`)을 재사용합니다.

## 만료 스케줄러와의 경합

- **만료 스케줄러는 결제가 `CONFIRMING`인 예약의 좌석을 풀지 않습니다.** 돈이 떠 있을 수 있으므로 보정이 처리하도록 양보합니다.
- 생애주기 분리: 만료는 결제 `READY`(시도 안 함)인 예약을, 보정은 `CONFIRMING`을 다룹니다.
- 겹쳐도 락 순서 일관(`Payment` 먼저) + 상태 재확인으로 데드락·중복 처리를 막습니다.

## DB 마이그레이션 (Flyway)

```sql
-- 1) 상태 CHECK 제약에 CONFIRMING 추가
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
  CHECK (status IN ('READY','CONFIRMING','APPROVED','FAILED','CANCELED'));

-- 2) CONFIRMING 진입 시각 (grace 판정용)
ALTER TABLE payments ADD COLUMN confirming_at timestamp NULL;
```

## 반영된 코드

| 영역 | 파일 |
| --- | --- |
| 상태/도메인 가드 | `Payment`, `PaymentStatus` |
| 마이그레이션 | `V3__add_payment_confirming_state.sql` |
| 동기 승인 오케스트레이션 | `PaymentConfirmService` |
| confirm 트랜잭션 경계 | `PaymentConfirmTransactionService` |
| PG 승인 응답 검증 | `PaymentPgApprovalValidator` |
| 비동기 보정 | `PaymentRecoveryScheduler`, `PaymentRecoveryService`, `PaymentRecoveryTransactionService` |

## 남은 리팩토링 후보

| 후보 | 이유 |
| --- | --- |
| 보정 환불 호출을 트랜잭션 밖으로 분리 | 현재 좌석 소실 환불 분기는 보정 트랜잭션 안에서 Toss cancel을 호출합니다. 빈도는 낮지만 외부 호출을 완전히 트랜잭션 밖으로 빼면 구조가 더 일관됩니다. |
| `PaymentRecoveryTransactionService` 결정 로직 세분화 | PG 조회 결과 판단, 내부 상태 판단, 환불 요청 판단을 더 작은 정책 객체로 나누면 테스트 단위가 선명해집니다. |
| 컨트롤러 레벨 최종 조회 정책 검토 | 현재 동기 confirm의 재조회는 서비스에서 처리합니다. 컨트롤러 try-catch까지 둘지는 사용자 응답 정책과 함께 결정할 후보입니다. |
| 보정 실패 재시도 정책 고도화 | 현재는 다음 스케줄 주기에 재시도합니다. 운영 규모가 커지면 retry count, backoff, 알림 지표를 추가할 수 있습니다. |

## 관련 문서

- [상태 전이 설계](state-design.md)
- [PG 연동 클라이언트 트레이드오프](external-api-client-tradeoffs.md)
