# 테스트 검증 체크리스트

## 문서 목적

이 문서는 TicketLedger 백엔드에서 구현한 상태 전이, 동시성, 인증, 조회 최적화가 어떤 테스트로 검증됐는지 정리합니다.

포트폴리오 관점에서는 테스트 개수보다 **어떤 장애 가능성을 테스트로 막았는지**가 중요하므로, 핵심 검증 범위와 체크리스트를 함께 남깁니다.

## 검증 범위 요약

| 영역 | 검증한 문제 |
| --- | --- |
| 예약 생성 | 좌석 선점, 다중 좌석 원자성, 예매 오픈 전 차단 |
| 예약 만료 | `PENDING -> EXPIRED`, `HELD -> AVAILABLE`, `READY -> FAILED` |
| 결제 승인 | `CONFIRMING` 마커, 중복 결제 방어, 금액 검증, PG 응답 검증, 상태 확정 |
| 결제 취소 | 승인 결제만 취소, 좌석 복구, 중복 취소 방어 |
| 조회 | 공연 캐시, 좌석 조회 전 회차별 만료 처리 |
| 사용자 | 현재 사용자 조회, 본인 마이페이지 접근 제어 |
| 인증 | Refresh Token 조건부 재발급과 동시성 방어 |

<details>
<summary>ReservationService</summary>

### `createReservation()`

성공 케이스:

- [x] 정상적으로 예약 group이 생성됩니다.
- [x] 생성된 `ReservationGroup` 상태는 `PENDING`입니다.
- [x] 선택 좌석들의 `Seat` 상태는 `AVAILABLE -> HELD`로 전이됩니다.
- [x] group 안의 `Reservation` 상태는 `PENDING`입니다.
- [x] 환경 설정의 선점 유지 시간으로 `expiresAt`이 설정됩니다.
- [x] 하나의 group과 group 안의 `Reservation`은 동일한 `expiresAt`을 공유합니다.
- [x] 겹치는 좌석 묶음 동시 요청 시 하나의 group만 성공합니다.

실패 케이스:

- [x] 존재하지 않는 사용자면 `EntityNotFoundException`을 반환합니다.
- [x] 존재하지 않는 좌석이면 `EntityNotFoundException`을 반환합니다.
- [x] 예매 오픈 전 공연의 좌석이면 `IllegalStateException`을 반환합니다.
- [x] 좌석이 `AVAILABLE`이 아니면 `IllegalStateException`을 반환합니다.

</details>

<details>
<summary>ReservationExpirationService</summary>

성공 케이스:

- [x] 만료된 group 안의 pending `Reservation`은 `PENDING -> EXPIRED`로 전이됩니다.
- [x] 만료된 `ReservationGroup`은 `PENDING -> EXPIRED`로 전이됩니다.
- [x] 만료된 group 안의 좌석은 `HELD -> AVAILABLE`로 복구됩니다.
- [x] 만료된 group에 연결된 결제가 `READY`면 `FAILED`로 전이됩니다.
- [x] 스케줄러용 전체 만료 처리와 좌석 조회용 회차별 만료 처리가 분리됩니다.
- [x] 좌석 조회 시 현재 `scheduleId`의 만료 group만 먼저 정리합니다.
- [x] 전역 만료 배치의 각 group은 독립 트랜잭션으로 처리됩니다.

검증 포인트:

- [x] 만료 대상 group이 없으면 상태 변화가 없습니다.
- [x] 결제가 없거나 `READY`가 아니면 결제 상태는 변경하지 않습니다.
- [x] 만료 처리 반환값(`expiredCount`)이 실제 만료 처리 reservation 건수와 일치합니다.
- [x] `createReservation()`은 전체 만료 정리를 호출하지 않고 좌석 선점 생성만 담당합니다.
- [x] 중간 group의 만료가 실패해도 해당 트랜잭션만 롤백되고 이후 group은 계속 만료됩니다.

</details>

<details>
<summary>EventQueryService / SeatQueryService</summary>

### `getEvents()`

- [x] 같은 공연 목록 조회는 캐시되어 DB 조회를 반복하지 않습니다.

### `getEvent()`

- [x] 같은 공연 상세 조회는 캐시되어 DB 조회를 반복하지 않습니다.
- [x] Redis 회로가 OPEN이면 Redis 호출을 생략하고 제한된 DB fallback 경로를 사용합니다.
- [x] HALF_OPEN probe 성공 후 Redis 회로가 CLOSED로 복구됩니다.

### `getSeats()`

- [x] 해당 회차의 만료 예약을 정리한 뒤 좌석을 조회합니다.

</details>

<details>
<summary>PaymentService</summary>

### `readyPayment()`

성공 케이스:

- [x] group 안의 모든 `Reservation`이 `PENDING`이면 `Payment`가 `READY`로 생성됩니다.
- [x] `Payment.amount`는 그룹에 포함된 `Seat.price` 합계 기준으로 저장됩니다.
- [x] `Payment.orderId`가 생성됩니다.
- [x] 동일 `reservationGroupId` 재요청 시 기존 `Payment`를 재사용합니다.
- [x] 동일 `reservationGroupId` 재요청 후에도 결제 row는 1건만 유지됩니다.

실패 케이스:

- [x] 존재하지 않는 `reservationGroupId`면 `EntityNotFoundException`을 반환합니다.
- [x] group 안에 `PENDING`이 아닌 `Reservation`이 있으면 `IllegalStateException`을 반환합니다.
- [x] `ReservationGroup`이 만료됐으면 `IllegalStateException`을 반환합니다.

### `confirmPayment()`

성공 케이스:

- [x] `orderId`로 `Payment`를 조회합니다.
- [x] `Payment` 상태가 `READY`일 때 `CONFIRMING`을 거쳐 승인됩니다.
- [x] group 안의 `Reservation` 상태가 모두 `PENDING`일 때 승인됩니다.
- [x] amount 검증 통과 시 승인됩니다.
- [x] 승인 성공 시 `Payment APPROVED / ReservationGroup CONFIRMED / Reservation CONFIRMED / Seat BOOKED`로 확정됩니다.
- [x] `paymentKey`, `method`, `pgStatus`가 저장됩니다.
- [x] PG confirm 응답을 받지 못해도 조회 결과가 `DONE`이면 승인 상태를 확정합니다.
- [x] 동일 `orderId` 동시 승인 요청은 같은 멱등키로 PG를 호출하고 내부 상태는 확정 상태로 수렴합니다.
- [x] 동일 `orderId` 동시 승인 요청 후에도 승인 결제 row는 1건만 유지됩니다.
- [x] 오래된 `CONFIRMING` 결제는 PG 조회 결과가 `DONE`이고 좌석이 `HELD`이면 승인 상태로 보정됩니다.
- [x] PG 조회 결과가 `IN_PROGRESS`이면 `CONFIRMING/PENDING/HELD`를 유지하고, 다음 조회가 `DONE`이면 `APPROVED/CONFIRMED/BOOKED`로 수렴합니다.
- [x] PG는 `DONE`이지만 예약/좌석이 유효하지 않으면 환불 후 실패 상태로 정리됩니다.
- [x] 승인 회로가 OPEN이면 `READY`를 `CONFIRMING`으로 바꾸기 전에 `503`으로 거절합니다.
- [x] 승인 permit 획득 후 결과가 불명이면 `CONFIRMING`을 유지해 보정 대상으로 남깁니다.
- [x] PG 일반 4xx 거절은 Circuit Breaker 실패율에 포함하지 않습니다.

실패 케이스:

- [x] 존재하지 않는 `orderId`면 `EntityNotFoundException`을 반환합니다.
- [x] `Payment` 상태가 `READY`, `CONFIRMING`, `APPROVED` 처리 조건에 맞지 않으면 `IllegalStateException`을 반환합니다.
- [x] group 안의 `Reservation` 상태가 `PENDING`이 아니면 `IllegalStateException`을 반환합니다.
- [x] `ReservationGroup`이 만료됐으면 `IllegalStateException`을 반환합니다.
- [x] amount 불일치면 `IllegalStateException`을 반환합니다.
- [x] PG 승인 응답 금액/통화 불일치면 `IllegalStateException`을 반환합니다.
- [x] PG 승인 응답 결제키/orderId/status 불일치면 `IllegalStateException`을 반환합니다.

### `failPayment()`

- [x] `READY -> FAILED`로 전이됩니다.
- [x] group이 미만료면 `Reservation`/`Seat` 상태는 유지됩니다.
- [x] group이 만료면 `ReservationGroup EXPIRED`, `Reservation EXPIRED`, `Seat AVAILABLE`로 전이됩니다.
- [x] `READY`가 아니면 `IllegalStateException`을 반환합니다.

### `cancelPayment()`

- [x] `APPROVED` 결제만 `CANCELING`으로 전이할 수 있습니다.
- [x] PG 취소 성공 후 `Payment CANCELED / ReservationGroup CANCELED / Reservation CANCELED / Seat AVAILABLE`로 전이됩니다.
- [x] PG cancel 응답을 받지 못해도 조회 결과가 `CANCELED`면 취소 상태를 확정합니다.
- [x] PG cancel 실패 후 조회 결과가 아직 `DONE`이면 `CANCELING`을 유지합니다.
- [x] PG cancel timeout과 조회 실패가 겹치면 `CANCELING`을 유지하고 보정 대상으로 남깁니다.
- [x] 동일 `paymentId` 동시 취소 요청은 PG cancel을 1회만 호출하고 취소 상태를 재사용합니다.
- [x] 이미 `CANCELED`인 결제는 멱등하게 `CANCELED`를 반환합니다.
- [x] `APPROVED`/`CANCELING`이 아니면 `IllegalStateException`을 반환합니다.
- [x] `paymentKey`가 없으면 `IllegalStateException`을 반환합니다.
- [x] 결제 소유자가 아니면 `ForbiddenAccessException`(HTTP `403`)을 반환합니다.
- [x] PG 응답의 결제키/통화가 불일치하면 상태를 바꾸지 않고 `CANCELING` 수동 보류로 남깁니다.

### `recoverCanceling()`

- [x] 조회 결과가 `CANCELED`면 취소를 확정합니다.
- [x] 조회 결과가 `DONE`이면 같은 idempotency key로 PG 취소를 재요청한 뒤 확정합니다.
- [x] 재취소 후에도 `DONE`이면 `CANCELING`을 유지합니다.
- [x] 재취소와 재조회가 모두 실패하면 `CANCELING`을 유지합니다.
- [x] 결제키 불일치면 상태를 바꾸지 않고 수동 보류로 남깁니다.
- [x] 대상이 이미 `CANCELING`이 아니면 아무것도 하지 않습니다.
- [x] 어떤 경로에서도 `CANCELING -> APPROVED` 되돌림은 발생하지 않습니다.

### `getPaymentStatus()`

- [x] `paymentId`로 현재 결제 상태를 조회합니다.
- [x] `Payment / Reservation / Seat` 상태를 함께 반환합니다.
- [x] 프론트 폴링 기준으로 사용할 수 있습니다.

</details>

<details>
<summary>Controller</summary>

### `ReservationController`

- [x] 예약 생성 성공은 `200`을 반환합니다.
- [x] validation 실패는 `400`을 반환합니다.
- [x] 존재하지 않는 user/seat는 `404`를 반환합니다.

### `PaymentController`

- [x] `/payments/ready` 성공은 `200`을 반환합니다.
- [x] `/payments/confirm` 성공은 `200`을 반환합니다.
- [x] `/payments/{id}/status` 성공은 `200`을 반환합니다.
- [x] `/payments/{id}/cancel` 성공은 `200`을 반환합니다.
- [x] `/payments/fail-redirect` 성공은 `200`을 반환합니다.
- [x] 잘못된 상태 전이는 `409`를 반환합니다.

</details>

<details>
<summary>User API</summary>

### `users/me`

- [x] 로그인 사용자 id로 현재 사용자 기본 정보를 반환합니다.
- [x] 존재하지 않는 사용자면 `EntityNotFoundException`을 반환합니다.
- [x] 컨트롤러가 `@AuthenticationPrincipal Long userId`를 서비스로 전달합니다.

### `mypage`

- [x] 본인 `userId`만 조회 가능합니다.
- [x] 다른 사용자 조회 시 `IllegalStateException`을 반환합니다.
- [x] `ReservationGroup.status`가 `CONFIRMED`, `CANCELED`인 예매를 group 기준으로 반환합니다.
- [x] 마이페이지 예매 상태 값은 `ReservationGroup.status`를 사용합니다.
- [x] `APPROVED`, `CANCELED` 결제를 group 기준으로 반환합니다.
- [x] 결제 취소에 필요한 `paymentId`와 좌석 목록을 포함합니다.
- [x] 컨트롤러가 path variable `userId`와 principal `userId`를 서비스로 전달합니다.

</details>

<details>
<summary>AuthService</summary>

### `reissue()`

- [x] 정상 재발급 시 기존 Refresh Token을 소비하고 새 토큰을 저장합니다.
- [x] 이미 소비된 Refresh Token이면 재발급을 거부합니다.
- [x] 동일 Refresh Token `20`개 동시 재발급 요청은 하나만 성공하고 나머지 `19`개는 거부합니다.
- [x] 같은 사용자의 서로 다른 Refresh Token은 각각 독립적으로 재발급됩니다.

</details>

## 핵심 목표

- 상태 전이 정합성을 보장합니다.
- PG 호출 전후로 나뉜 트랜잭션에서도 `Seat`, `Reservation`, `Payment` 상태가 같은 결과로 수렴하는지 확인합니다.
- 만료, 이탈, 취소 같은 예외 상황에서도 상태 불일치가 없도록 검증합니다.
