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

### Initial Observation

- `10 -> 30 VU`에서는 처리량이 약 `32.3%` 증가했지만 p95도 `4.83 ms -> 14.37 ms`로 상승했다.
- `30 -> 50 VU`에서는 처리량 증가가 약 `1.8%`에 그친 반면 p95는 `14.37 ms -> 25.24 ms`로 상승했다.
- 실패는 발생하지 않았지만, 현재 로컬 dev pressure 조건에서 `30~50 VU` 구간은 처리량 증가보다 지연 증가가 커지는 포화 접근 구간으로 본다.
- 상세 SQL/bind 로그가 활성화된 `dev` profile 결과이므로 절대 처리 능력으로 주장하지 않고, 이후 동일 환경에서의 변경 전후 비교 baseline으로 사용한다.
- `expiration-first-hit`는 첫 조회 한 번에서 만료 group과 좌석 복구가 수행됐고, HTTP 체크 및 사후 DB 상태 확인까지 통과했다. 일반 조회 `10 VU` p95와 직접 비교할 수는 없지만, 만료 복구 경로의 기준값은 `18.57 ms`로 기록한다.
- `expiration-concurrency`는 최종 DB 상태가 `group/reservation=EXPIRED`, `seat=AVAILABLE`, `payment=FAILED`로 정리됐으나, 동일 만료 대상을 동시에 처리한 `20`개 조회 중 `8`개가 `Seat.release()` 상태 검증 예외로 실패했다. 만료 처리 대상 선정과 상태 전이를 직렬화하거나 조건부 처리하는 보강이 필요하다.
- `Payment` row의 `PESSIMISTIC_WRITE` 락과 락 이후 group 상태 재확인을 적용한 재실행에서는 동일 조건의 `20`개 조회가 모두 성공했고 최종 상태도 일관됐다. p95는 `56.42 ms -> 74.81 ms`로 상승했으므로, 오류 제거의 대가로 경합 요청의 대기 시간이 증가하는 트레이드오프를 확인했다.

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

### Improvement Candidates To Compare

1. 이미 조회한 reservation 목록을 `reservationGroupId` 기준 `Map`으로 묶고 payment DTO 변환에서도 재사용한다.
2. 마이페이지 전용 조회에서 필요한 연관 데이터는 `fetch join` 또는 DTO projection으로 한 번에 조회한다.
3. bulk 조회 적용 후에도 스캔 비용이 크면 `reservations(reservation_group_id)` 인덱스를 측정 기반으로 결정한다.

## Phase 3 And 4: Consistency Evidence

예약 선점과 결제 상태 전이는 단순 응답 시간보다 정합성이 우선이다.

| Flow | Scenario | Pass condition |
| --- | --- | --- |
| 예약 선점 | 여러 사용자가 겹치는 좌석 묶음을 동시에 예약 | 성공 group 하나만 존재하고 부분 성공이 없음 |
| 결제 승인 | 같은 승인 요청이 중복 도착 | 결제 및 예약 확정 상태가 중복 전이되지 않음 |
| 결제 취소 | 같은 취소 요청이 중복 도착 | 취소 결과가 정책대로 멱등 처리되고 좌석이 한 번만 복구됨 |

결제 외부 PG 자체의 처리량을 측정하려고 대량 승인 요청을 보내지 않는다. 이 프로젝트에서는 내부 상태 전이와 중복 요청 방어를 검증 대상으로 삼는다.

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

## Next Measurement

1. 별도 만료 데이터를 준비해 `expiration-first-hit`와 `expiration-concurrency` 결과를 수집한다.
2. 마이페이지의 group 수를 달리한 시나리오를 추가해 N+1 제거 및 `reservations(reservation_group_id)` 인덱스 검증 전 기준값을 수집한다.
3. 운영 유사 성능 주장이 필요해지는 시점에는 SQL 상세 로그를 낮춘 별도 profile과 실제 사용자 간격/도착률 시나리오를 구성한다.

## References

- Grafana k6, test types: https://grafana.com/docs/k6/latest/testing-guides/test-types/
- Hibernate ORM User Guide, fetching and N+1: https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html
- Toss Payments, payment flow: https://docs.tosspayments.com/guides/v2/get-started/payment-flow
- Toss Payments, idempotency: https://docs.tosspayments.com/blog/what-is-idempotency
