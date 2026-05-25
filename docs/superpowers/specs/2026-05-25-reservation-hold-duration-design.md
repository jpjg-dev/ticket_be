# Reservation Hold Duration Centralization Design

## Goal

예약 생성 시 사용하는 좌석 선점 유지 시간을 환경별 설정으로 중앙화해,
개발 환경에서는 빠르게 만료를 확인하고 운영 환경에서는 정상적인 결제 시간을 보장한다.

## Decision

- 개발 환경의 좌석 선점 유지 시간은 `30s`로 유지한다.
- 운영 환경의 좌석 선점 유지 시간은 `5m`로 설정한다.
- `reservation.expire-scheduler.fixed-delay-ms`는 만료 데이터를 정리하는 실행 주기이며, 좌석 선점 유지 시간과 별도 설정으로 유지한다.
- 이번 변경에서는 `ReservationGroup.expiresAt`과 `Reservation.expiresAt`을 모두 유지한다.
- 하나의 예매 묶음에 포함된 group과 child reservation은 동일한 `expiresAt` 값을 사용한다.

## Configuration

```yaml
reservation:
  hold-duration: 30s # dev
  expire-scheduler:
    fixed-delay-ms: 15000
```

```yaml
reservation:
  hold-duration: 5m # prod
  expire-scheduler:
    fixed-delay-ms: 60000
```

`hold-duration`은 Spring의 `@Value`를 통해 `Duration`으로 직접 주입한다.

## Implementation Shape

- 현재 설정 소비자는 `ReservationService` 하나이므로 별도 설정 클래스를 추가하지 않는다.
- `ReservationService.createReservation()`에서 `@Value`로 주입된 `Duration`을 사용해 만료 시각을 한 번 계산한다.
- `ReservationGroup`과 각 `Reservation` 생성자는 이미 계산된 동일 `expiresAt`을 전달받는다.
- 엔티티 내부의 `now.plusSeconds(30)` 하드코딩은 제거한다.
- 현재 사용되지 않는 `RESERVATION_HOLD_MINUTES` 상수는 제거한다.

## Test Criteria

- 설정된 duration을 사용해 생성된 group의 `expiresAt`이 결정되는지 검증한다.
- 같은 생성 요청의 reservation과 group이 동일한 `expiresAt`을 갖는지 검증한다.
- 기존 만료 처리 및 좌석 선점 동작 테스트가 유지되는지 검증한다.

## Follow-Up

- 이후 `ReservationGroup.status`를 추가할 때 group을 예매 묶음 상태의 기준으로 정리한다.
- 그 작업에서 `Reservation.expiresAt`을 제거하고 group 단일 만료 기준으로 단순화할지 판단한다.
