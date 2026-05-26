# Reservation Group Status Design

## Decision

`ReservationGroup`에 예매 묶음 전체 상태를 추가한다. 마이페이지의 예매 내역 조회 조건과 표시 상태는 group 상태를 기준으로 하고, 하위 `Reservation` 상태는 좌석 단위 정합성 검증을 위해 유지한다.

```text
ReservationGroupStatus: PENDING, CONFIRMED, CANCELED, EXPIRED
```

## State Transitions

```text
예약 생성       -> Group PENDING / Reservation PENDING / Seat HELD
결제 승인       -> Group CONFIRMED / Reservation CONFIRMED / Seat BOOKED / Payment APPROVED
결제 취소       -> Group CANCELED / Reservation CANCELED / Seat AVAILABLE / Payment CANCELED
선점 만료       -> Group EXPIRED / Reservation EXPIRED / Seat AVAILABLE / READY Payment FAILED
```

결제 실패가 발생했더라도 선점 만료 전이면 사용자가 재결제를 시도할 수 있어야 하므로 `ReservationGroup`은 `PENDING`을 유지한다. 실패 시점에 이미 선점 시간이 만료된 경우에만 group을 `EXPIRED`로 전이한다.

## MyPage Query

마이페이지는 좌석 상세 정보가 필요하므로 `Reservation` row 조회는 유지한다. 단, 노출 대상 필터와 DTO의 예매 상태 값은 `ReservationGroup.status in (CONFIRMED, CANCELED)`를 기준으로 한다.

## Database Delivery

운영 기존 DB는 Flyway baseline 대상에 포함하지 않는다. 코드 구현 후 실제 배포 전에 신규 DB 기준 Flyway migration으로 `reservation_groups.status` 컬럼을 생성해야 하며, `ddl-auto: validate`인 운영 애플리케이션을 컬럼 생성 전에 배포하지 않는다.
