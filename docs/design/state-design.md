# 상태 전이 설계

## 문서 목적

이 문서는 TicketLedger 백엔드가 좌석 선점, 예약 만료, 결제 승인, 결제 취소를 어떤 상태 전이 규칙으로 처리하는지 정리합니다.

이 프로젝트에서 중요한 지점은 "요청이 성공했는가"보다 **좌석, 예매, 결제 상태가 같은 결과로 수렴하는가**입니다. 그래서 기능별 로직보다 상태 전이와 금지 전이를 먼저 정의했습니다.

## 바로가기

- [핵심 문제](#핵심-문제)
- [상태 전이 한눈에 보기](#상태-전이-한눈에-보기)
- [좌석 상태](#좌석-상태)
- [예매 상태](#예매-상태)
- [결제 상태](#결제-상태)
- [상태 연동 규칙](#상태-연동-규칙)
- [설계 판단과 트레이드오프](#설계-판단과-트레이드오프)
- [검증 근거](#검증-근거)

## 핵심 문제

| 문제 | 상태 전이 기준 |
| --- | --- |
| 같은 좌석에 여러 사용자가 동시에 접근합니다. | 하나의 요청만 `AVAILABLE -> HELD`에 성공해야 합니다. |
| 여러 좌석을 한 번에 예매합니다. | 선택 좌석 전체가 가능할 때만 같은 `ReservationGroup`으로 묶어야 합니다. |
| 선점 후 결제하지 않는 사용자가 생깁니다. | 만료 시 `HELD -> AVAILABLE`, `PENDING -> EXPIRED`로 복구해야 합니다. |
| 결제 승인과 만료가 겹칠 수 있습니다. | 먼저 확정된 상태만 최종 결과로 남아야 합니다. |
| 외부 PG 응답이 불확실할 수 있습니다. | PG 조회 결과를 기준으로 내부 상태를 다시 수렴시켜야 합니다. |

## 상태 전이 한눈에 보기

| 대상 | 생성/선점 | 확정 | 실패/만료 | 취소 |
| --- | --- | --- | --- | --- |
| `Seat` | `AVAILABLE -> HELD` | `HELD -> BOOKED` | `HELD -> AVAILABLE` | `BOOKED -> AVAILABLE` |
| `ReservationGroup` | `PENDING` | `PENDING -> CONFIRMED` | `PENDING -> EXPIRED` | `CONFIRMED -> CANCELED` |
| `Reservation` | `PENDING` | `PENDING -> CONFIRMED` | `PENDING -> EXPIRED` | `CONFIRMED -> CANCELED` |
| `Payment` | `READY` | `READY -> CONFIRMING -> APPROVED` | `READY/CONFIRMING -> FAILED` | `APPROVED -> CANCELING -> CANCELED` |

## 좌석 상태

| 상태 | 의미 |
| --- | --- |
| `AVAILABLE` | 예매 가능한 좌석입니다. |
| `HELD` | 예약 그룹이 일시 선점한 좌석입니다. |
| `BOOKED` | 결제 승인까지 완료된 좌석입니다. |

좌석은 `AVAILABLE` 상태에서만 선택할 수 있습니다. 예매 오픈 전이면 좌석이 `AVAILABLE`이어도 예약 생성 단계에서 거부합니다.

금지 전이:

```text
BOOKED -> HELD
AVAILABLE -> BOOKED
```

## 예매 상태

`ReservationGroup`은 사용자가 한 번에 선택한 좌석 묶음이고, `Reservation`은 묶음 안의 좌석별 예매 row입니다.

| 상태 | 의미 |
| --- | --- |
| `PENDING` | 결제 대기 중입니다. |
| `CONFIRMED` | 결제 승인 후 예매가 확정됐습니다. |
| `CANCELED` | 결제 취소와 함께 예매가 취소됐습니다. |
| `EXPIRED` | 결제하지 않아 선점 시간이 만료됐습니다. |

금지 전이:

```text
CONFIRMED -> PENDING
EXPIRED -> CONFIRMED
CANCELED -> CONFIRMED
```

## 결제 상태

| 상태 | 의미 |
| --- | --- |
| `READY` | 결제 준비가 생성됐습니다. |
| `CONFIRMING` | 결제 승인을 시도해 PG 호출 단계에 진입했습니다. 승인 결과는 아직 미확정입니다. |
| `APPROVED` | PG 승인까지 완료됐습니다. |
| `FAILED` | 결제 실패 또는 만료로 실패 처리됐습니다. |
| `CANCELING` | 승인된 결제 취소를 시작해 PG 취소 호출 단계에 진입했습니다. 취소 결과는 아직 미확정입니다. |
| `CANCELED` | 승인된 결제가 취소됐습니다. |

`CONFIRMING`, `CANCELING`과 보정 스케줄러는 현재 코드에 반영되어 있습니다. 결제 승인과 취소는 모두 PG 호출 전후로 트랜잭션을 나누고, `CONFIRMING`/`CANCELING`을 durable 마커로 사용합니다. 두 상태는 모두 "외부 호출을 시도했지만 결과가 아직 확정되지 못한" 회색지대이며, 보정 스케줄러가 각각 `APPROVED`(또는 `FAILED`)/`CANCELED`로 수렴시킵니다.

### 전이 규칙

```text
READY -> CONFIRMING       // confirm 진입 시 PG 호출 전에 먼저 커밋 (durable 마커)
CONFIRMING -> APPROVED    // PG 승인 + 내부 상태 확정
CONFIRMING -> FAILED      // PG 미승인 확인 (보정 또는 재진입 시)
READY -> FAILED           // 결제를 시도하지 않은 채 만료
APPROVED -> CANCELING     // 취소 진입 시 PG 취소 호출 전에 먼저 커밋 (durable 마커)
CANCELING -> CANCELED     // PG 취소 확인 + 내부 상태 확정
```

금지 전이:

```text
APPROVED / FAILED / CANCELED -> CONFIRMING
CONFIRMING -> READY
CANCELING -> APPROVED       // 취소 의도 마킹 후 승인 복귀 없음(REVERT 없음)
CANCELING -> FAILED
CANCELED -> *               // 취소 완료는 종단 상태
```

도메인 가드(`Payment`):

| 메서드 | 허용 상태 | 결과 |
| --- | --- | --- |
| `confirming()` | `READY` | `CONFIRMING` |
| `approve()` | `CONFIRMING` | `APPROVED` |
| `fail()` | `READY` 또는 `CONFIRMING` | `FAILED` |
| `startCanceling()` | `APPROVED` | `CANCELING` |
| `cancel()` | `CANCELING` | `CANCELED` |

### `CONFIRMING` 도입 이유

PG 승인(돈이 빠짐)과 내부 상태 확정은 하나의 트랜잭션으로 묶을 수 없습니다. confirm 진입 시 `READY -> CONFIRMING`을 **PG 호출 전에 먼저 커밋**해 두면, PG 호출 이후 커밋 실패나 프로세스 종료가 발생해도 결제가 `CONFIRMING`에 남습니다. 따라서 "승인을 시도했지만 확정되지 못한" 결제를 식별할 수 있고, 이 `CONFIRMING` row가 보정 스케줄러의 처리 대상이 됩니다.

### confirm 재진입 정책

이미 `CONFIRMING`인 결제에 confirm 요청이 다시 들어오면(사용자 재시도 또는 이전 요청 중단), 거부하지 않고 같은 `orderId` 기반 멱등키로 PG confirm을 다시 호출해 한 방향으로 수렴시킵니다. PG confirm 응답을 받지 못한 경우에는 `paymentKey` 또는 `orderId`로 PG 상태를 재조회합니다.

| PG 확인 결과 | 좌석 | 결과 |
| --- | --- | --- |
| `DONE` | 유효(`HELD`) | `CONFIRMING -> APPROVED` |
| `DONE` | 소실(만료·재판매) | 환불 후 `CONFIRMING -> FAILED` |
| 미승인 | - | `CONFIRMING -> FAILED` |
| 불명 | - | 보류, 보정 스케줄러가 이후 처리 |

"진행 중"으로 일정 시간 결제를 잠그는 방식은 사용자를 불필요하게 대기시키므로 채택하지 않았습니다. 대신 PG 호출 중에는 DB 락을 오래 잡지 않고, 이후 재조회와 보정으로 수렴시킵니다.

승인된 결제만 취소할 수 있습니다. `FAILED` 또는 `CANCELED` 결제는 다시 `APPROVED`로 되돌리지 않습니다.

### `CANCELING` 도입 이유

취소도 승인과 같은 회색지대입니다. PG 취소(돈이 돌아감)와 내부 상태 확정은 하나의 트랜잭션으로 묶을 수 없습니다. 취소 진입 시 `APPROVED -> CANCELING`을 **PG 취소 호출 전에 먼저 커밋**해 두면, 이후 커밋 실패나 프로세스 종료가 발생해도 결제가 `CANCELING`에 남습니다. 이 durable 마커는 두 가지를 보장합니다.

- **사용자의 취소 의도를 보존합니다.** `CANCELING`을 통과한 결제는 어떤 경로로도 `APPROVED`로 되돌아가지 않습니다(REVERT 없음). 탈출구는 `CANCELED`(또는 운영자가 수동 검토하도록 `CANCELING` 유지)뿐입니다.
- **보정 대상을 식별할 수 있습니다.** "취소를 시도했지만 확정되지 못한" 결제를 `CANCELING` row로 남겨 보정 스케줄러가 처리하게 합니다.

`CONFIRMING`이 "승인 시도 후 미확정"이라면 `CANCELING`은 "취소 시도 후 미확정"으로, 두 회색지대는 대칭입니다.

### 취소 재진입 정책

이미 `CANCELING`인 결제에 취소 요청이 다시 들어오면(사용자 재시도, 보정 재취소, confirm 보정의 환불), 거부하지 않고 **추가 마킹 없이** 같은 멱등키 `"cancel:" + paymentId`로 PG 취소를 다시 호출해 한 방향으로 수렴시킵니다. PG 취소 응답을 받지 못하면 `paymentKey`로 PG 상태를 재조회합니다.

| PG 확인 결과 | 결과 |
| --- | --- |
| 취소됨 + paymentKey·원결제 금액·통화 일치 + `balanceAmount=0` | `CANCELING -> CANCELED` + 좌석 release |
| `PARTIAL_CANCELED` + 취소 가능 잔액 존재 | 자동 처리 안 함, `CANCELING`/`BOOKED` 유지(수동 검토) |
| 아직 승인(`DONE`) + paymentKey·원결제 금액·통화 일치 | 재취소 발행, `CANCELING` 유지 |
| 응답값 불일치 또는 잔액 불명 | 자동 처리 안 함, `CANCELING` 유지(수동 검토) |
| 미확정(timeout·5xx) | 보류, 보정 스케줄러가 이후 처리 |

취소 요청 자체는 확정 응답을 강제하지 않습니다. 확정되지 않으면 예외 대신 `CANCELING` 상태를 반환하고, 스케줄러가 `CANCELED`로 수렴시킵니다. 다만 취소 시작 전 사전 검사(소유자 검증, 상태 검증, `paymentKey` 존재)에 실패하면 마킹 없이 예외를 던집니다.

> 스키마: `payments.status`의 CHECK 제약(`payments_status_check`)은 Flyway `V3__add_payment_confirming_state.sql`에서 `CONFIRMING`을 포함하도록 재생성했습니다. `confirming_at` 컬럼도 같은 migration에서 추가했습니다. 이후 Flyway `V10__add_payment_canceling_state.sql`에서 같은 CHECK 제약에 `CANCELING`을 추가하고 `canceling_at` 컬럼을 추가했습니다.

## 상태 연동 규칙

| 상황 | `Payment` | `ReservationGroup` | `Reservation` | `Seat` |
| --- | --- | --- | --- | --- |
| 예약 생성 | - | `PENDING` | `PENDING` | `HELD` |
| 결제 준비 | `READY` | `PENDING` 유지 | `PENDING` 유지 | `HELD` 유지 |
| 결제 승인 진행 | `READY -> CONFIRMING` | `PENDING` 유지 | `PENDING` 유지 | `HELD` 유지 |
| 결제 승인 | `CONFIRMING -> APPROVED` | `CONFIRMED` | `CONFIRMED` | `BOOKED` |
| 예약 만료 | `READY -> FAILED` | `EXPIRED` | `EXPIRED` | `AVAILABLE` |
| 결제 취소 진행 | `APPROVED -> CANCELING` | `CONFIRMED` 유지 | `CONFIRMED` 유지 | `BOOKED` 유지 |
| 결제 취소 | `CANCELING -> CANCELED` | `CANCELED` | `CANCELED` | `AVAILABLE` |

PG 승인/취소 요청 후 서버가 응답을 받지 못한 경우에는 `orderId` 조회 결과를 기준으로 내부 상태를 확정합니다. 응답을 받지 못한 채 커밋 실패나 프로세스 종료가 발생하면 결제가 `CONFIRMING`에 남고, 보정 스케줄러가 `orderId`로 PG를 재조회해 위 [confirm 재진입 정책](#confirm-재진입-정책)과 같은 기준으로 수렴시킵니다. 자세한 설계는 [결제 장애 복구 설계](payment-failure-recovery-design.md)를 참고하세요.

현재 구현에서 보정 대상은 오래된 `CONFIRMING` 결제입니다. PG 조회 결과가 `DONE`이고 예약/좌석이 아직 `PENDING/HELD`이며 금액·통화가 일치하면 내부 상태를 `APPROVED/CONFIRMED/BOOKED`로 확정합니다. PG는 `DONE`이지만 좌석이 유효하지 않거나 금액·통화가 불일치하면 환불 후 실패 상태로 정리합니다(`orderId` 자체가 불일치하면 자동 처리하지 않고 보류). 또한 confirm 요청 처리 중 회색지대로 실패하면 컨트롤러가 같은 보정 로직을 동기로 1회 시도하고, 확정되지 않으면 `CONFIRMING`으로 두어 스케줄러에 위임합니다.

결제 취소도 대칭입니다. 취소 시작 시 `APPROVED -> CANCELING`을 PG 취소 호출 전에 먼저 커밋해 durable 마커를 남기고, PG 취소가 확인되면 `CANCELING -> CANCELED`로 확정하면서 예매·좌석을 함께 정리합니다. `CANCELING`인 동안에는 `ReservationGroup=CONFIRMED`, `Seat=BOOKED`를 유지하므로, 만료 스케줄러는 이 예약을 만료 대상(결제 `PENDING`)으로 보지 않아 좌석을 풀지 않습니다. 따라서 `CANCELING`이 오래 남아도 이중 판매나 돈 손실은 발생하지 않고, 보정 스케줄러가 `CANCELED`로 수렴시킵니다. 마이페이지 결제 내역에는 `APPROVED`, `CANCELING`, `CANCELED`가 노출됩니다.

## 설계 판단과 트레이드오프

| 판단 | 선택하지 않은 대안 | 이유 |
| --- | --- | --- |
| 예매를 `ReservationGroup` 기준으로 묶었습니다. | 좌석마다 독립 결제를 생성하는 방식 | 다중 좌석 예매에서 부분 성공을 만들지 않기 위해서입니다. |
| 좌석 상태는 캐시하지 않습니다. | 좌석 상태를 Redis나 로컬 캐시에 저장하는 방식 | 예약, 만료, 결제 취소가 모두 좌석 상태를 바꾸므로 캐시 동기화 비용이 커집니다. |
| 만료 처리는 유지합니다. | 스케줄러만 의존하는 방식 | 만료된 좌석이 다음 스케줄러 실행 전까지 판매 불가 상태로 남을 수 있습니다. |
| 결제 승인·취소 중간 상태로 `CONFIRMING`/`CANCELING`을 둡니다. | PG 호출 동안 DB 락을 계속 잡는 방식 | 외부 호출 시간을 트랜잭션 밖으로 빼면서 크래시 이후 보정할 durable 마커를 남기기 위해서입니다. 취소도 승인과 같은 회색지대라 대칭으로 마커를 둡니다. |
| 화면 표시 상태는 프론트에서 계산합니다. | 백엔드가 `displayStatus`를 내려주는 방식 | `UPCOMING`, `NOW_SHOWING`, `ENDED`는 도메인 정합성이 아니라 화면 표시값이기 때문입니다. |
| 좌석 선점 락 대기 상한을 1초로 둡니다(`jakarta.persistence.lock.timeout=1000`). | 기본값인 무한 대기 | 인기 좌석에 경합이 몰리면 대기자들이 스레드·DB 커넥션을 오래 붙잡아 커넥션 풀 고갈로 이어질 수 있습니다. 1초 안에 락을 못 얻으면 다른 요청이 선점 처리 중인 것으로 보고 즉시 실패시켜, 대기자가 자원을 오래 점유하지 않게 했습니다. 대신 락 보유 트랜잭션이 곧 롤백될 예정이었어도 기다리지 않고 거부합니다. 다만 이 트랜잭션엔 외부 PG 호출이 없고 검증도 락 전에 이미 참인 값 재확인이라 롤백 확률 자체가 낮아 비용은 작다고 판단했습니다. 재시도·서킷브레이커는 도입하지 않았습니다 — 좌석이 실제로 선점된 경우 재시도해도 풀리지 않고, 오히려 경합이 몰린 순간 자동 재시도가 커넥션 고갈을 다시 만들 수 있습니다. |

## 검증 근거

- 겹치는 좌석 묶음 동시 요청에서 하나의 `ReservationGroup`만 성공했습니다.
- 다중 좌석 예매에서 부분 성공 예매 그룹 `0`을 확인했습니다.
- 결제 승인 후 `Payment=APPROVED`, `ReservationGroup=CONFIRMED`, `Reservation=CONFIRMED`, `Seat=BOOKED` 상태 불일치 `0`을 확인했습니다.
- PG 조회 결과가 `DONE`이고 좌석이 아직 `HELD`인 오래된 `CONFIRMING` 결제가 승인 상태로 수렴하는 것을 확인했습니다.
- PG 조회 결과가 `DONE`이지만 좌석이 유효하지 않은 경우 환불 후 실패 상태로 정리되는 것을 확인했습니다.
- 결제 취소 후 좌석이 다시 `AVAILABLE`로 복구되는 흐름을 확인했습니다.
- 인기 공연 E2E 부하 테스트 후 중복 활성 좌석 배정 `0`, 부분 성공 그룹 `0`, 상태 불일치 `0`을 확인했습니다.

관련 문서:

- [동시성 검증](../testing/concurrentTest.md)
- [성능 개선 요약](../performance/performance-e2e-optimization-summary.md)
- [성능 테스트 전략](../performance/performance-test-strategy.md)
