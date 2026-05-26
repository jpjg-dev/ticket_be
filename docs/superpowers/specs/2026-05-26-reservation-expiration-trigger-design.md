# Reservation Expiration Trigger Design

## Decision

예약 만료 처리는 예약 생성 책임에서 분리하고 `ReservationExpirationService`가 담당한다.

- 스케줄러는 `expireAll()`을 호출해 전체 만료 group을 정리한다.
- 좌석 조회는 `expireByScheduleId(scheduleId)`를 호출해 현재 회차의 만료 group만 정리한 뒤 최신 좌석을 반환한다.
- 예약 생성은 선택 좌석의 비관적 락 및 상태 검증만 담당하고, 전체 만료 정리를 호출하지 않는다.

## Reasoning

사용자가 좌석 선택 화면에 진입할 때 만료된 선점 좌석이 즉시 다시 보여야 한다. 예약 생성 시 전체 만료를 처리하면 화면 노출 시점보다 늦고, 관계없는 다른 회차까지 함께 갱신한다. 반대로 스케줄러만 사용하면 스케줄 주기만큼 만료 좌석이 판매 불가로 남을 수 있다.

따라서 전체 정리는 스케줄러의 안전망으로 유지하고, 화면 조회 시에는 요청한 회차만 정리한다.

## Transaction Boundary

만료 처리에서는 `Payment`, `Reservation`, `Seat` 상태가 함께 변경되므로 쓰기 트랜잭션이 필요하다. 현재 `EventService`는 클래스 수준에서 `readOnly = true`이므로, `getSeats()`는 쓰기 가능한 트랜잭션으로 재정의해 회차별 만료 정리와 좌석 조회를 한 흐름으로 실행한다.

## Test Scope

- `ReservationExpirationService.expireAll()`이 기존 전체 만료 상태 전이를 유지한다.
- `ReservationExpirationService.expireByScheduleId()`가 현재 회차 기준 repository 조회만 사용한다.
- `ReservationService.createReservation()`이 전체 만료 처리를 더 이상 호출하지 않는다.
- `EventService.getSeats()`가 회차별 만료 처리 후 좌석을 반환한다.
