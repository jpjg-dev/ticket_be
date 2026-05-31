# Performance Test Strategy

## Purpose

현재 단계의 성능 테스트는 최대 처리량을 과장하기 위한 테스트가 아니라, 조회 최적화 전후의 차이를 같은 조건으로 비교하기 위한 baseline 측정이다.

예매/결제 시스템에서 포트폴리오로 증명할 핵심은 다음 세 가지다.

1. 오픈 직후 좌석 조회와 선점 요청이 몰려도 응답과 상태 정합성이 유지되는가.
2. 사용자 이력이 늘었을 때 마이페이지 조회 N+1을 발견하고 개선 수치로 증명할 수 있는가.
3. 결제 승인/취소의 중복 요청에서 상태 전이가 한 번만 유효하게 반영되는가.

현재 진행 중인 1차 대상은 `GET /api/v1/event/schedules/{scheduleId}/seats`이다. 이 API는 좌석을 반환하기 전에 해당 회차의 만료된 예약 group을 정리하므로, 일반 조회와 만료 처리 포함 조회를 분리해 측정한다.

## Scope And Order

| Phase | Target | Test type | Evidence to retain |
| --- | --- | --- | --- |
| 1 | 좌석 조회, 만료 정리 포함 좌석 조회 | k6 baseline + 일회성/동시성 확인 | 일반 조회 p95, 만료 첫 호출 비용, 동시 조회 상태 일관성 |
| 2 | 마이페이지 조회 | k6 전후 비교 + SQL 로그/실행 계획 | 이력 건수별 쿼리 수, N+1 제거 전후 p95 |
| 3 | 예약 group 좌석 선점 | 동시성 검증 테스트 | 겹치는 seat 요청에서 성공 group 수, 부분 성공 없음 |
| 4 | 결제 승인/취소 | 중복 요청 및 상태 전이 검증 | 중복 승인/취소 방어, group/reservation/seat 일관성 |
| 5 | Refresh Token 재발급 | 정책 결정 후 동시성 검증 | 동일 refresh token 동시 요청 처리 정책 |

모든 API를 무작정 부하시험 대상으로 삼지 않는다. 사용자 트래픽이 집중되는 조회 구간, 현재 코드에서 N+1이 확인된 조회 구간, 금액 또는 좌석 정합성을 깨뜨릴 수 있는 상태 전이 구간만 선택한다.

## Environment

- 1차 스크립트 검증: 로컬 `dev` profile, `https://localhost:8080`
- 로컬 TLS 인증서는 k6 실행 시 검증을 생략한다.
- 로컬 측정 결과와 GCP 운영 측정 결과는 서버 자원이 다르므로 서로 직접 비교하지 않는다.

## Seat Lookup Test Split

| Case | Database preparation | Execution model | Purpose |
| --- | --- | --- | --- |
| `no-expired` | 대상 회차에 만료된 `PENDING` reservation group 없음 | 10/30/50 VU 지속 호출 | 일반 좌석 조회 pressure baseline |
| `expiration-first-hit` | 만료된 `PENDING` group 및 `HELD` 좌석 존재 | 1 VU, 1회 호출 | 실제 만료 복구가 실행되는 한 번의 비용과 복구 결과 확인 |
| `expiration-concurrency` | 만료된 `PENDING` group 및 `HELD` 좌석 존재 | 여러 VU가 각 1회 동시 호출 | 같은 만료 대상의 중복 처리 충돌 및 응답 상태 확인 |

만료 대상은 첫 요청이 처리하면 상태가 변경되어 이후 요청에서는 더 이상 만료 처리 비용이 발생하지 않는다. 따라서 만료 복구를 `no-expired`와 동일한 지속 호출 baseline으로 비교하지 않고, 첫 호출 비용과 동시 요청 상태 일관성으로 나누어 확인한다.

`no-expired` 결과 기록 전에는 대상 회차에 만료된 `PENDING` group이 없음을 확인한다. 만료 관련 두 테스트는 각각 별도의 만료 데이터를 준비한 뒤 실행한다.

## Load Stages

| Stage | VUs | Duration | Purpose |
| --- | ---: | ---: | --- |
| Smoke | 1 | 10s | URL, 인증서, 응답 코드, 스크립트 검증 |
| Baseline low | 10 | 30s | 낮은 동시 조회 기준선 |
| Baseline normal | 30 | 30s | 정상 부하 가설 구간 |
| Observation | 50 | 30s | p95 상승 및 오류 발생 여부 관찰 |

현재 스크립트는 VU가 대기 없이 연속으로 요청하는 endpoint pressure baseline이다. 실제 사용자가 초당 몇 번 조회하는지를 재현하는 테스트가 아니라, 같은 실행 조건에서 코드 또는 쿼리 변경 전후의 차이를 비교하는 용도로 사용한다. 사용자 행동 기반 시나리오가 필요해지면 `sleep()` 또는 arrival-rate 기준 시나리오를 별도로 추가한다.

## Measurements

### k6

- `seat_lookup_duration`: 좌석 조회만 분리한 p50, p95, p99
- `seat_lookup_failed`: 좌석 조회만 분리한 실패율
- `http_req_duration`, `http_req_failed`: 전체 HTTP 참고값
- `http_reqs`: 처리량
- `checks`: HTTP 200 검증 성공률

## Initial Acceptance Hypothesis

baseline 단계에서는 HTTP 실패가 없는 유효한 시험인지 먼저 검증하고, 응답 시간 목표는 관찰 결과를 바탕으로 확정한다.

| Case | Initial validity requirement |
| --- | --- |
| `no-expired` | 실패율 `< 1%`, checks `> 99%` |
| `expiration-first-hit` | 응답 `200`, 대상 좌석이 `AVAILABLE`로 반환됨 |
| `expiration-concurrency` | 전 요청 응답 `200`, 대상 좌석이 모두 `AVAILABLE`로 반환됨, 상태 전이 예외 없음 |

응답 시간 커트라인은 `10`, `30`, `50` VU의 최초 결과를 확인한 뒤 결정한다.

## Result Record

측정값은 같은 API라도 DB 조건, VU, 시간, 실행 환경이 다르면 비교하지 않는다.

| Date | API / Case | Data condition | VUs / Duration | Requests | Failure rate | p95 | Note |
| --- | --- | --- | --- | ---: | ---: | ---: | --- |
| 2026-05-27 | seat-list / no-expired | `scheduleId=1`, reservation row `0`, expired group `0` | `10 / 30s` | 76,888 | 0.00% | 4.83 ms | local dev, pressure baseline, 2,561.92 req/s |
| 2026-05-27 | seat-list / no-expired | `scheduleId=1`, reservation row `0`, expired group `0` | `30 / 30s` | 101,717 | 0.00% | 14.37 ms | local dev, pressure baseline, 3,389.87 req/s |
| 2026-05-27 | seat-list / no-expired | `scheduleId=1`, reservation row `0`, expired group `0` | `50 / 30s` | 103,566 | 0.00% | 25.24 ms | local dev, pressure baseline, 3,450.42 req/s |
| 2026-05-27 | seat-list / expiration-first-hit | `groupId=1`, `scheduleId=7`, expired `PENDING` group, `HELD` seats `50,51` | `1 / 1 iteration` | 1 | 0.00% | 18.57 ms | `group/reservation=EXPIRED`, `seat=AVAILABLE`, `payment=FAILED` 확인 |
| 2026-05-27 | seat-list / expiration-concurrency | `groupId=2`, `scheduleId=9`, expired `PENDING` group, `HELD` seats `66,67` | `20 / 1 iteration each` | 20 | 40.00% | 56.42 ms | 실패: `IllegalStateException: 예약된 좌석만 해제할 수 있습니다.`; 최종 DB 상태는 일관됨 |
| 2026-05-27 | seat-list / expiration-concurrency after lock fix | `groupId=3`, `scheduleId=9`, expired `PENDING` group, `HELD` seats `66,67` | `20 / 1 iteration each` | 20 | 0.00% | 74.81 ms | `Payment` 비관적 락 및 락 이후 상태 재확인 적용; `group/reservation=EXPIRED`, `seat=AVAILABLE`, `payment=FAILED` 확인 |
| 2026-05-28 | mypage / groups-1 smoke | `userId=1`, group `1`, payment `1`, reservation `2` | `1 / 10s` | 1,426 | 0.00% | 9.63 ms | 인증 Cookie 기반 smoke; reservations/payments 모두 응답 |
| 2026-05-28 | mypage / groups-1 baseline | `userId=1`, group `1`, payment `1`, reservation `2` | `10 / 30s` | 41,779 | 0.00% | 8.25 ms | N+1 비교 전 최소 데이터셋 기준값; SQL 쿼리 수는 별도 로그 카운트 필요 |
| 2026-05-28 | mypage / groups-20 baseline | `userId=1`, group `20`, payment `20`, reservation `40` | `10 / 30s` | 7,815 | 0.00% | 47.58 ms | 데이터 증가에 따라 p95 상승 및 처리량 감소 확인 |
| 2026-05-28 | mypage / groups-100 baseline | `userId=1`, group `100`, payment `100`, reservation `200` | `10 / 30s` | 1,829 | 0.00% | 202.75 ms | N+1/반복 조회 개선 전 기준값; 처리량 `60.73 req/s` |
| 2026-05-28 | mypage / groups-100 after query optimization | `userId=1`, group `100`, payment `100`, reservation `200` | `10 / 30s` | 19,911 | 0.00% | 17.52 ms | reservation Map 재사용, reservation/payment fetch join 적용 후 재측정 |
| 2026-05-30 | reservation-create / unique seats after warm-up | `userId=1`, unique seat ids `331~350`, 2 seats/request | `10 / 10 shared iterations` | 10 | 0.00% | 21.42 ms | local dev, warm-up 이후 write baseline, 233.02 req/s, DB 사후 검증: group 10 / reservation 20 / HELD seat 20 |
| 2026-05-30 | reservation-create / unique seats after warm-up | `userId=1`, unique seat ids `351~390`, 2 seats/request | `20 / 20 shared iterations` | 20 | 0.00% | 18.25 ms | local dev, warm-up 이후 write expansion, 326.67 req/s, DB 사후 검증: group 20 / reservation 40 / HELD seat 40 |
| 2026-05-30 | reservation-create / load seats | `userId=1`, `scheduleId=18`, unique seat ids `391~590`, 2 seats/request | `10 / 100 shared iterations` | 100 | 0.00% | 16.27 ms | local dev, load dataset, 766.67 req/s, DB 사후 검증: group 100 / reservation 200 / HELD seat 200 |
| 2026-05-30 | reservation-create / load seats | `userId=1`, `scheduleId=18`, unique seat ids `391~790`, 2 seats/request | `20 / 200 shared iterations` | 200 | 0.00% | 23.74 ms | local dev, load dataset, 916.82 req/s, DB 사후 검증: group 200 / reservation 400 / HELD seat 400 |
| 2026-05-30 | reservation-create / load seats | `userId=1`, `scheduleId=18`, unique seat ids `391~1390`, 2 seats/request | `50 / 500 shared iterations` | 500 | 0.00% | 98.07 ms | local dev, load dataset, 740.74 req/s, DB 사후 검증: group 500 / reservation 1000 / HELD seat 1000 |
| 2026-05-30 | reservation-create / same-seat contention | `userId=1`, `scheduleId=18`, same seat ids `391,392`, 2 seats/request | `10 / 10 shared iterations` | 10 | expected reject 9 | 38.84 ms | local dev, success 1 / rejected 9 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 |
| 2026-05-30 | reservation-create / same-seat contention | `userId=1`, `scheduleId=18`, same seat ids `391,392`, 2 seats/request | `20 / 20 shared iterations` | 20 | expected reject 19 | 17.68 ms | local dev, success 1 / rejected 19 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 |
| 2026-05-30 | reservation-create / same-seat contention | `userId=1`, `scheduleId=18`, same seat ids `391,392`, 2 seats/request | `50 / 50 shared iterations` | 50 | expected reject 49 | 26.45 ms | local dev, success 1 / rejected 49 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 |
| 2026-05-30 | reservation-create / overlapping-seat contention | `userId=1`, `scheduleId=18`, alternating seat pairs `391,392` and `392,393` | `10 / 10 shared iterations` | 10 | expected reject 9 | 17.98 ms | local dev, success 1 / rejected 9 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seats `392,393` |
| 2026-05-30 | reservation-create / overlapping-seat contention | `userId=1`, `scheduleId=18`, alternating seat pairs `391,392` and `392,393` | `20 / 20 shared iterations` | 20 | expected reject 19 | 24.40 ms | local dev, success 1 / rejected 19 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seats `391,392` |
| 2026-05-30 | reservation-create / overlapping-seat contention | `userId=1`, `scheduleId=18`, alternating seat pairs `391,392` and `392,393` | `50 / 50 shared iterations` | 50 | expected reject 49 | 35.11 ms | local dev, success 1 / rejected 49 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seats `391,392` |

### Initial Observation

- `10 -> 30 VU`에서는 처리량이 약 `32.3%` 증가했지만 p95도 `4.83 ms -> 14.37 ms`로 상승했다.
- `30 -> 50 VU`에서는 처리량 증가가 약 `1.8%`에 그친 반면 p95는 `14.37 ms -> 25.24 ms`로 상승했다.
- 실패는 발생하지 않았지만, 현재 로컬 dev pressure 조건에서 `30~50 VU` 구간은 처리량 증가보다 지연 증가가 커지는 포화 접근 구간으로 본다.
- 상세 SQL/bind 로그가 활성화된 `dev` profile 결과이므로 절대 처리 능력으로 주장하지 않고, 이후 동일 환경에서의 변경 전후 비교 baseline으로 사용한다.
- `expiration-first-hit`는 첫 조회 한 번에서 만료 group과 좌석 복구가 수행됐고, HTTP 체크 및 사후 DB 상태 확인까지 통과했다. 일반 조회 `10 VU` p95와 직접 비교할 수는 없지만, 만료 복구 경로의 기준값은 `18.57 ms`로 기록한다.
- `expiration-concurrency`는 최종 DB 상태가 `group/reservation=EXPIRED`, `seat=AVAILABLE`, `payment=FAILED`로 정리됐으나, 동일 만료 대상을 동시에 처리한 `20`개 조회 중 `8`개가 `Seat.release()` 상태 검증 예외로 실패했다. 만료 처리 대상 선정과 상태 전이를 직렬화하거나 조건부 처리하는 보강이 필요하다.
- `Payment` row의 `PESSIMISTIC_WRITE` 락과 락 이후 group 상태 재확인을 적용한 재실행에서는 동일 조건의 `20`개 조회가 모두 성공했고 최종 상태도 일관됐다. p95는 `56.42 ms -> 74.81 ms`로 상승했으므로, 오류 제거의 대가로 경합 요청의 대기 시간이 증가하는 트레이드오프를 확인했다.
- 마이페이지 조회는 동일 `10 VU / 30s` 조건에서 group `1 -> 20 -> 100` 증가 시 p95가 `8.25 ms -> 47.58 ms -> 202.75 ms`로 상승했고, 처리량은 `1,392 req/s -> 260 req/s -> 60.73 req/s`로 감소했다.
- 이는 현재 `UserService.getUserInfo()`의 DTO 변환 과정에서 LAZY 연관 조회와 `reservationsForPayment(payment)` 반복 조회가 데이터 건수 증가에 따라 비용을 키우는 구조임을 보여주는 baseline이다.
- 1차 개선으로 이미 조회한 reservation 목록을 groupId 기준 `Map`으로 재사용하고, reservation/payment 조회에 fetch join을 적용했다. 동일 `groups-100`, `10 VU / 30s` 조건에서 p95는 `202.75 ms -> 17.52 ms`로 감소했고 처리량은 `60.73 req/s -> 663.46 req/s`로 증가했다.

## Phase 2: MyPage N+1 Baseline

마이페이지는 이미 코드와 SQL 로그에서 N+1 후보가 확인되었으므로, 좌석 조회 baseline 이후 바로 진행한다.

### Problem To Measure

- `Reservation -> Seat -> Schedule -> Event` DTO 변환 과정에서 LAZY 연관 조회가 reservation 수에 따라 반복될 수 있다.
- `Payment`를 순회하면서 `findByReservationGroupId()`를 다시 호출해 payment 건수만큼 예약 조회가 추가될 수 있다.

### Dataset And Comparison

| Paid reservation groups for one user | Purpose |
| ---: | --- |
| 2 | 현재 로그 재현 |
| 20 | 쿼리 수 증가 형태 확인 |
| 100 | 개선 전후 p95 차이 확인 |

기록 항목:

- SQL 쿼리 수
- `GET /api/v1/users/{userId}`의 p95와 실패율
- 개선 전후 동일 데이터셋 결과
- 필요 시 `EXPLAIN ANALYZE` 기반 `reservations(reservation_group_id)` 인덱스 적용 전후 결과

측정 스크립트:

- `performance/k6/mypage-baseline.js`

개발 환경 초기 데이터:

- `dev` profile의 `DataInitializer`는 `test1@email.com` 사용자에게 마이페이지 성능 비교용 확정 예매 group `100`건, 결제 `100`건, 예약 row `200`건이 되도록 초기 데이터를 보강한다.
- 운영 기본 데이터와 성능 측정용 데이터가 섞이는 것을 막기 위해 이 성능 seed는 `dev` profile에서만 생성한다.
- DB를 초기화한 뒤 `dev` profile로 애플리케이션을 실행하면 동일 기준의 성능 비교 데이터가 자동으로 생성된다.

초기 실행은 최대 처리량 측정이 아니라 데이터 건수별 증가 패턴 확인이 목적이다. 따라서 먼저 `1 VU / 10s` smoke로 인증 쿠키와 응답 구조를 확인하고, 이후 동일 데이터셋에서 `10 VU / 30s` 기준값을 기록한다.

### Improvement Candidates To Compare

1. 이미 조회한 reservation 목록을 `reservationGroupId` 기준 `Map`으로 묶고 payment DTO 변환에서도 재사용한다.
2. 마이페이지 전용 조회에서 필요한 연관 데이터는 `fetch join` 또는 DTO projection으로 한 번에 조회한다.
3. bulk 조회 적용 후에도 스캔 비용이 크면 `reservations(reservation_group_id)` 인덱스를 측정 기반으로 결정한다.

### Applied Query Optimization

개선 전 구조:

- `Payment` 목록을 순회하면서 결제 건마다 `findByReservationGroupId(payment.reservationGroup.id)`를 다시 호출했다.
- `Reservation` DTO 변환 중 `Seat`, `Schedule`, `Event`, `ReservationGroup` LAZY 조회가 예약 row 수만큼 추가될 수 있었다.
- 따라서 사용자 이력이 많아질수록 HTTP 요청 1회 안에서 DB round-trip과 LAZY 조회 비용이 함께 증가했다.

개선 후 구조:

- 사용자 기준 reservation 목록은 한 번 조회하고, Java에서 `reservationGroupId -> List<Reservation>` 형태의 `Map`으로 묶는다.
- payment DTO를 만들 때 DB를 다시 조회하지 않고 이미 만든 `Map`에서 같은 group의 reservation을 꺼내 재사용한다.
- reservation 조회에는 `reservationGroup`, `seat`, `schedule`, `event`를 `fetch join`으로 함께 가져오고, payment 조회에는 `reservationGroup`을 `fetch join`으로 가져온다.

이 방식은 `IN` 쿼리만 추가하는 방식보다 현재 코드에 더 잘 맞는다. 이미 필요한 reservation 목록을 한 번 조회하고 있으므로, 같은 데이터를 payment 변환에서 재사용하면 추가 repository 호출 자체를 제거할 수 있다. 즉, DB 조회를 한 번 더 잘하는 문제가 아니라 불필요한 조회를 없애는 방향이다.

측정 결과:

- `groups-100`, `10 VU / 30s` 기준 p95: `202.75 ms -> 17.52 ms`
- 처리량: `60.73 req/s -> 663.46 req/s`
- 실패율: `0.00%` 유지

### Index Review: `reservations(reservation_group_id)`

PostgreSQL은 외래키 컬럼에 인덱스를 자동 생성하지 않는다. 따라서 `reservations.reservation_group_id`로 조회하거나 join하는 쿼리가 많다면 별도 인덱스를 검토해야 한다.

적용 시 기대 효과:

- `findByReservationGroupId(...)`
- `where reservation_group_id in (...)`
- `reservation_groups -> reservations` 방향의 group 기준 join
- 마이페이지, 결제 취소, 결제 상세처럼 group 단위로 예약 row를 다시 묶어 보는 read 경로

비용:

- reservation 생성 시 row insert뿐 아니라 인덱스 엔트리도 함께 갱신된다.
- 좌석을 여러 장 예매할수록 reservation row가 늘어나므로 write 비용도 같이 증가한다.
- 인덱스 저장 공간이 추가로 필요하다.

현재 판단:

- 마이페이지 1차 개선은 `Map` 재사용과 `fetch join`만으로도 p95가 크게 개선됐다.
- 따라서 현재 단계에서는 인덱스를 바로 적용하지 않고 후보로 보류한다.
- 이유는 마이페이지 조회가 좌석 선점/결제 승인처럼 핵심 경합 구간이 아니고, 사용자별 이력이 계속 커지는 문제는 먼저 페이징, 기간 필터, 아카이빙으로 풀어야 하는 성격이 강하기 때문이다.
- 만약 DB가 reservation에서 시작해 group으로 join하는 계획을 선택하고 스캔 비용이 낮다면 인덱스 효과가 작을 수 있다.
- 반대로 group id 목록을 기준으로 reservation을 자주 찾는 실행 계획이 확인되면 `reservations(reservation_group_id)` 인덱스는 read 성능 개선 가치가 높다.
- 추후 데이터 증가 후에도 group 기준 조회가 병목으로 확인되면 `EXPLAIN ANALYZE`와 k6 전후 비교를 통해 적용 여부를 다시 판단한다.

후보 DDL:

```sql
CREATE INDEX idx_reservations_reservation_group_id
    ON reservations (reservation_group_id);
```

## Phase 3 And 4: Consistency Evidence

예약 선점과 결제 상태 전이는 단순 응답 시간보다 정합성이 우선이다.

| Flow | Scenario | Pass condition |
| --- | --- | --- |
| 예약 선점 | 여러 사용자가 겹치는 좌석 묶음을 동시에 예약 | 성공 group 하나만 존재하고 부분 성공이 없음 |
| 결제 승인 | 같은 승인 요청이 중복 도착 | 결제 및 예약 확정 상태가 중복 전이되지 않음 |
| 결제 취소 | 같은 취소 요청이 중복 도착 | 취소 결과가 정책대로 멱등 처리되고 좌석이 한 번만 복구됨 |

결제 외부 PG 자체의 처리량을 측정하려고 대량 승인 요청을 보내지 않는다. 이 프로젝트에서는 내부 상태 전이와 중복 요청 방어를 검증 대상으로 삼는다.

### Reservation Create Consistency Result

2026-05-28 기준 `ReservationConcurrencyTest`로 예약 생성 정합성을 먼저 검증했다.

- 시나리오: 10명의 사용자가 `[A-1, A-2]`, `[A-2, A-3]`처럼 겹치는 2좌석 묶음을 동시에 예약 요청
- 기대값: 하나의 group만 성공하고 나머지는 실패
- 결과: 성공 1건, 실패 9건, reservation 2건, group 1건, HELD 좌석 2건

이 테스트는 처리량 측정보다 정합성 검증이 목적이다. 다음 단계의 k6 예약 생성 write baseline은 이 정합성이 유지되는 상태에서 p95, 실패율, 락 대기 영향을 관찰하는 보조 지표로 사용한다.

### Reservation Create Write Baseline

예약 생성 write baseline은 같은 좌석을 반복 호출하지 않는다. 예약 생성은 상태를 변경하는 API이므로, 동일 좌석을 여러 번 사용하면 첫 요청 이후부터는 정상적인 성능 측정이 아니라 이미 선점된 좌석에 대한 실패 측정이 된다.

측정 기준:

- `shared-iterations` 방식으로 총 요청 수를 고정한다.
- `SEAT_IDS`에는 `iterations * seatsPerRequest` 이상의 사용 가능한 좌석 id를 전달한다.
- 각 iteration은 고유한 좌석 묶음을 사용한다.
- 결과는 성공 write 기준 p95, 실패율, 처리량을 기록한다.

측정 스크립트:

- `performance/k6/reservation-create-baseline.js`

이 테스트는 중복 좌석 정합성 검증을 대체하지 않는다. 겹치는 좌석에 대한 정합성은 `ReservationConcurrencyTest`가 담당하고, k6 write baseline은 정상 예약 생성 경로의 처리 비용을 관찰한다.

현재 예약 생성 write 측정값은 baseline 성격이다. `10~20`건 수준의 짧은 `shared-iterations` 결과는 warm-up, JVM/DB 캐시, 스레드 스케줄링 영향으로 p95와 처리량이 쉽게 흔들릴 수 있으므로 절대 성능이나 VU별 우열로 주장하지 않는다. 추후 부하 테스트 단계에서는 충분한 좌석과 요청 데이터를 준비하고, 각 VU 구간을 최소 `100~500 iterations` 이상으로 반복 측정한 뒤 중간값 또는 반복 평균을 비교한다.

개발 환경 예약 생성 부하 데이터:

- `PERF_LOAD_TEST_EVENT` 이벤트와 `scheduleId=18` 회차를 예약 생성 부하 테스트 전용 데이터로 사용한다.
- `LOAD-0001 ~ LOAD-1000` 좌석 `1,000`개를 사용하며, 현재 좌석 id 범위는 `391~1390`이다.
- 요청당 2좌석 기준 최대 `500`건의 정상 예약 생성 write 테스트가 가능하다.
- 재측정 전에는 `performance/reset-load-test-seats.ps1`로 해당 부하 테스트 전용 좌석, 예약, 결제, group을 초기화한다.

### Reservation Create Contention Baseline

같은 좌석 묶음에 여러 요청을 동시에 보내는 경합 테스트는 HTTP 실패율이 높게 나오는 것이 정상이다. 이 테스트의 성공 기준은 모든 요청이 `200`을 받는 것이 아니라, 정확히 하나의 요청만 예약 group을 생성하고 나머지 요청은 이미 선점된 좌석으로 거부되는 것이다.

경합 시나리오는 두 가지로 나눈다.

| Scenario | Request shape | Purpose |
| --- | --- | --- |
| Same-seat contention | 모든 요청이 같은 `seatIds`를 사용한다. 예: `[391,392]` vs `[391,392]` | 완전히 동일한 좌석 묶음 중복 선점 방지 |
| Overlapping-seat contention | 요청이 서로 다르지만 일부 좌석이 겹친다. 예: `[391,392]` vs `[392,393]` | 하나라도 겹치면 실패 요청 전체가 롤백되는지 확인 |

측정 기준:

- 모든 iteration이 같은 `seatIds`를 사용한다.
- k6에서는 `success`, `expected rejection`, `unexpected`를 분리해 기록한다.
- HTTP `4xx`는 기대 가능한 거부로 분류한다.
- HTTP `5xx`, 네트워크 오류, 인증 오류는 unexpected로 분류한다.
- 실행 후 DB에서 group `1`, reservation `2`, HELD seat `2`만 남는지 검증한다.

측정 스크립트:

- `performance/k6/reservation-contention.js`
- `performance/k6/reservation-overlap-contention.js`

## Run Commands

### General Seat Lookup Baseline

PowerShell 예시:

```powershell
$env:SCHEDULE_ID="1"
$env:CASE_NAME="no-expired"
$env:VUS="1"
$env:DURATION="10s"
k6 run performance/k6/seats-baseline.js
```

### Expiration Test Preparation

좌석 조회가 만료를 처리하는지 확인할 때는 스케줄러가 먼저 만료를 치우지 않도록 테스트 실행 동안 스케줄러 주기를 충분히 길게 둔다. 개발 서버를 아래 override를 포함해 시작하고, 결제하지 않은 예약을 생성한 뒤 `reservation.hold-duration`이 지난 후 스크립트를 실행한다.

```powershell
.\gradlew.bat bootRun --args="--reservation.hold-duration=5s --reservation.expire-scheduler.fixed-delay-ms=3600000"
```

준비 절차:

1. 별도 회차의 좌석을 선택해 결제하지 않은 예약 group을 생성한다.
2. 좌석 ID와 회차 ID를 기록한다.
3. `5초` 이상 기다려 group을 만료 대상 상태로 만든다.
4. 실행 직전에 DB에서 해당 group이 `PENDING`, reservation이 `PENDING`, seat가 `HELD`, `expires_at <= now`인지 확인한다.
5. 아래 스크립트 중 하나를 실행한다.

두 스크립트는 첫 호출에서 상태를 변경하므로 각각 실행 전에 새로운 만료 예약 데이터를 다시 준비한다.

### Expiration First Hit

```powershell
$env:SCHEDULE_ID="<schedule id>"
$env:EXPECTED_RELEASED_SEAT_IDS="<seat id,seat id>"
k6 run performance/k6/expiration-first-hit.js
```

확인값:

- `expiration_first_hit_duration`
- `expiration_first_hit_failed = 0`
- `expired held seats are available after lookup` 체크 성공

### Expiration Concurrency

```powershell
$env:SCHEDULE_ID="<schedule id>"
$env:EXPECTED_RELEASED_SEAT_IDS="<seat id,seat id>"
$env:VUS="20"
k6 run performance/k6/expiration-concurrency.js
```

확인값:

- `expiration_concurrent_duration`
- `expiration_concurrent_failed = 0`
- `expiration_concurrent_unexpected_seat_state = 0`
- 모든 `concurrent lookups observe released seats` 체크 성공

실행 후 서버 로그에서 상태 전이 예외 또는 5xx 응답이 없는지도 함께 확인한다.

### MyPage N+1 Baseline

마이페이지 API는 인증이 필요하므로 브라우저 개발자 도구에서 현재 로그인 사용자의 `Cookie` 헤더 값을 복사해 사용한다.

Smoke 예시:

```powershell
$env:USER_ID="<user id>"
$env:COOKIE="<browser cookie header>"
$env:DATA_SET="groups-2"
$env:VUS="1"
$env:DURATION="10s"
k6 run performance/k6/mypage-baseline.js
```

Baseline 예시:

```powershell
$env:USER_ID="<user id>"
$env:COOKIE="<browser cookie header>"
$env:DATA_SET="groups-20"
$env:VUS="10"
$env:DURATION="30s"
k6 run performance/k6/mypage-baseline.js
```

확인값:

- `mypage_duration`
- `mypage_failed = 0`
- `mypage_empty_reservations = 0`
- `mypage_empty_payments = 0`
- SQL 로그 기준 총 쿼리 수
- `Payment` 건수 증가에 따라 `findByReservationGroupId()` 반복 조회가 증가하는지 여부

### Reservation Create Write Baseline

예약 생성 API는 인증과 CSRF Origin 검증이 필요하다. 브라우저에서 로그인한 뒤 `Cookie` 값을 복사하고, 테스트에 사용할 사용 가능한 좌석 id 목록을 준비한다.

Access Token 쿠키명은 `__Host-access_token`이다. 단순히 토큰 문자열만 넣거나 `access_token` 이름으로 넣으면 인증 필터가 읽지 못해 `401 AUTH_REQUIRED`가 발생한다.

```powershell
$env:COOKIE="__Host-access_token=<access token>"
$env:ORIGIN="https://localhost:3000"
$env:SEAT_IDS="<seat id,seat id,seat id,seat id>"
$env:VUS="2"
$env:ITERATIONS="2"
$env:SEATS_PER_REQUEST="2"
$env:CASE_NAME="reservation-create-unique"
k6 run performance/k6/reservation-create-baseline.js
```

확인값:

- `reservation_create_duration`
- `reservation_create_failed = 0`
- `reservationGroupId` 응답 존재
- 실행 후 DB에서 생성된 group 수와 HELD 좌석 수가 요청 수와 일치하는지 확인

부하 테스트 전용 좌석 초기화:

```powershell
.\performance\reset-load-test-seats.ps1
```

위 스크립트는 `PERF_LOAD_TEST_EVENT`의 `LOAD-*` 좌석만 대상으로 한다. 연결된 `reservations`, `payments`, `reservation_groups`를 제거하고 좌석 상태를 `AVAILABLE`로 되돌린다.

### Reservation Create Contention

```powershell
$env:COOKIE="__Host-access_token=<access token>"
$env:ORIGIN="https://localhost:3000"
$env:SEAT_IDS="391,392"
$env:VUS="10"
$env:ITERATIONS="10"
$env:CASE_NAME="reservation-contention-10vu"
k6 run performance/k6/reservation-contention.js
```

확인값:

- `reservation_contention_success = 1`
- `reservation_contention_rejected = iterations - 1`
- `reservation_contention_unexpected_rate = 0`
- 실행 후 DB에서 group `1`, reservation `2`, HELD seat `2` 확인

### Reservation Create Overlap Contention

```powershell
$env:COOKIE="__Host-access_token=<access token>"
$env:ORIGIN="https://localhost:3000"
$env:SEAT_PAIRS="391,392|392,393"
$env:VUS="10"
$env:ITERATIONS="10"
$env:CASE_NAME="reservation-overlap-contention-10vu"
k6 run performance/k6/reservation-overlap-contention.js
```

확인값:

- `reservation_overlap_success = 1`
- `reservation_overlap_rejected = iterations - 1`
- `reservation_overlap_unexpected_rate = 0`
- 실행 후 DB에서 group `1`, reservation `2` 확인
- HELD seat 조합은 `391,392` 또는 `392,393` 중 하나여야 한다.
- `391`만 HELD 또는 `393`만 HELD 같은 부분 성공 상태가 없어야 한다.

## Next Measurement

1. 예약 생성 write baseline, same-seat contention, overlapping-seat contention은 `10`, `20`, `50` VU 구간에서 1차 재측정했으므로, 같은 조건을 2~3회 반복해 중간값 또는 반복 평균을 산출한다.
2. 결제 승인/취소 중복 요청 상태 전이를 통합 테스트 결과와 함께 문서화한다.
3. 운영 유사 성능 주장이 필요해지는 시점에는 SQL 상세 로그를 낮춘 별도 profile과 실제 사용자 간격/도착률 시나리오를 구성한다.

## References

- Grafana k6, test types: https://grafana.com/docs/k6/latest/testing-guides/test-types/
- Hibernate ORM User Guide, fetching and N+1: https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html
- Toss Payments, payment flow: https://docs.tosspayments.com/guides/v2/get-started/payment-flow
- Toss Payments, idempotency: https://docs.tosspayments.com/blog/what-is-idempotency
