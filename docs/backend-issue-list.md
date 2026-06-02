# Backend Issue List

TicketLedger 백엔드에서 아직 추적해야 할 미해결 항목을 카테고리별로 정리한다.
완료된 항목은 이 문서에서 제거한다.

## 1. 예약/좌석/시간 정책

- [ ] 시간 저장 및 표시 정책 전환
  - 현재 서비스 대상은 국내 공연/국내 사용자로 한정하고, 화면 표시는 `Asia/Seoul` 기준으로 제공한다.
  - 예약 생성, 결제 승인/취소, 예약 만료처럼 실제 발생한 시점 데이터는 DB에 UTC 기준으로 저장하도록 `LocalDateTime` 사용 범위를 검토하고 `Instant` 중심 전환을 진행한다.
  - API 응답은 UTC 기준 ISO 시각을 전달하고 프론트가 `Asia/Seoul`로 표시하는 방향을 기준으로 한다.
  - 향후 해외 공연 또는 해외 사용자 지원 시 공연장 timezone과 사용자 timezone 저장 정책을 별도로 추가 검토한다.

## 2. 결제/상태 전이

- [ ] Toss Payments 외부 호출 장애 대응 개선
  - 후보: timeout, retry, circuit breaker, 장애 로그 표준화.
  - 외부 호출 대상이 늘어나면 Resilience4j, OpenFeign, WebClient 전환 여부를 재검토한다.

## 3. 테스트/문서

- [ ] 좌석 조회 연계 만료 처리의 동시성 및 성능 검증
  - 현재 `EventService.getSeats(scheduleId)`는 좌석 반환 전에 해당 회차의 만료 group을 즉시 정리한다.
  - 동일 회차 좌석 조회 요청이 동시에 들어오면 같은 만료 group을 함께 처리하려 할 수 있으므로, 중복 상태 전이 충돌 여부를 검증한다.
  - 만료 group이 없는 좌석 조회와 만료 group이 누적된 좌석 조회를 분리해 응답 시간과 쿼리 수를 측정한다.
  - 검증 결과에 따라 만료 대상 조회 시 비관적 락 또는 조건부 상태 전이 적용 여부를 결정한다.
  - k6 baseline 시나리오에는 `만료 대상 없음`과 `만료 대상 존재` 좌석 조회를 구분해 포함한다.

- [ ] 마이페이지 조회 N+1 제거 및 `reservation_group_id` 인덱스 검증
  - 1차 개선 완료: `reservationsForPayment()` 반복 조회를 제거하고, 이미 조회한 reservation 목록을 `reservationGroupId` 기준 `Map`으로 묶어 payment DTO 변환에서 재사용한다.
  - 1차 개선 완료: reservation/payment 조회에 필요한 연관 데이터를 `fetch join`으로 가져와 DTO 변환 중 LAZY N+1 발생 가능성을 줄였다.
  - 측정 결과: `groups-100`, `10 VU / 30s` 기준 p95 `202.75 ms -> 17.52 ms`, 처리량 `60.73 req/s -> 663.46 req/s`.
  - 현재 판단: `reservations.reservation_group_id` 인덱스는 바로 적용하지 않고 후보로 보류한다.
  - 이유: 마이페이지는 핵심 경합 구간이 아니며, 사용자 이력 증가 문제는 먼저 페이징, 기간 필터, 아카이빙으로 해결하는 것이 도메인상 더 자연스럽다.
  - 단, IN 쿼리만으로는 충분하지 않을 수 있다. PostgreSQL에서 FK 컬럼은 자동 인덱싱되지 않으므로 `reservations.reservation_group_id` 인덱스가 없으면 IN 조회도 풀스캔 가능성이 있다.
  - 인덱스 후보: `reservations(reservation_group_id)`.
  - 트레이드오프: 마이페이지/결제취소/결제내역 같은 group 기준 read 성능은 개선되지만, 예약 생성 시 reservation row마다 인덱스 엔트리도 함께 갱신되어 write 비용이 증가한다.
  - 현재 예약 생성은 반복 `save()`가 아니라 `saveAll()`로 정리되어 있어, 인덱스 적용 시 발생할 수 있는 write 비용 증가를 일부 완화할 수 있는 구조다.
  - 재검토 기준: 데이터 증가 후 group 기준 조회가 병목으로 확인되면 인덱스 적용 전후 `EXPLAIN ANALYZE`, 쿼리 호출 횟수, 마이페이지 조회 시간, 예약 생성 write 시간, p95 응답 시간을 함께 비교한다.
