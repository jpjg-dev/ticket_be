# Backend Issue List

TicketLedger 백엔드에서 아직 추적해야 할 미해결 항목을 카테고리별로 정리한다.
완료된 항목은 이 문서에서 제거한다.

## 1. 인증/인가

- [ ] Refresh Token 재발급 동시 요청 방어 정책 결정 및 구현
  - 같은 Refresh Token으로 `/reissue` 요청이 동시에 들어왔을 때 하나의 요청만 성공하도록 보장할지 결정한다.
  - 후보: `RefreshToken` 조회 시 pessimistic lock 사용, 또는 `revoked_at is null` 조건부 update 사용.

## 2. 예약/좌석

- [ ] 수동 예약 만료 트리거 정책 명확화
  - 현재 수동 만료 정리는 `POST /api/v1/reservations` 예약 생성 요청 진입 시 `ReservationService.createReservation()`에서 `expireReservations()`를 먼저 호출하는 방식이다.
  - 이 흐름은 UI의 "예매하기" 버튼 자체가 아니라, 사용자가 특정 좌석을 선택해 `seatIds`로 예약 생성을 요청하는 시점에 기존 만료 group을 정리하는 의미다.
  - 정리 대상은 방금 선택한 좌석만이 아니라 `ReservationGroup.expiresAt <= now`인 전체 만료 group이며, 각 group의 pending reservation은 `EXPIRED`, HELD 좌석은 `AVAILABLE`로 복구된다.
  - 정책 결정 필요: 수동 정리를 예약 생성 요청에 계속 둘지, 좌석 조회/선택 단계 또는 별도 관리자/스케줄러 전용 흐름으로 분리할지 결정한다.

## 3. 결제/상태 전이

- [ ] Toss Payments 외부 호출 장애 대응 개선
  - 후보: timeout, retry, circuit breaker, 장애 로그 표준화.
  - 외부 호출 대상이 늘어나면 Resilience4j, OpenFeign, WebClient 전환 여부를 재검토한다.

## 4. 테스트/문서

- [ ] 마이페이지 조회 N+1 제거 및 `reservation_group_id` 인덱스 검증
  - 현재 `UserService.getUserInfo()`의 `paymentItems` 생성 과정에서 결제 건마다 `reservationsForPayment()`가 추가 조회를 발생시킬 수 있다.
  - 1차 개선 후보: payment 목록의 `reservationGroupId`를 모아 `reservation_group_id in (...)` 조회로 예약 목록을 한 번에 가져온 뒤 Java에서 group 기준으로 묶는다.
  - 단, IN 쿼리만으로는 충분하지 않을 수 있다. PostgreSQL에서 FK 컬럼은 자동 인덱싱되지 않으므로 `reservations.reservation_group_id` 인덱스가 없으면 IN 조회도 풀스캔 가능성이 있다.
  - 인덱스 후보: `reservations(reservation_group_id)`.
  - 트레이드오프: 마이페이지/결제취소/결제내역 같은 group 기준 read 성능은 개선되지만, 예약 생성 시 reservation row마다 인덱스 엔트리도 함께 갱신되어 write 비용이 증가한다.
  - 현재 예약 생성은 반복 `save()`가 아니라 `saveAll()`로 정리되어 있어, 인덱스 적용 시 발생할 수 있는 write 비용 증가를 일부 완화할 수 있는 구조다.
  - 검증 기준: 인덱스 적용 전후 `EXPLAIN ANALYZE`, 쿼리 호출 횟수, 마이페이지 조회 시간, 예약 생성 write 시간, p95 응답 시간을 함께 비교한다.
  - 판단 기준: read 성능 개선 폭이 write 성능 손실보다 도메인상 더 큰 가치가 있는지 수치로 확인한 뒤 적용 여부를 결정한다.
