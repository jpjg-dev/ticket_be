# 결제 장애 복구 설계 (CONFIRMING + 보정 스케줄러)

> **상태: 도입 예정(미구현).** 이 문서는 적용할 목표 설계입니다. 현재 배포 동작은 `READY -> APPROVED` 단일 트랜잭션이며, PG 호출 실패 시 1회 재조회만 수행합니다.

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
| 동기 — 컨트롤러 try-catch | 코드 살아있음 | confirm 실패 시 `orderId`로 PG 최종 조회 → 사용자에게 결과 응답 | 상태 수정은 하지 않고 `CONFIRMING`으로 남겨 둠 |
| 동기 — confirm 재진입 | 코드 살아있음 | 이미 `CONFIRMING`인 결제에 재요청 시 PG 재조회로 수렴 | 사용자 재시도가 자가복구 |
| 비동기 — 보정 스케줄러 | 크래시 등으로 동기 복구 불가 | 나중에 DB 스캔 → PG 재조회 → 수렴 | 최종 일관성 보장 |

> **재시도 + 지수 백오프, Circuit Breaker는 의도적으로 도입하지 않습니다(고려사항/roadmap).** 단일 PG·모놀리식 규모에서는 과설계이며, durable 마커 + 보정 스케줄러로 정합성은 보장됩니다.

## confirm 흐름 (최소 범위)

PG 호출은 기존대로 락 안에서 수행하고, 앞에 `CONFIRMING` 선커밋만 추가합니다. (PG 호출을 락 밖으로 빼는 락-타임 최적화는 별도 리팩토링.)

```text
Tx1: 락 → READY 검증 → READY -> CONFIRMING → 커밋        // durable 마커 확보
PG confirm 호출 (멱등키, 락 안)
Tx2: 락 → CONFIRMING -> APPROVED + 예매 CONFIRMED + 좌석 BOOKED → 커밋
```

Tx2에서 커밋 실패/크래시 시 결제는 `CONFIRMING`에 남습니다.

## 보정 스케줄러 설계

### 1. 후보 선정 — `CONFIRMING` 상태

```text
status = CONFIRMING AND confirming_at < now - grace
```

- `grace = 1분`(권장): 정상 confirm은 수초이므로, 1분이면 진행 중 요청과 겹치지 않고, hold(5분) 안이라 좌석이 살아있을 때 적용(apply)할 수 있습니다.
- 진행 중인 요청은 `Payment` row 락을 쥐고 있어 스케줄러가 동시에 건드리지 못합니다.

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

## 적용 순서 / 미적용 이유

- 결제 정합성 핵심 경로라 TDD 동시성 테스트(보정 ↔ 라이브 confirm ↔ 만료 경합)를 먼저 작성합니다.
- 단계: ① `CONFIRMING` 상태 + 도메인 가드 + 마이그레이션 → ② confirm 2-phase(선커밋) + 재진입 수렴 + 컨트롤러 try-catch → ③ 보정 스케줄러.

## 관련 문서

- [상태 전이 설계](state-design.md)
- [PG 연동 클라이언트 트레이드오프](external-api-client-tradeoffs.md)
