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
| 5 | Refresh Token 재발급 | 조건부 update + 동시성 검증 | 동일 refresh token 동시 요청은 하나만 성공, 서로 다른 refresh token은 독립 재발급 |

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
| 2026-06-02 | reservation-create / load seats run 2 | `userId=1`, `scheduleId=18`, unique seat ids `391~590`, 2 seats/request | `10 / 100 shared iterations` | 100 | 0.00% | 153.78 ms | local dev, load dataset run 2, 274.82 req/s |
| 2026-06-02 | reservation-create / load seats run 2 | `userId=1`, `scheduleId=18`, unique seat ids `391~790`, 2 seats/request | `20 / 200 shared iterations` | 200 | 0.00% | 84.73 ms | local dev, load dataset run 2, 331.93 req/s |
| 2026-06-02 | reservation-create / load seats run 2 | `userId=1`, `scheduleId=18`, unique seat ids `391~1390`, 2 seats/request | `50 / 500 shared iterations` | 500 | 0.00% | 74.98 ms | local dev, load dataset run 2, 892.67 req/s |
| 2026-06-02 | reservation-create / load seats run 3 | `userId=1`, `scheduleId=18`, unique seat ids `391~590`, 2 seats/request | `10 / 100 shared iterations` | 100 | 0.00% | 10.95 ms | local dev, load dataset run 3, 822.71 req/s |
| 2026-06-02 | reservation-create / load seats run 3 | `userId=1`, `scheduleId=18`, unique seat ids `391~790`, 2 seats/request | `20 / 200 shared iterations` | 200 | 0.00% | 33.33 ms | local dev, load dataset run 3, 766.57 req/s |
| 2026-06-02 | reservation-create / load seats run 3 | `userId=1`, `scheduleId=18`, unique seat ids `391~1390`, 2 seats/request | `50 / 500 shared iterations` | 500 | 0.00% | 93.50 ms | local dev, load dataset run 3, 705.79 req/s, DB 사후 검증: group 500 / reservation 1000 / distinct HELD seat 1000 / duplicate active seat assignment 0 |
| 2026-05-30 | reservation-create / same-seat contention | `userId=1`, `scheduleId=18`, same seat ids `391,392`, 2 seats/request | `10 / 10 shared iterations` | 10 | expected reject 9 | 38.84 ms | local dev, success 1 / rejected 9 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 |
| 2026-05-30 | reservation-create / same-seat contention | `userId=1`, `scheduleId=18`, same seat ids `391,392`, 2 seats/request | `20 / 20 shared iterations` | 20 | expected reject 19 | 17.68 ms | local dev, success 1 / rejected 19 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 |
| 2026-05-30 | reservation-create / same-seat contention | `userId=1`, `scheduleId=18`, same seat ids `391,392`, 2 seats/request | `50 / 50 shared iterations` | 50 | expected reject 49 | 26.45 ms | local dev, success 1 / rejected 49 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 |
| 2026-05-30 | reservation-create / overlapping-seat contention | `userId=1`, `scheduleId=18`, alternating seat pairs `391,392` and `392,393` | `10 / 10 shared iterations` | 10 | expected reject 9 | 17.98 ms | local dev, success 1 / rejected 9 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seats `392,393` |
| 2026-05-30 | reservation-create / overlapping-seat contention | `userId=1`, `scheduleId=18`, alternating seat pairs `391,392` and `392,393` | `20 / 20 shared iterations` | 20 | expected reject 19 | 24.40 ms | local dev, success 1 / rejected 19 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seats `391,392` |
| 2026-05-30 | reservation-create / overlapping-seat contention | `userId=1`, `scheduleId=18`, alternating seat pairs `391,392` and `392,393` | `50 / 50 shared iterations` | 50 | expected reject 49 | 35.11 ms | local dev, success 1 / rejected 49 / unexpected 0, DB 사후 검증: group 1 / reservation 2 / HELD seats `391,392` |
| 2026-05-31 | reservation-create / same-seat spike | `scheduleId=18`, same seat ids `391,392`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 29,702 | expected reject 29,701 | 1.36 s | local dev spike, success 1 / unexpected 0 / interrupted 193, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 |
| 2026-06-01 | reservation-create / same-seat spike run 2 | `scheduleId=18`, same seat ids `391,392`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 29,301 | expected reject 29,300 | 1.45 s | local dev spike run 2, success 1 / unexpected 0 / interrupted 225, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 / duplicate active seat assignment 0 |
| 2026-06-01 | reservation-create / same-seat spike run 3 | `scheduleId=18`, same seat ids `391,392`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 30,837 | expected reject 30,836 | 1.36 s | local dev spike run 3, success 1 / unexpected 0 / interrupted 199, DB 사후 검증: group 1 / reservation 2 / HELD seat 2 / duplicate active seat assignment 0 |
| 2026-05-31 | reservation-create / overlapping-seat spike | `scheduleId=18`, alternating seat pairs `391,392` and `392,393`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 31,542 | expected reject 31,541 | 1.31 s | local dev spike, success 1 / unexpected 0 / interrupted 184, DB 사후 검증: group 1 / reservation 2 / HELD seats `392,393` |
| 2026-06-01 | reservation-create / overlapping-seat spike run 2 | `scheduleId=18`, alternating seat pairs `391,392` and `392,393`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 31,517 | expected reject 31,516 | 1.34 s | local dev spike run 2, success 1 / unexpected 0 / interrupted 186, DB 사후 검증: group 1 / reservation 2 / HELD seats `391,392` / duplicate active seat assignment 0 |
| 2026-06-01 | reservation-create / overlapping-seat spike run 3 | `scheduleId=18`, alternating seat pairs `391,392` and `392,393`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 30,228 | expected reject 30,227 | 1.35 s | local dev spike run 3, success 1 / unexpected 0 / interrupted 204, DB 사후 검증: group 1 / reservation 2 / HELD seats `392,393` / duplicate active seat assignment 0 |
| 2026-05-31 | reservation-create / random mixed-seat spike | `scheduleId=18`, random seats `391~1390`, request ratio 1 seat `40%` / 2 seats `60%`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 59,171 | expected reject 58,433 | 801.45 ms | local dev spike, success group 738 / unexpected 0 / interrupted 0, DB 사후 검증: reservation 1000 / distinct HELD seat 1000 / duplicate seat assignment 0 |
| 2026-06-01 | reservation-create / random mixed-seat spike run 2 | `scheduleId=18`, random seats `391~1390`, request ratio 1 seat `40%` / 2 seats `60%`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 55,942 | expected reject 55,217 | 788.37 ms | local dev spike run 2, success group 725 / unexpected 0 / interrupted 0, DB 사후 검증: reservation 1000 / distinct HELD seat 1000 / duplicate seat assignment 0 / invalid group size 0 |
| 2026-06-01 | reservation-create / random mixed-seat spike run 3 | `scheduleId=18`, random seats `391~1390`, request ratio 1 seat `40%` / 2 seats `60%`, ramping VUs | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 59,006 | expected reject 58,273 | 732.84 ms | local dev spike run 3, success group 733 / unexpected 0 / interrupted 0, DB 사후 검증: reservation 1000 / distinct HELD seat 1000 / duplicate seat assignment 0 / invalid group size 0 |
| 2026-05-31 | event-open / seat lookup + random reservation mixed spike | `scheduleId=18`, seat lookup `70%`, random reservation `30%`, reservation request ratio 1 seat `40%` / 2 seats `60%` | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 16,854 | expected reject 3,916 | 3.30 s | local dev mixed spike, seat lookup 11,596 / reservation 5,258 / reservation success 1,342 / unexpected 0 / interrupted 720. 좌석 조회의 수동 만료 처리 포함. 최종 active 상태: group 375 / reservation 522 / distinct HELD seat 522 / duplicate active seat assignment 0 |
| 2026-06-01 | event-open / seat lookup + random reservation mixed spike without expiration | `scheduleId=18`, `hold-duration=2m`, seat lookup `70%`, random reservation `30%`, reservation request ratio 1 seat `40%` / 2 seats `60%` | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 21,238 | expected reject 5,629 | 1.90 s | local dev mixed spike pure comparison, seat lookup 14,889 / reservation 6,349 / reservation success 720 / unexpected 0 / interrupted 375. 수동 만료 개입 없음. 최종 상태: PENDING group 720 / reservation 979 / HELD seat 979 / EXPIRED group 0 / duplicate active seat assignment 0 |
| 2026-06-01 | event-open / seat lookup + random reservation mixed spike without expiration run 2 | `scheduleId=18`, `hold-duration=2m`, seat lookup `70%`, random reservation `30%`, reservation request ratio 1 seat `40%` / 2 seats `60%` | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 20,927 | expected reject 5,570 | 1.81 s | local dev mixed spike pure comparison run 2, seat lookup 14,645 / reservation 6,282 / reservation success 712 / unexpected 0 / interrupted 368. 최종 상태: PENDING group 712 / reservation 977 / HELD seat 977 / EXPIRED group 0 / duplicate active seat assignment 0 |
| 2026-06-01 | event-open / seat lookup + random reservation mixed spike without expiration run 3 | `scheduleId=18`, `hold-duration=2m`, seat lookup `70%`, random reservation `30%`, reservation request ratio 1 seat `40%` / 2 seats `60%` | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 18,972 | expected reject 5,059 | 1.97 s | local dev mixed spike pure comparison run 3, seat lookup 13,220 / reservation 5,752 / reservation success 693 / unexpected 0 / interrupted 410. 최종 상태: PENDING group 693 / reservation 983 / HELD seat 983 / EXPIRED group 0 / duplicate active seat assignment 0 |
| 2026-06-01 | event-open / seat lookup + random reservation mixed spike with expiration run 2 | `scheduleId=18`, `hold-duration=10s`, seat lookup `70%`, random reservation `30%`, reservation request ratio 1 seat `40%` / 2 seats `60%` | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 16,050 | expected reject 3,488 | 3.31 s | local dev mixed spike expiration run 2, seat lookup 11,222 / reservation 4,828 / reservation success 1,340 / unexpected 0 / interrupted 711. 최종 상태: EXPIRED group 1,004 / PENDING group 363 / active reservation 499 / HELD seat 499 / duplicate active seat assignment 0 |
| 2026-06-01 | event-open / seat lookup + random reservation mixed spike with expiration run 3 | `scheduleId=18`, `hold-duration=10s`, seat lookup `70%`, random reservation `30%`, reservation request ratio 1 seat `40%` / 2 seats `60%` | `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 / 26s` | 16,756 | expected reject 3,916 | 3.47 s | local dev mixed spike expiration run 3, seat lookup 11,501 / reservation 5,255 / reservation success 1,339 / unexpected 0 / interrupted 721. 최종 상태: EXPIRED group 982 / PENDING group 384 / active reservation 527 / HELD seat 527 / duplicate active seat assignment 0 |

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

2026-06-02 로컬 DevDB 임시 인덱스 적용 실험:

- 비교 인덱스: `idx_reservations_reservation_group_id`
- 실험 절차: 적용 전 측정, 임시 인덱스 생성, `ANALYZE reservations`, 동일 조건 재측정, 임시 인덱스 제거
- 마이페이지 데이터: `userId=1`, `CONFIRMED/CANCELED group 100`, reservation `200`
- 예약 생성 데이터: `scheduleId=18`, `LOAD-0001 ~ LOAD-1000`, 요청당 좌석 `2`

마이페이지 `10 VU / 30s`, 3회 중앙값:

| Condition | p95 median | Throughput median | Failure rate |
| --- | ---: | ---: | ---: |
| 인덱스 적용 전 | 17.07 ms | 666.54 req/s | 0.00% |
| 인덱스 적용 후 | 15.06 ms | 714.64 req/s | 0.00% |

예약 생성 write baseline, 각 3회 중앙값:

| VUs / Iterations | 적용 전 p95 | 적용 후 p95 | 적용 전 처리량 | 적용 후 처리량 | Failure rate |
| --- | ---: | ---: | ---: | ---: | ---: |
| `10 / 100` | 17.59 ms | 8.79 ms | 691.00 req/s | 990.94 req/s | 0.00% |
| `20 / 200` | 15.48 ms | 17.06 ms | 1,235.79 req/s | 1,193.24 req/s | 0.00% |
| `50 / 500` | 47.57 ms | 51.69 ms | 1,242.94 req/s | 1,130.59 req/s | 0.00% |

`EXPLAIN (ANALYZE, BUFFERS)`에서 인덱스 적용 전후 모두 planner가 `Seq Scan on reservations`를 선택했다. 측정 당시 load reset 후 `reservations`는 `508 rows`였고, 마이페이지 reservation 조회 실행 시간은 적용 전 `0.524 ms`, 적용 후 `0.496 ms`였다. 현재 데이터 규모에서는 마이페이지 read 개선이 인덱스 사용에 따른 효과라고 보기 어렵다.

인덱스 적용 후 마이페이지 조회 지표는 소폭 개선됐다. 그러나 실행 계획에서는 인덱스가 사용되지 않아 인덱스 적용에 따른 개선이라고 판단하기 어렵다. 또한 예약 생성 write는 `20`, `50 VU` 구간에서 적용 후 중앙값이 악화됐다. `10 VU` 구간 개선은 로컬 환경 변동 범위로 판단한다. 따라서 현재 데이터 규모와 트래픽 조건에서는 읽기 성능 개선 근거가 부족하고 쓰기 비용이 추가될 가능성이 있어 임시 인덱스를 제거했으며 Flyway migration에는 반영하지 않았다. 데이터 증가 후 group 기준 조회가 병목으로 다시 확인되면 같은 절차로 재검토한다.

## Phase 3 And 4: Consistency Evidence

예약 선점과 결제 상태 전이는 단순 응답 시간보다 정합성이 우선이다.

| Flow | Scenario | Pass condition |
| --- | --- | --- |
| 예약 선점 | 여러 사용자가 겹치는 좌석 묶음을 동시에 예약 | 성공 group 하나만 존재하고 부분 성공이 없음 |
| 결제 승인 | 같은 승인 요청이 중복 도착 | 결제 및 예약 확정 상태가 중복 전이되지 않음 |
| 결제 취소 | 같은 취소 요청이 중복 도착 | 취소 결과가 정책대로 멱등 처리되고 좌석이 한 번만 복구됨 |

2026-06-02 기준 `PaymentServiceIntegrationTest`에서 결제 중복 요청 정합성을 검증했다.

- 승인: 동일 `orderId` 요청 2개를 동시에 실행해 PG confirm Mock 호출이 정확히 1회인지 확인했다. 최종 상태는 `Payment=APPROVED`, `ReservationGroup=CONFIRMED`, 모든 `Reservation=CONFIRMED`, 모든 `Seat=BOOKED`다.
- 취소: 동일 `paymentId` 요청 2개를 동시에 실행해 PG cancel Mock 호출이 정확히 1회인지 확인했다. 최종 상태는 `Payment=CANCELED`, `ReservationGroup=CANCELED`, 모든 `Reservation=CANCELED`, 모든 `Seat=AVAILABLE`다.
- 재검증: `PaymentServiceIntegrationTest` 총 `13`개 통과, `failures=0`, `errors=0`, `skipped=0`, `BUILD SUCCESSFUL in 18s`.

결제 외부 PG 자체의 처리량을 측정하려고 대량 승인 요청을 보내지 않는다. 이 프로젝트에서는 내부 상태 전이와 중복 요청 방어를 검증 대상으로 삼는다.

### Reservation Create Consistency Result

2026-05-28 기준 `ReservationConcurrencyTest`로 예약 생성 정합성을 먼저 검증했다.

- 시나리오: 10명의 사용자가 `[A-1, A-2]`, `[A-2, A-3]`처럼 겹치는 2좌석 묶음을 동시에 예약 요청
- 기대값: 하나의 group만 성공하고 나머지는 실패
- 결과: 성공 1건, 실패 9건, reservation 2건, group 1건, HELD 좌석 2건

이 테스트는 처리량 측정보다 정합성 검증이 목적이다. 다음 단계의 k6 예약 생성 write baseline은 이 정합성이 유지되는 상태에서 p95, 실패율, 락 대기 영향을 관찰하는 보조 지표로 사용한다.

### Reservation Create Lock Strategy Tradeoff

현재 예약 생성은 요청 좌석 id를 정렬한 뒤 `PESSIMISTIC_WRITE`로 좌석 row를 잠그고, 트랜잭션 안에서 `Seat.hold()`를 호출해 `AVAILABLE -> HELD` 상태 전이를 수행한다. 결제 승인 단계는 `Payment` row 비관적 락으로 동일 승인 요청을 직렬화하고, `Seat.book()`으로 `HELD -> BOOKED`를 수행한다. 따라서 좌석 락은 조회부터 결제 완료까지 유지되는 락이 아니라 예약 생성 트랜잭션 동안의 선점 보호 장치다.

인기 공연 E2E arrival-rate 최초 측정에서는 예약 생성보다 조회 경로가 더 큰 병목으로 나타났다.

| Metric | Result |
| --- | ---: |
| 전체 여정 p95 | `15.60s` |
| 좌석 조회 p95 | `3.16s` |
| 예약 생성 p95 | `28.63ms` |
| 결제 준비 p95 | `26.47ms` |
| 결제 승인 p95 | `38.17ms` |

좌석 선점을 조건부 update로 바꾸면 다음처럼 DB update 결과 row 수로 선점 성공 여부를 판단할 수 있다.

```sql
update seats
set status = 'HELD'
where id in (:seatIds)
  and status = 'AVAILABLE'
```

영향 row 수가 요청 좌석 수와 같으면 성공, 하나라도 부족하면 실패로 보고 트랜잭션을 롤백한다. PostgreSQL에서 `UPDATE`도 대상 row에 row-level lock을 획득하므로 동시성 제어 자체는 가능하다. 다만 이 방식은 `Seat.hold()` 도메인 메서드의 상태 전이 검증을 우회하고, 상태 전이 정책이 SQL 조건으로 분산된다.

| Strategy | 장점 | 단점 | 현재 판단 |
| --- | --- | --- | --- |
| 비관적 락 + 도메인 상태 전이 | `Seat.hold()` / `Seat.book()` 규칙이 유지되고, 다중 좌석 정렬 락으로 데드락 위험을 줄일 수 있다. | 경합 시 실패 요청도 앞선 트랜잭션 종료를 기다린 뒤 거부될 수 있다. | 유지 |
| 조건부 update | 이미 선점된 좌석에 대한 실패 요청을 빠르게 탈락시킬 수 있고, 엔티티 조회 비용을 줄일 수 있다. | 도메인 메서드를 우회하며, bulk update 후 영속성 컨텍스트 stale 문제를 관리해야 한다. 부분 성공 방지를 위해 영향 row 수 검증과 롤백 정책이 필수다. | 보류 |
| 낙관적 락 | 도메인 메서드를 유지할 수 있고, 충돌이 적은 일반 수정에는 단순하다. | 인기 좌석처럼 충돌이 의도적으로 많은 구간에서는 실패가 늦게 감지되고 retry 비용이 커질 수 있다. | 우선순위 낮음 |
| `NOWAIT` 또는 짧은 lock timeout | 도메인 상태 전이를 유지하면서 락 대기 시간을 제한할 수 있다. | 잠깐 점유된 좌석도 즉시 실패 처리될 수 있어 false reject가 늘 수 있다. | 실험 후보 |

따라서 현재 최적화 우선순위는 좌석 선점 조건부 update 전환이 아니라, 공연 목록/상세 캐시와 좌석 조회 연계 만료 처리 비용 축소다. 예약 생성 p95가 후속 측정에서 실제 병목으로 확인될 때만 `NOWAIT`, 짧은 lock timeout, 조건부 update를 동일한 E2E arrival-rate 조건에서 비교한다. 비교 시 성공 결제 수, 정상 경합 거부 수, 예상 밖 오류, 중복 active 좌석 배정, 부분 성공 group을 함께 확인한다.

### Event Cache Prerequisite

공연 목록/상세 캐시는 예매/결제 정합성 영역이 아니라 반복 조회 비용을 줄이기 위한 read-through local cache 후보로 본다. 캐시 적용 전에 서버 응답에서 시간에 따라 변하는 `displayStatus`를 제거했다.

- 백엔드는 `bookingOpenAt`, `runStartAt`, `runEndAt` 같은 원천 시간 데이터만 응답한다.
- 프론트 서버 컴포넌트 데이터 조합 단계에서 `runStartAt`, `runEndAt` 기준으로 `UPCOMING / NOW_SHOWING / ENDED` 표시 상태를 계산한다.
- `displayStatus`는 홈 화면 섹션 분류와 배지 표시용 파생값이며 예약/결제 가능 여부의 서버 검증 기준으로 사용하지 않는다.
- 백엔드는 예약 생성 시 `bookingOpenAt` 이전 요청을 `ReservationGroup` 생성과 `Seat HELD` 전이 전에 거부한다.
- 좌석 조회는 실시간 좌석 상태와 수동 만료 처리를 포함하므로 캐시 대상에서 제외한다.
- `soldOut`, `availableCount` 같은 예약 상태는 공연 목록/상세 캐시에 포함하지 않는다.

이 정리는 공연 목록/상세 캐시 TTL을 정할 때 화면 표시 상태의 stale 문제를 줄이기 위한 선행 작업이다. 캐시 적용 후에는 동일한 `dev,perf` arrival-rate E2E 조건에서 공연 목록 p95, 공연 상세 p95, 전체 여정 p95, dropped iteration, DB 정합성을 다시 비교한다.

### Event Cache Policy

공연 목록/상세 캐시는 `Spring Cache + Caffeine` 기반의 JVM 로컬 캐시로 적용한다. 캐시는 DB 원본을 대체하지 않고, 반복 조회 비용을 줄이는 보조 계층으로만 사용한다. 캐시 이름은 `CacheNames`에서 관리하고, TTL과 최대 크기는 `cache.event.*` 설정으로 profile별 분리한 뒤 `CacheConfig`에서 `@Value`로 주입한다.

| Cache | Target | Key | prod TTL / size | dev TTL / size | Note |
| --- | --- | --- | ---: | ---: | --- |
| `eventList` | `GET /api/v1/events` | `SimpleKey.EMPTY` | `60s / 1` | `10s / 1` | 홈/인기 공연 목록 반복 조회 비용 축소 |
| `eventDetail` | `GET /api/v1/events/{eventId}` | `eventId` | `300s / 500` | `30s / 100` | 상세 진입 반복 조회 비용 축소 |

서버 재시작 또는 프로세스 종료 시 Caffeine 로컬 캐시는 사라진다. 현재 대상 데이터는 공연 목록/상세 조회이고 원본은 DB에 있으므로, 별도 복구 로직이나 Redis warm-up은 적용하지 않는다. 재시작 직후 첫 요청은 DB에서 조회하고 캐시에 다시 적재하는 read-through 흐름을 허용한다.

이 선택의 트레이드오프는 다음과 같다.

- 이득: Redis 도입 없이 목록/상세 반복 조회의 DB 접근을 줄이고, 현재 E2E spike의 조회 단계 병목을 좁은 범위에서 개선할 수 있다.
- 비용: 서버 재시작 직후 첫 요청은 cold cache로 DB를 조회하고, 관리자 수정이 생기면 TTL 동안 구버전 응답이 노출될 수 있다.
- 보류: 다중 서버 캐시 공유, 관리자 수정 즉시 반영, cold start 지연 최소화가 필요해지는 시점에 Redis 또는 cache warm-up을 후속 후보로 검토한다.
- 제외: 좌석 조회, 예약 생성, 결제, 마이페이지는 실시간 상태와 사용자별 상태가 섞이므로 이번 캐시 대상에서 제외한다.

### Schedule Availability Policy

메인 화면의 예매 버튼 비활성화와 매진 이후 좌석 payload 축소는 공연 카탈로그 캐시와 분리한다.

- `GET /api/v1/event/schedules/availability?scheduleIds=...`는 여러 회차의 상태 요약을 한 번에 반환한다.
- 매진 기준은 `AVAILABLE=0`, `HELD=0`, `BOOKED>0`이다.
- `HELD` 좌석은 만료 후 `AVAILABLE`로 복구될 수 있으므로 매진으로 보지 않는다.
- 프론트 서버는 캐시 가능한 `/event` 응답과 실시간 availability 응답을 조합해 ViewModel의 `schedule.soldOut`을 만든다.
- `/event` 또는 `/event/{eventId}` 응답에는 `soldOut`을 직접 포함하지 않는다. 예약 상태가 카탈로그 캐시에 섞이면 매진 직후 stale 값이 노출될 수 있기 때문이다.
- 좌석 조회 API는 `{ scheduleId, soldOut, seats }` wrapper를 반환한다. `soldOut=true`이면 좌석 목록을 내려주지 않는다.

후속 성능 재측정에서는 동일한 arrival-rate 조건에서 매진 이후 `data_received`, 좌석 조회 p95, 전체 여정 p95, dropped iteration을 기존 2차 개선값과 비교한다.

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

예약 생성 write baseline 반복 측정:

| VUs / Iterations | Run 1 p95 | Run 2 p95 | Run 3 p95 | p95 중앙값 | Run 1 처리량 | Run 2 처리량 | Run 3 처리량 | 처리량 중앙값 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `10 / 100` | 16.27 ms | 153.78 ms | 10.95 ms | 16.27 ms | 766.67 req/s | 274.82 req/s | 822.71 req/s | 766.67 req/s |
| `20 / 200` | 23.74 ms | 84.73 ms | 33.33 ms | 33.33 ms | 916.82 req/s | 331.93 req/s | 766.57 req/s | 766.57 req/s |
| `50 / 500` | 98.07 ms | 74.98 ms | 93.50 ms | 93.50 ms | 740.74 req/s | 892.67 req/s | 705.79 req/s | 740.74 req/s |

세 구간의 유효 실행은 모두 실패율 `0.00%`였다. 마지막 `50 VU / 500 shared iterations` 실행 후 DB 사후 검증에서 `PENDING group 500`, active reservation `1,000`, distinct HELD seat `1,000`, 중복 active 좌석 배정 `0`을 확인했다.

로컬 dev 환경은 k6, 애플리케이션, PostgreSQL이 같은 장비 자원을 공유하고 SQL 상세 로그도 활성화되어 있다. 특히 `10`, `20 VU`의 run 2는 다른 실행보다 지연이 크게 나타났다. 따라서 단일 실행값으로 처리 능력을 주장하지 않고 3회 중앙값을 로컬 비교 baseline으로 사용한다.

### Reservation Create Contention Baseline

같은 좌석 묶음에 여러 요청을 동시에 보내는 경합 테스트는 HTTP 실패율이 높게 나오는 것이 정상이다. 이 테스트의 성공 기준은 모든 요청이 `200`을 받는 것이 아니라, 정확히 하나의 요청만 예약 group을 생성하고 나머지 요청은 이미 선점된 좌석으로 거부되는 것이다.

경합 시나리오는 두 가지로 나눈다.

| Scenario | Request shape | Purpose |
| --- | --- | --- |
| Same-seat contention | 모든 요청이 같은 `seatIds`를 사용한다. 예: `[391,392]` vs `[391,392]` | 완전히 동일한 좌석 묶음 중복 선점 방지 |
| Overlapping-seat contention | 요청이 서로 다르지만 일부 좌석이 겹친다. 예: `[391,392]` vs `[392,393]` | 하나라도 겹치면 실패 요청 전체가 롤백되는지 확인 |
| Random mixed-seat spike | `391~1390` 범위에서 랜덤 좌석을 선택한다. 요청의 `40%`는 1좌석, `60%`는 2좌석을 요청한다. | 실제 오픈 상황에 가까운 좌석 분산 요청, 충돌, 매진 처리 검증 |

측정 기준:

- 모든 iteration이 같은 `seatIds`를 사용한다.
- k6에서는 `success`, `expected rejection`, `unexpected`를 분리해 기록한다.
- HTTP `4xx`는 기대 가능한 거부로 분류한다.
- HTTP `5xx`, 네트워크 오류, 인증 오류는 unexpected로 분류한다.
- 실행 후 DB에서 group `1`, reservation `2`, HELD seat `2`만 남는지 검증한다.

측정 스크립트:

- `performance/k6/reservation-contention.js`
- `performance/k6/reservation-overlap-contention.js`
- `performance/k6/reservation-spike.js`

### Reservation Create Spike

Spike 테스트는 인기공연 오픈 순간처럼 트래픽이 짧은 시간에 급증했다가 감소하는 상황을 관찰한다. 로컬 dev 환경에서는 `10 -> 100 -> 300 -> 500 -> 1000 -> 1500 -> 2000 -> 10 VU` 단계로 올리고, 절대 처리 능력이 아니라 정합성 유지, 오류율, 지연 증가, 로컬 테스트 환경 한계를 기록한다.

세 모드를 분리한다.

| Mode | Request shape | Expected result |
| --- | --- | --- |
| `same` | 모든 요청이 `[391,392]` | 성공 group `1`, reservation `2`, HELD seat `2` |
| `overlap` | `[391,392]`, `[392,393]` 반복 | 성공 group `1`, reservation `2`, 부분 성공 없음 |
| `random` | 좌석 `391~1390` 랜덤 선택, 1좌석 `40%`, 2좌석 `60%` | 동일 좌석 중복 배정 없음, HELD seat 수와 reservation 수 일치 |

`ramping-vus` 종료 과정에서 발생한 interrupted iteration은 ramp-down 중 정리된 요청으로 별도 기록한다. unexpected 오류와 DB 정합성 실패 여부를 함께 확인해야 한다.

동일 좌석 Spike 반복 측정:

| Run | 전체 요청 수 | 처리량 | 전체 p95 | 성공 group | 예상 거부 | 예상 밖 오류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 29,702 | 1,138.00 req/s | 1.36 s | 1 | 29,701 | 0 |
| 2 | 29,301 | 1,126.09 req/s | 1.45 s | 1 | 29,300 | 0 |
| 3 | 30,837 | 1,185.49 req/s | 1.36 s | 1 | 30,836 | 0 |
| 중앙값 | 29,702 | 1,138.00 req/s | 1.36 s | 1 | 29,701 | 0 |

세 실행 모두 DB 사후 검증에서 `group 1`, `reservation 2`, `HELD seat 2`, 중복 active 좌석 배정 `0`을 확인했다.

겹치는 좌석 Spike 반복 측정:

| Run | 전체 요청 수 | 처리량 | 전체 p95 | 성공 group | 예상 거부 | 예상 밖 오류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 31,542 | 1,210.58 req/s | 1.31 s | 1 | 31,541 | 0 |
| 2 | 31,517 | 1,211.68 req/s | 1.34 s | 1 | 31,516 | 0 |
| 3 | 30,228 | 1,161.93 req/s | 1.35 s | 1 | 30,227 | 0 |
| 중앙값 | 31,517 | 1,210.58 req/s | 1.34 s | 1 | 31,516 | 0 |

세 실행 모두 DB 사후 검증에서 `group 1`, `reservation 2`, HELD 좌석은 `391,392` 또는 `392,393` 중 하나, 중복 active 좌석 배정 `0`을 확인했다. 공유 좌석 `392`가 포함되지 않은 부분 성공 상태는 발생하지 않았다.

랜덤 좌석 1장·2장 혼합 Spike 반복 측정:

| Run | 전체 요청 수 | 처리량 | 전체 p95 | 성공 group | 예상 거부 | 예상 밖 오류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 59,171 | 2,261.38 req/s | 801.45 ms | 738 | 58,433 | 0 |
| 2 | 55,942 | 2,150.91 req/s | 788.37 ms | 725 | 55,217 | 0 |
| 3 | 59,006 | 2,268.63 req/s | 732.84 ms | 733 | 58,273 | 0 |
| 중앙값 | 59,006 | 2,261.38 req/s | 788.37 ms | 733 | 58,273 | 0 |

세 실행 모두 DB 사후 검증에서 `reservation 1,000`, distinct HELD seat `1,000`, 중복 active 좌석 배정 `0`, 잘못된 group 크기 `0`을 확인했다.

### Event Open Mixed Spike

실제 오픈 상황에 가까운 혼합 Spike는 좌석 조회 `70%`, 랜덤 예약 생성 `30%` 비율로 구성한다. 예약 요청 내부 비율은 1좌석 `40%`, 2좌석 `60%`다.

현재 `EventService.getSeats(scheduleId)`는 좌석을 반환하기 전에 해당 회차의 만료 group을 즉시 정리한다. 따라서 혼합 Spike 결과에는 조회, 예약 생성뿐 아니라 테스트 도중 만료된 좌석의 복구 비용도 함께 포함된다. 순수 API 처리 비용을 비교하려면 별도로 만료 시간을 Spike 전체 실행 시간보다 길게 설정한 뒤 재측정해야 한다.

측정 스크립트:

- `performance/k6/event-open-mixed-spike.js`

### Event Open Mixed Spike Comparison

혼합 Spike의 순수 조회 및 예약 생성 비용을 분리하기 위해 `dev` profile의 `reservation.hold-duration`을 `2m`으로 늘리고 동일한 Spike를 재실행했다. Spike 실행 시간은 `26s`이므로 테스트 중 생성된 group은 만료 대상이 되지 않는다.

| Metric | 수동 만료 포함 3회 중앙값 | 만료 제외 3회 중앙값 | Change |
| --- | ---: | ---: | ---: |
| 전체 요청 수 | 16,756 | 20,927 | `+24.9%` |
| 전체 처리량 | 606.20 req/s | 801.51 req/s | `+32.2%` |
| 전체 p95 | 3.31 s | 1.90 s | `-42.6%` |
| 좌석 조회 요청 수 | 11,501 | 14,645 | `+27.3%` |
| 좌석 조회 p95 | 3.28 s | 1.81 s | `-44.8%` |
| 예약 생성 요청 수 | 5,255 | 6,282 | `+19.5%` |
| 예약 생성 p95 | 3.47 s | 1.98 s | `-42.9%` |
| 예상 밖 오류 | 0 | 0 | 유지 |
| 중복 active 좌석 배정 | 0 | 0 | 유지 |

수동 만료 포함 반복 측정:

| Run | 전체 요청 수 | 처리량 | 전체 p95 | 좌석 조회 p95 | 예약 생성 p95 | 예상 밖 오류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 16,854 | 610.26 req/s | 3.30 s | 3.25 s | 3.34 s | 0 |
| 2 | 16,050 | 580.87 req/s | 3.31 s | 3.28 s | 3.47 s | 0 |
| 3 | 16,756 | 606.20 req/s | 3.47 s | 3.42 s | 3.58 s | 0 |
| 중앙값 | 16,756 | 606.20 req/s | 3.31 s | 3.28 s | 3.47 s | 0 |

만료 제외 순수 비교 반복 측정:

| Run | 전체 요청 수 | 처리량 | 전체 p95 | 좌석 조회 p95 | 예약 생성 p95 | 예상 밖 오류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 21,238 | 813.99 req/s | 1.90 s | 1.81 s | 1.98 s | 0 |
| 2 | 20,927 | 801.51 req/s | 1.81 s | 1.77 s | 1.94 s | 0 |
| 3 | 18,972 | 723.18 req/s | 1.97 s | 1.92 s | 2.09 s | 0 |
| 중앙값 | 20,927 | 801.51 req/s | 1.90 s | 1.81 s | 1.98 s | 0 |

순수 비교 실행의 DB 사후 검증:

- 초기 상태: 부하 테스트 전용 좌석 `AVAILABLE 1,000 / 1,000`
- 3회 모두 active reservation 수와 distinct active seat 수가 일치
- 3회 모두 동일 seat에 대한 중복 active reservation `0`
- 3회 모두 group 크기는 1좌석 또는 2좌석이며 잘못된 크기 group `0`
- 3회 모두 만료된 group `0`

해석:

- 수동 만료 복구를 제외하면 3회 중앙값 기준 전체 p95가 `3.31s -> 1.90s`로 감소하고 처리량은 `606.20 -> 801.51 req/s`로 증가했다.
- 기존 혼합 Spike는 좌석 조회와 예약 생성만의 비용이 아니라, 조회 시점의 만료 group 탐색 및 상태 복구 비용까지 포함한 운영 시나리오 baseline이다.
- 수동 만료 포함 조건과 제외 조건을 각각 3회 반복한 중앙값에서도 처리량과 p95 차이가 유지됐다. 따라서 현재 구조에서 조회 연계 만료 정리는 오픈 시점 병목에 영향을 주는 비용으로 분리해 관리할 필요가 있다.
- 현재 단계에서는 수동 만료 정리를 제거하지 않는다. 스케줄러만 의존하면 주기 사이에 만료된 좌석이 판매 불가 상태로 남아 사용자에게 최신 좌석을 제공하지 못할 수 있다. 예매 시스템에서는 처리량 개선보다 좌석 상태 정합성과 즉시 재판매 가능성이 우선이다.
- 후속 최적화는 정합성 정책을 유지하는 범위에서 검토한다. 후보는 회차별 만료 대상 조회 범위 축소, 조건부 상태 전이, 만료 처리량 제한, 스케줄러 주기 조정이다.
- 로컬 dev 환경에서 k6, 애플리케이션, PostgreSQL이 같은 장비 자원을 공유하고 SQL 상세 로그도 활성화되어 있으므로 운영 처리량 보장 수치로 사용하지 않는다.

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

### Reservation Create Spike

```powershell
$env:COOKIE="__Host-access_token=<access token>"
$env:ORIGIN="https://localhost:3000"
$env:MODE="random"
$env:CASE_NAME="reservation-spike-random-mixed"
$env:MIN_SEAT_ID="391"
$env:MAX_SEAT_ID="1390"
$env:SINGLE_SEAT_RATIO="0.4"
k6 run performance/k6/reservation-spike.js
```

확인값:

- `reservation_spike_unexpected_rate = 0`
- 동일 좌석 중복 reservation이 없어야 한다.
- 최종 HELD seat 수와 reservation 수가 일치해야 한다.
- 각 성공 group은 reservation을 `1`개 또는 `2`개만 가져야 한다.
- 로컬 결과는 운영 처리량 보장이 아니라 운영 유사 시나리오 설계를 위한 기준값으로 사용한다.

### Event Open Mixed Spike

```powershell
$env:COOKIE="__Host-access_token=<access token>"
$env:ORIGIN="https://localhost:3000"
$env:SCHEDULE_ID="18"
$env:CASE_NAME="event-open-mixed-spike"
$env:MIN_SEAT_ID="391"
$env:MAX_SEAT_ID="1390"
$env:SINGLE_SEAT_RATIO="0.4"
k6 run performance/k6/event-open-mixed-spike.js
```

확인값:

- `event_open_seat_lookup_unexpected_rate = 0`
- `event_open_reservation_unexpected_rate = 0`
- active 상태의 동일 좌석 중복 reservation이 없어야 한다.
- 최종 active HELD seat 수와 active reservation 수가 일치해야 한다.
- 현재 좌석 조회 경로는 수동 만료 처리를 포함하므로, 만료 group 수와 active group 수를 함께 기록한다.

### Popular Event Payment E2E Spike

인기 공연 조회부터 결제 완료까지 이어지는 사용자 여정은 외부 경량 Mock PG와 함께 측정한다. Mock PG는 테스트 전용 프로필에서만 연결한다. 실제 Toss API에는 부하를 주지 않는다.

Mock PG 실행:

```powershell
node performance/mock-pg/mock-pg-server.js
```

백엔드는 IntelliJ 실행 설정에서 active profile을 `dev,test`로 지정해 다시 실행한다. `dev`의 로컬 TLS 설정을 유지하고 `test`의 아래 설정으로 PG 주소만 Mock 서버로 덮어쓴다.

```yaml
toss:
  payments:
    base-url: http://127.0.0.1:18080
```

Mock PG health 확인:

```powershell
Invoke-RestMethod http://127.0.0.1:18080/health
```

Smoke:

```powershell
$env:COOKIE="__Host-access_token=<access token>"
$env:ORIGIN="https://localhost:3000"
$env:LOAD_PROFILE="smoke"
$env:SCHEDULE_ID="18"
$env:MIN_SEAT_ID="391"
$env:MAX_SEAT_ID="1390"
k6 run performance/k6/popular-event-payment-e2e-spike.js
```

Spike:

```powershell
.\performance\reset-load-test-seats.ps1
$env:LOAD_PROFILE="spike"
k6 run performance/k6/popular-event-payment-e2e-spike.js
```

확인값:

- `e2e_journey_duration`: 전체 사용자 여정 p95
- `e2e_event_list_duration`, `e2e_event_detail_duration`, `e2e_seat_lookup_duration`: 탐색 단계 p95
- `e2e_reservation_duration`, `e2e_payment_ready_duration`, `e2e_payment_confirm_duration`: 상태 전이 단계 p95
- `e2e_reservation_success`: 예약 성공 수
- `e2e_contention_rejected`: 인기 좌석 경합에 따른 정상 거부 수
- `e2e_payment_completed`: 결제 완료 수와 초당 완료 건수
- `e2e_payment_completion_rate`: 예약 성공 이후 결제 완료율
- `e2e_unexpected_rate`: 예상 밖 오류율

실행 후 DB MCP로 아래 정합성을 확인한다.

- 동일 좌석에 대한 중복 active reservation `0`
- reservation group의 부분 성공 `0`
- `Payment=APPROVED`, `ReservationGroup=CONFIRMED`, `Reservation=CONFIRMED`, `Seat=BOOKED` 상태 불일치 `0`

### Popular Event Payment E2E Spike Result

`PERF_LOAD_TEST_EVENT`, 회차 `18`, 인기 좌석 범위 `391~1390`, 최대 `100 VU`, `17s` 조건에서 3회 반복했다. 각 실행 전에 부하 테스트 전용 좌석과 연결 데이터를 reset했다.

| Run | 전체 여정 p95 | 좌석 조회 p95 | 예약 생성 p95 | 결제 준비 p95 | 결제 승인 p95 | 완료 결제 | 정상 경합 거부 | 예상 밖 오류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | `353.75 ms` | `120.29 ms` | `49.54 ms` | `49.13 ms` | `52.12 ms` | `1,000` | `2,666` | `0` |
| 2 | `377.00 ms` | `126.79 ms` | `59.18 ms` | `55.94 ms` | `59.77 ms` | `1,000` | `2,399` | `0` |
| 3 | `362.00 ms` | `119.15 ms` | `45.10 ms` | `45.56 ms` | `45.38 ms` | `1,000` | `2,653` | `0` |
| 중앙값 | `362.00 ms` | `120.29 ms` | `49.54 ms` | `49.13 ms` | `52.12 ms` | `1,000` | `2,653` | `0` |

3회 모두 결제 완료율은 `100%`였고 초당 결제 완료 건수는 각각 `58.74`, `58.70`, `58.72`건이었다. 중앙값은 `58.72 payments/s`다.

마지막 실행 후 DB MCP 사후 검증:

| Verification | Result |
| --- | ---: |
| 생성된 reservation group | `1,000` |
| 생성된 payment | `1,000` |
| 생성된 reservation | `1,000` |
| `BOOKED` 좌석 | `1,000` |
| 중복 active 좌석 배정 | `0` |
| 부분 성공 reservation group | `0` |
| `APPROVED / CONFIRMED / BOOKED` 상태 불일치 | `0` |

해석:

- 인기 좌석 범위를 공유하는 경합 상황에서도 성공한 `1,000`건은 모두 결제 완료까지 일관되게 전이됐다.
- 이미 선점된 좌석을 선택한 요청은 정상 경합 거부로 분리했고 예상 밖 오류는 발생하지 않았다.
- 외부 경량 Mock PG를 사용했으므로 결과는 내부 예약/결제 확정 경로의 로컬 baseline이다. 실제 Toss 네트워크 지연이나 장애 대응 성능을 의미하지 않는다.
- 대기열 기능 없이 좌석 정합성과 결제 상태 전이의 일관성을 검증했다.

### Popular Event Payment Arrival Rate Spike

기존 `ramping-vus` E2E Spike는 정합성 baseline이다. 로컬 통제 환경에서 오픈 직후 신규 사용자 유입률별 포화 구간을 관찰하려면 별도의 open model 시나리오를 사용한다.

성능 테스트 전용 사용자 `10,000`명과 사용자별 Access Token 쿠키를 생성한다. 생성된 `performance/data/perf-users.json`은 Git에 올리지 않는다.

```powershell
.\performance\prepare-perf-user-pool.ps1
```

Mock PG 실행:

```powershell
node performance/mock-pg/mock-pg-server.js
```

백엔드는 IntelliJ 실행 설정에서 active profile을 `dev,perf`로 지정해 다시 실행한다. `perf` profile은 SQL 상세 로그를 끄고 Mock PG 주소, `1h` Access Token 만료, `10m` 좌석 hold 시간, `1h` 스케줄러 주기를 사용한다.

Smoke:

```powershell
.\performance\reset-load-test-seats.ps1
$env:LOAD_PROFILE="smoke"
k6 run performance/k6/popular-event-payment-arrival-rate-spike.js
```

Arrival-rate Spike:

```powershell
.\performance\reset-load-test-seats.ps1
$env:LOAD_PROFILE="arrival"
k6 run performance/k6/popular-event-payment-arrival-rate-spike.js
```

유입 단계:

| Stage | Target | Duration |
| --- | ---: | ---: |
| Warm-up | `10 journeys/s` | `5s` |
| Low | `100 journeys/s` | `10s` |
| Normal | `300 journeys/s` | `10s` |
| High | `500 journeys/s` | `10s` |
| Peak | `1,000 journeys/s` | `10s` |
| Cool-down | `100 journeys/s` | `5s` |

확인값:

- `dropped_iterations`: 목표 유입률 중 시작하지 못한 사용자 여정
- `arrival_e2e_journey_duration`: 전체 사용자 여정 p95, p99
- `arrival_e2e_*_duration`: 단계별 p95
- `arrival_e2e_payment_completed`: 완료 결제 건수와 초당 완료 건수
- `arrival_e2e_contention_rejected`: 인기 좌석 경합에 따른 정상 거부 수
- `arrival_e2e_user_pool_reuse`: `10,000`명 이후 재방문으로 순환 사용된 사용자 수
- `arrival_e2e_unexpected_rate`: 예상 밖 오류율

실행 후 DB MCP 정합성 검증은 기존 E2E와 동일하게 수행한다.

### Popular Event Payment Arrival Rate Spike Initial Result

로컬 통제 환경에서 `dev,perf` 프로필, 외부 Mock PG, 테스트 사용자 `10,000`명 AT 풀을 사용해 최초 포화 관찰을 수행했다.

| Metric | Result |
| --- | ---: |
| 설정 유입 단계 | `10 -> 100 -> 300 -> 500 -> 1000 -> 100 journeys/s` |
| 실행 시간 | `50s` |
| 완료 iteration | `11,153` |
| dropped iteration | `5,694` |
| 최대 사용 VU | `3,000` |
| 전체 여정 p95 | `15.60s` |
| 전체 여정 p99 | `16.27s` |
| 좌석 조회 p95 | `3.16s` |
| 예약 생성 p95 | `28.63ms` |
| 결제 준비 p95 | `26.47ms` |
| 결제 승인 p95 | `38.17ms` |
| 완료 결제 | `1,000` |
| 완료 결제 처리량 | `17.13 payments/s` |
| 정상 경합 거부 | `10,153` |
| 예상 밖 오류 | `0` |
| 사용자 풀 재사용 | `1,153` |

실행 후 DB MCP 사후 검증:

| Verification | Result |
| --- | ---: |
| 생성된 reservation group | `1,000` |
| 생성된 payment | `1,000` |
| 생성된 reservation | `1,000` |
| `BOOKED` 좌석 | `1,000` |
| 중복 active 좌석 배정 | `0` |
| 부분 성공 reservation group | `0` |
| `APPROVED / CONFIRMED / BOOKED` 상태 불일치 | `0` |

해석:

- `High~Peak` 유입 구간에서 지연이 누적됐고 k6는 `maxVUs=3000` 상한에 도달해 `dropped_iterations=5,694`를 기록했다.
- 완료 결제와 정상 경합 거부를 합친 완료 iteration `11,153`건에서 예상 밖 오류는 발생하지 않았다.
- 좌석이 모두 판매된 뒤에도 공연 목록, 상세, 좌석 조회 요청은 계속 수행되므로 전체 여정 p95는 조회 경로의 지연 누적 영향을 크게 받는다.
- 이번 값은 최초 포화 관찰이다. `dropped_iterations`에는 서버 지연과 동일 장비에서 실행한 k6 부하 발생기 한계가 함께 영향을 줄 수 있으므로 운영 처리량 보장 수치로 해석하지 않는다.
- 후속 최적화 전후 비교에서는 동일한 `perf` 프로필과 동일한 arrival-rate 단계를 유지하고, 구간별 p95, dropped iteration, DB 정합성을 비교한다.

### Popular Event Payment Arrival Rate Spike After Event Cache

공연 목록/상세에 `Spring Cache + Caffeine` 로컬 캐시를 적용한 뒤 동일한 `dev,perf` 프로필, 외부 Mock PG, 테스트 사용자 `10,000`명 AT 풀, 동일 arrival-rate 단계로 재측정했다. `application-perf.yaml` 기준 캐시 TTL은 공연 목록 `60s`, 공연 상세 `5m`이다.

이후 2차 개선으로 좌석 조회의 write transaction 범위를 줄였다. `EventService.getSeats()`가 직접 write transaction을 감싸지 않고, 만료 상태 전이는 `ReservationExpirationService.expireByScheduleId()`의 write transaction에서만 처리한다. 좌석 조회 응답 매핑은 이미 알고 있는 `scheduleId`를 직접 사용한다.

| Metric | Initial | After event cache | After seat transaction split |
| --- | ---: | ---: | ---: |
| 설정 유입 단계 | `10 -> 100 -> 300 -> 500 -> 1000 -> 100 journeys/s` | `10 -> 100 -> 300 -> 500 -> 1000 -> 100 journeys/s` | `10 -> 100 -> 300 -> 500 -> 1000 -> 100 journeys/s` |
| 실행 시간 | `50s` | `50s` | `50s` |
| 완료 iteration | `11,153` | `15,573` | `15,739` |
| dropped iteration | `5,694` | `1,274` | `1,108` |
| 최대 사용 VU | `3,000` | `1,618` | `1,546` |
| 전체 여정 p95 | `15.60s` | `2.93s` | `2.66s` |
| 공연 목록 p95 | 미측정 | `982.18ms` | `924.24ms` |
| 공연 상세 p95 | 미측정 | `893.50ms` | `727.77ms` |
| 좌석 조회 p95 | `3.16s` | `1.41s` | `1.31s` |
| 예약 생성 p95 | `28.63ms` | `16.57ms` | `19.49ms` |
| 결제 준비 p95 | `26.47ms` | `15.72ms` | `18.03ms` |
| 결제 승인 p95 | `38.17ms` | `27.39ms` | `26.90ms` |
| 완료 결제 | `1,000` | `1,000` | `1,000` |
| 완료 결제 처리량 | `17.13 payments/s` | `19.43 payments/s` | `19.64 payments/s` |
| 정상 경합 거부 | `10,153` | `14,573` | `14,739` |
| 예상 밖 오류 | `0` | `0` | `0` |
| 사용자 풀 재사용 | `1,153` | `5,573` | `5,739` |

실행 후 DB MCP 사후 검증:

| Verification | Result |
| --- | ---: |
| 생성된 reservation group | `1,000` |
| 생성된 payment | `1,000` |
| 생성된 reservation | `1,000` |
| `BOOKED` 좌석 | `1,000` |
| 중복 active 좌석 배정 | `0` |
| 부분 성공 reservation group | `0` |
| `APPROVED / CONFIRMED / BOOKED` 상태 불일치 | `0` |

해석:

- 공연 목록/상세 캐시 적용 후 전체 여정 p95는 `15.60s -> 2.93s`로 개선됐고, dropped iteration은 `5,694 -> 1,274`로 줄었다.
- 좌석 조회 트랜잭션 범위 분리 후 전체 여정 p95는 `2.93s -> 2.66s`, 좌석 조회 p95는 `1.41s -> 1.31s`, dropped iteration은 `1,274 -> 1,108`로 추가 개선됐다.
- 최대 사용 VU는 `3,000 -> 1,618 -> 1,546`으로 줄어 같은 유입 조건에서 여정 적체가 완화됐다.
- 완료 iteration은 `11,153 -> 15,573`으로 늘었지만 좌석 수는 동일하게 `1,000`개이므로, 추가 완료분은 대부분 매진/경합에 따른 정상 거부로 집계됐다.
- 좌석 조회 p95도 `3.16s -> 1.41s`로 낮아졌지만 좌석 조회 자체에는 캐시를 적용하지 않았다. 이 값은 목록/상세 단계 적체 완화와 전체 부하 분산 변화가 함께 반영된 결과로 본다.
- 2차 개선 후에도 완료 결제는 `1,000`, 스크립트 기준 예상 밖 오류율은 `0%`다. `http_req_failed=25`가 기록됐지만 좌석 매진 또는 경합으로 정상 거부된 요청이 HTTP 실패 카운터에는 포함될 수 있으므로, 이 시나리오에서는 `arrival_e2e_unexpected_rate`와 DB 정합성을 우선 지표로 본다.

## Next Measurement

1. 예약 생성 write baseline은 `10 / 100`, `20 / 200`, `50 / 500 shared iterations` 구간을 각각 3회 반복해 중앙값을 기록했다.
2. Event Open Mixed Spike는 수동 만료 포함 조건과 `hold-duration=2m` 순수 비교 조건을 각각 3회 반복해 중앙값을 기록했다.
3. 결제 승인/취소 중복 요청 상태 전이는 통합 테스트로 검증하고 결과를 문서화했다.
4. Refresh Token 재발급은 조건부 update를 적용해 동일 토큰 `20`개 동시 요청 중 하나만 성공하고 나머지 `19`개는 거부하도록 검증했다. 같은 사용자의 서로 다른 Refresh Token은 각각 정상 재발급된다.
5. 결제 예외 흐름은 PG 승인 응답의 금액/통화/결제키/orderId/status 불일치, 잘못된 취소 상태 전이, `paymentKey` 누락, PG 취소 응답의 결제키/통화/status 불일치를 통합 테스트로 검증했다.
6. 최종 성능 검증은 인기 공연 조회부터 결제 완료까지 이어지는 사용자 여정 기반 E2E Spike로 수행한다.
   - 흐름: 인기 공연 조회, 회차 조회, 좌석 조회, 인기 좌석 선택, 예약 생성, 결제 준비, Mock PG 승인, 결제 승인, 최종 `BOOKED` 상태 확인
   - 대기열 기능은 추가하지 않는다. 사용자의 탐색과 선택 시간은 필요한 경우 짧은 `sleep()`으로 모델링한다.
   - 예약 실패는 인기 좌석 경합에 따른 정상 거부와 예상 밖 오류를 분리한다.
   - 기록값: 전체 여정 p95, 단계별 p95, 예약 성공률, 결제 완료율, 초당 결제 완료 건수, 예상 밖 오류율
   - DB 사후 검증: 중복 active 좌석 배정 `0`, 부분 성공 group `0`, `APPROVED / CONFIRMED / BOOKED` 상태 불일치 `0`
   - 외부 경량 Mock PG와 `dev,test` 프로필로 Smoke 및 Spike 측정을 완료했다.
   - 최대 `100 VU`, `17s`, 3회 중앙값 기준 전체 여정 p95는 `362.00 ms`, 결제 완료는 `1,000`, 완료율은 `100%`, 초당 결제 완료는 `58.72 payments/s`, 예상 밖 오류는 `0`이다.
   - DB 사후 검증에서 중복 active 좌석 배정, 부분 성공 group, `APPROVED / CONFIRMED / BOOKED` 상태 불일치는 모두 `0`이다.
7. 운영 유사 성능 주장이 필요해지는 시점에는 SQL 상세 로그를 낮춘 별도 profile과 별도 부하 발생 환경을 구성한다.
   - 로컬 통제 환경용 `perf` profile과 사용자 `10,000`명의 AT 풀을 사용하는 `ramping-arrival-rate` E2E 시나리오를 추가했다.
   - `dev,perf` 프로필 최초 측정에서 완료 iteration `11,153`, dropped iteration `5,694`, 전체 여정 p95 `15.60s`, 완료 결제 `1,000`, 예상 밖 오류 `0`을 기록했다.
   - DB 사후 검증에서 중복 active 좌석 배정, 부분 성공 group, `APPROVED / CONFIRMED / BOOKED` 상태 불일치는 모두 `0`이다.
8. 공연 목록/상세 캐시 적용 전 선행 정리로 `displayStatus`를 백엔드 응답에서 제거하고 프론트 서버 컴포넌트 데이터 조합 단계에서 계산하도록 전환했다.
   - 예약 생성은 `bookingOpenAt` 이전 요청을 좌석 선점 전에 거부한다.
   - 공연 목록/상세는 `Spring Cache + Caffeine` 로컬 read-through 캐시로 적용한다.
   - 동일한 E2E arrival-rate 조건에서 캐시 적용 후 완료 iteration `15,573`, dropped iteration `1,274`, 전체 여정 p95 `2.93s`, 완료 결제 `1,000`, 예상 밖 오류 `0`을 기록했다.
   - 좌석 조회 트랜잭션 범위 분리 후 완료 iteration `15,739`, dropped iteration `1,108`, 전체 여정 p95 `2.66s`, 좌석 조회 p95 `1.31s`, 완료 결제 `1,000`, 예상 밖 오류 `0`을 기록했다.
   - DB 사후 검증에서 중복 active 좌석 배정, 부분 성공 group, `APPROVED / CONFIRMED / BOOKED` 상태 불일치는 모두 `0`이다.

## References

- Grafana k6, test types: https://grafana.com/docs/k6/latest/testing-guides/test-types/
- Hibernate ORM User Guide, fetching and N+1: https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html
- Spring Data JPA, Locking: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- Spring Data JPA, Modifying Queries: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries
- PostgreSQL, Explicit Locking: https://www.postgresql.org/docs/17/explicit-locking.html
- Toss Payments, payment flow: https://docs.tosspayments.com/guides/v2/get-started/payment-flow
- Toss Payments, idempotency: https://docs.tosspayments.com/blog/what-is-idempotency
