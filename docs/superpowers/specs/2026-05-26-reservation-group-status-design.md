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

운영 기존 데이터는 보존하지 않고, 신규 스키마 기준으로 재구성한다. Flyway를 스키마 변경 이력의 기준으로 사용하며 `V1__init_schema.sql`은 현재 전체 테이블 구조와 `reservation_groups.status` 컬럼을 포함한다.

운영 적용 결과:

- `flyway-core`와 PostgreSQL Flyway 모듈을 백엔드에 추가했다.
- 신규 운영 스키마에 `V1__init_schema.sql`을 적용했다.
- 운영 profile은 `ddl-auto: validate`를 유지해 Flyway 적용 후 엔티티와 스키마 일치 여부를 검증한다.
- 운영 DB에서 `reservation_groups.status` 반영과 애플리케이션 기동을 확인했다.

현재 `DataInitializer`는 profile 제한 없이 애플리케이션 시작 시 동작한다. 운영용 초기 공연/회차/좌석 데이터를 이 방식으로 계속 관리할지, 별도 seed migration 또는 운영 전용 초기화 방식으로 분리할지는 후속 판단 대상으로 둔다.
