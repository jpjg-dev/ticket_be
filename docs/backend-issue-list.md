# Backend Issue List

TicketLedger 백엔드에서 아직 추적해야 할 미해결 항목을 카테고리별로 정리한다.
완료된 항목은 이 문서에서 제거한다.

## 1. 인증/인가

- [ ] Refresh Token 재발급 동시 요청 방어 정책 결정 및 구현
  - 같은 Refresh Token으로 `/reissue` 요청이 동시에 들어왔을 때 하나의 요청만 성공하도록 보장할지 결정한다.
  - 후보: `RefreshToken` 조회 시 pessimistic lock 사용, 또는 `revoked_at is null` 조건부 update 사용.

## 2. 예약/좌석/시간 정책

- [ ] 시간 저장 및 표시 정책 전환
  - 현재 서비스 대상은 국내 공연/국내 사용자로 한정하고, 화면 표시는 `Asia/Seoul` 기준으로 제공한다.
  - 예약 생성, 결제 승인/취소, 예약 만료처럼 실제 발생한 시점 데이터는 DB에 UTC 기준으로 저장하도록 `LocalDateTime` 사용 범위를 검토하고 `Instant` 중심 전환을 진행한다.
  - API 응답은 UTC 기준 ISO 시각을 전달하고 프론트가 `Asia/Seoul`로 표시하는 방향을 기준으로 한다.
  - 향후 해외 공연 또는 해외 사용자 지원 시 공연장 timezone과 사용자 timezone 저장 정책을 별도로 추가 검토한다.

- [ ] 신규 DB 기준 Flyway 도입 및 `reservation_groups.status` 컬럼 반영
  - 코드에는 `ReservationGroupStatus(PENDING, CONFIRMED, CANCELED, EXPIRED)`가 추가되고, 마이페이지 기준 상태와 결제/취소/만료 전이에 반영되어 있다.
  - 기존 운영 DB는 Flyway baseline 편입 대상으로 삼지 않고, 배포 시 신규 DB를 Flyway migration으로 구성하는 정책을 사용한다.
  - 운영 profile은 `ddl-auto: validate`이므로 `reservation_groups.status` migration 적용 전에 현재 백엔드 이미지를 배포하면 애플리케이션 시작이 실패한다.
  - Flyway 도입 시 신규 DB 전체 스키마 생성 migration과 `status` 컬럼을 포함한 초기 기준을 작성하고, 빈 PostgreSQL에서 애플리케이션 기동 검증을 수행한다.
  - `Reservation.expiresAt` 유지 여부는 만료 시간 기준 통일 리팩토링 항목에서 별도로 판단한다.

## 3. 결제/상태 전이

- [ ] Toss Payments 외부 호출 장애 대응 개선
  - 후보: timeout, retry, circuit breaker, 장애 로그 표준화.
  - 외부 호출 대상이 늘어나면 Resilience4j, OpenFeign, WebClient 전환 여부를 재검토한다.

## 4. 테스트/문서

- [ ] 좌석 조회 연계 만료 처리의 동시성 및 성능 검증
  - 현재 `EventService.getSeats(scheduleId)`는 좌석 반환 전에 해당 회차의 만료 group을 즉시 정리한다.
  - 동일 회차 좌석 조회 요청이 동시에 들어오면 같은 만료 group을 함께 처리하려 할 수 있으므로, 중복 상태 전이 충돌 여부를 검증한다.
  - 만료 group이 없는 좌석 조회와 만료 group이 누적된 좌석 조회를 분리해 응답 시간과 쿼리 수를 측정한다.
  - 검증 결과에 따라 만료 대상 조회 시 비관적 락 또는 조건부 상태 전이 적용 여부를 결정한다.
  - k6 baseline 시나리오에는 `만료 대상 없음`과 `만료 대상 존재` 좌석 조회를 구분해 포함한다.

- [ ] 마이페이지 조회 N+1 제거 및 `reservation_group_id` 인덱스 검증
  - 현재 `UserService.getUserInfo()`의 `paymentItems` 생성 과정에서 결제 건마다 `reservationsForPayment()`가 추가 조회를 발생시킬 수 있다.
  - 1차 개선 후보: payment 목록의 `reservationGroupId`를 모아 `reservation_group_id in (...)` 조회로 예약 목록을 한 번에 가져온 뒤 Java에서 group 기준으로 묶는다.
  - 단, IN 쿼리만으로는 충분하지 않을 수 있다. PostgreSQL에서 FK 컬럼은 자동 인덱싱되지 않으므로 `reservations.reservation_group_id` 인덱스가 없으면 IN 조회도 풀스캔 가능성이 있다.
  - 인덱스 후보: `reservations(reservation_group_id)`.
  - 트레이드오프: 마이페이지/결제취소/결제내역 같은 group 기준 read 성능은 개선되지만, 예약 생성 시 reservation row마다 인덱스 엔트리도 함께 갱신되어 write 비용이 증가한다.
  - 현재 예약 생성은 반복 `save()`가 아니라 `saveAll()`로 정리되어 있어, 인덱스 적용 시 발생할 수 있는 write 비용 증가를 일부 완화할 수 있는 구조다.
  - 검증 기준: 인덱스 적용 전후 `EXPLAIN ANALYZE`, 쿼리 호출 횟수, 마이페이지 조회 시간, 예약 생성 write 시간, p95 응답 시간을 함께 비교한다.
  - 판단 기준: read 성능 개선 폭이 write 성능 손실보다 도메인상 더 큰 가치가 있는지 수치로 확인한 뒤 적용 여부를 결정한다.
