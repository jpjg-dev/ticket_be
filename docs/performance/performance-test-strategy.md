# 성능 테스트 전략

## 문서 목적

이 문서는 TicketLedger 백엔드가 어떤 기준으로 성능 테스트 대상을 고르고, 어떤 수치를 포트폴리오 근거로 남겼는지 정리합니다.

이 프로젝트의 성능 테스트는 운영 최대 처리량을 보장하기 위한 벤치마크가 아닙니다. 같은 로컬 통제 조건에서 개선 전후를 비교하고, 부하 상황에서도 예약/결제 정합성이 깨지지 않는지 확인하기 위한 검증입니다.

## 바로가기

- [테스트 원칙](#테스트-원칙)
- [측정 환경과 한계](#측정-환경과-한계)
- [검증 시나리오](#검증-시나리오)
- [핵심 지표](#핵심-지표)
- [인기 공연 E2E 결과](#인기-공연-e2e-결과)
- [병목 가설 검증](#병목-가설-검증)
- [마이페이지 N+1 검증](#마이페이지-n1-검증)
- [정합성 검증](#정합성-검증)
- [실행 명령](#실행-명령)
- [참고 자료](#참고-자료)

## 테스트 원칙

| 원칙 | 설명 |
| --- | --- |
| 모든 API를 무작정 부하 테스트하지 않습니다. | 사용자 트래픽이 집중되는 조회 구간, N+1이 확인된 조회 구간, 상태 정합성을 깨뜨릴 수 있는 전이 구간만 선택했습니다. |
| 정상 거부와 예상 밖 오류를 분리합니다. | 인기 공연에서는 매진/경합 거부가 정상 결과일 수 있으므로 HTTP 실패율만 보지 않았습니다. |
| p95만 보지 않습니다. | 완료 결제 수, 중복 좌석 배정, 부분 성공 group, 상태 불일치를 함께 확인했습니다. |
| 같은 조건에서 전후를 비교합니다. | 로컬 환경 절대 수치가 아니라 동일 조건의 상대 개선을 근거로 사용했습니다. |
| 좌석 상태는 캐시하지 않습니다. | 성능 최적화보다 예약/결제/만료 정합성을 우선했습니다. |

## 측정 환경과 한계

| 항목 | 내용 |
| --- | --- |
| 백엔드 | Spring Boot `dev,perf` 프로필 |
| DB | 로컬 PostgreSQL |
| 부하 발생기 | k6 |
| PG | 로컬 Mock PG |
| 사용자 풀 | 성능 테스트 전용 사용자 `10,000`명과 Access Token Cookie |
| 좌석 데이터 | `PERF_LOAD_TEST_EVENT`, `scheduleId=18`, 좌석 `391~1390` |

측정 한계:

- k6, 애플리케이션 서버, PostgreSQL을 같은 로컬 장비에서 동시에 실행했습니다.
- 부하 발생기와 애플리케이션이 CPU, 네트워크, 디스크 자원을 공유하므로 절대 수치를 운영 처리량으로 해석하지 않습니다.
- `ramping-arrival-rate`는 응답 완료와 독립적으로 iteration을 시작하는 open-model 실행 방식입니다.
- 따라서 대표 수치는 운영 최대 RPS 보장이 아니라 개선 전후 상대 비교와 정합성 유지 여부를 보여주는 값입니다.

## 검증 시나리오

| 구분 | 대상 | 목적 | 남긴 근거 |
| --- | --- | --- | --- |
| 좌석 조회 | `GET /event/schedules/{scheduleId}/seats` | 조회 전 만료 처리 비용과 좌석 응답 지연 확인 | 일반 조회 p95, 만료 첫 호출 비용, 동시 만료 처리 결과 |
| 마이페이지 | `GET /users/{userId}` | 예매 이력 증가 시 N+1 확인 | group 수별 p95, 처리량, 개선 전후 비교 |
| 좌석 선점 | `POST /reservations` | 동일/겹치는 좌석 동시 요청 방어 | 성공 group 수, 부분 성공 없음, 중복 active 좌석 없음 |
| 결제 승인/취소 | `POST /payments/confirm`, `POST /payments/{id}/cancel` | 중복 요청에서 PG 호출과 내부 상태 전이 1회 보장 | PG Mock 호출 1회, 최종 상태 일관성 |
| Refresh Token | `POST /auth/reissue` | 동일 토큰 동시 재발급 방어 | 20개 동시 요청 중 1개만 성공 |
| 인기 공연 E2E | 목록, 상세, 좌석, 예약, 결제 전체 여정 | 오픈 직후 포화 상황에서 조회 성능과 정합성 확인 | RPS, p95, dropped iteration, 완료 결제, DB 사후 검증 |

## 핵심 지표

| 지표 | 해석 |
| --- | --- |
| `arrival_e2e_journey_duration` | 전체 사용자 여정의 p95입니다. |
| `arrival_e2e_seat_lookup_duration` | 좌석 조회 단계의 p95입니다. |
| `arrival_e2e_payment_completed` | 실제 결제 완료 처리량입니다. |
| `arrival_e2e_unexpected_rate` | 예상 밖 오류율입니다. |
| `dropped_iterations` | open-model 부하에서 예정된 iteration을 시작하지 못한 횟수입니다. |
| DB 사후 검증 | 중복 좌석, 부분 성공, 상태 불일치 여부를 확인합니다. |

## 인기 공연 E2E 결과

인기 공연 E2E는 공연 목록, 상세, 좌석 조회, 예약 생성, 결제 준비, Mock PG 승인, 결제 확정까지 이어지는 전체 여정으로 측정했습니다.

| 단계 | 핵심 변경 | 전체 여정 RPS | 전체 여정 p95 | dropped iteration | 완료 결제 | 예상 밖 오류 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 초기 관찰 | 캐시/SoldOut 정책 전 | `223.06 RPS` | `15.60s` | `5,694` | `1,000` | `0` |
| 공연 캐시 | 목록/상세 Caffeine 캐시 | `311.46 RPS` | `2.93s` | `1,274` | `1,000` | `0` |
| 트랜잭션 분리 | 만료 처리 쓰기 트랜잭션과 좌석 조회 분리 | `314.78 RPS` | `2.66s` | `1,108` | `1,000` | `0` |
| SoldOut 정책 | 가용 상태 기반 좌석 목록 조회 생략 | `336.94 RPS` | `244ms` | `0` | `1,000` | `0` |

RPS 개선율:

| 단계 | 완료 iteration | 전체 여정 RPS | 초기 대비 | 직전 대비 | 결제 완료 RPS | 결제 완료 RPS 초기 대비 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Initial | `11,153` | `223.06 RPS` | - | - | `17.13 RPS` | - |
| After event cache | `15,573` | `311.46 RPS` | `+39.63%` | `+39.63%` | `19.43 RPS` | `+13.43%` |
| After seat transaction split | `15,739` | `314.78 RPS` | `+41.12%` | `+1.07%` | `19.64 RPS` | `+14.65%` |
| After SoldOut policy | `16,847` | `336.94 RPS` | `+51.05%` | `+7.04%` | `19.92 RPS` | `+16.29%` |

해석:

- 전체 여정 RPS는 완료 결제뿐 아니라 정상 경합/매진 거부까지 포함한 시스템 처리량입니다.
- 결제 완료 RPS는 좌석 수 `1,000`개에 의해 상한이 생기므로 전체 여정 RPS와 분리해서 봅니다.
- SoldOut 정책 적용 후 네트워크 수신량은 `1.6GB -> 206MB`로 감소했습니다.
- 이 감소는 핵심 목표가 아니라, 매진 확정 이후 반복 좌석 조회를 차단하면서 얻은 부가 효과입니다.

## 병목 가설 검증

| 가설 | 실험 | 결과 | 판단 |
| --- | --- | --- | --- |
| 예약 만료 처리 트랜잭션이 병목입니다. | 만료 처리 제거/트랜잭션 범위 분리 후 재측정했습니다. | 일부 개선은 있었지만 전체 병목을 단독으로 설명하지 못했습니다. | 단독 원인으로 보지 않았습니다. |
| DB 커넥션 풀이 부족합니다. | Hikari pool 크기 조정 후 재측정했습니다. | p95와 완료 처리량 차이가 크지 않았습니다. | 단독 원인으로 보지 않았습니다. |
| 좌석 조회 응답 크기가 병목입니다. | SoldOut 정책 적용 후 재측정했습니다. | 응답 크기 감소 효과는 있었지만, 핵심은 payload 축소가 아니라 매진 확정 이후 반복 좌석 조회를 차단한 점입니다. | 보조 효과로 정리했습니다. |
| 매진 이후에도 좌석 목록 조회가 반복됩니다. | 회차별 가용 상태를 먼저 집계하고, `soldOut=true`이면 좌석 목록 조회 없이 빈 `seats`를 반환했습니다. | 완료 결제 `1,000`, 중복 좌석 `0`, 부분 성공 `0`, 상태 불일치 `0`을 유지했습니다. | SoldOut 정책으로 채택했습니다. |

## 마이페이지 N+1 검증

마이페이지 조회는 예매 이력이 늘어날수록 DTO 변환 과정에서 LAZY 연관 조회와 반복 조회 비용이 커지는 구조였습니다.

| 조건 | 개선 전 p95 | 개선 후 p95 | 개선 전 처리량 | 개선 후 처리량 |
| --- | ---: | ---: | ---: | ---: |
| group `100`, `10 VU / 30s` | `202.75ms` | `17.52ms` | `60.73 req/s` | `663.46 req/s` |

적용한 개선:

- 이미 조회한 reservation 목록을 groupId 기준 `Map`으로 재사용했습니다.
- reservation/payment 조회에 fetch join을 적용했습니다.
- 인덱스 후보 `reservations(reservation_group_id)`는 `EXPLAIN ANALYZE`에서 사용되지 않아 Flyway migration에 반영하지 않았습니다.

왜 이렇게 개선했는가:

- 마이페이지 응답은 예매 그룹 목록과 결제 목록 양쪽에서 같은 좌석 정보를 보여줘야 합니다.
- `Reservation` 목록은 `ReservationGroup`, `Seat`, `Schedule`, `Event` 정보가 필요하므로 fetch join으로 DTO 변환 중 발생하는 지연 로딩을 줄였습니다.
- `Payment` 목록은 결제 상태와 금액뿐 아니라 어떤 예매 그룹의 결제인지 알아야 하므로 `ReservationGroup`을 함께 조회했습니다.
- 결제 항목을 만들 때마다 다시 reservation을 조회하면 group 수만큼 반복 쿼리가 생기므로, 이미 조회한 reservation 목록을 groupId 기준 `Map`으로 묶어 재사용했습니다.
- 결과 개수를 줄인 최적화가 아니라, 같은 응답 구조를 유지하면서 반복 조회와 중복 DTO 조립 비용을 줄인 최적화입니다.

## 정합성 검증

부하 테스트는 성능 수치만으로 끝내지 않고 DB 사후 검증을 함께 수행했습니다.

| 항목 | 결과 |
| --- | ---: |
| 완료 결제 | `1,000` |
| `BOOKED` 좌석 | `1,000` |
| 중복 active 좌석 배정 | `0` |
| 부분 성공 group | `0` |
| `APPROVED / CONFIRMED / BOOKED` 상태 불일치 | `0` |

추가 검증:

- 동일 좌석 묶음 동시 요청은 하나의 `ReservationGroup`만 성공했습니다.
- 겹치는 좌석 묶음 동시 요청에서도 실패 요청은 부분 reservation을 남기지 않았습니다.
- 동일 `orderId` 결제 승인 동시 요청은 PG confirm Mock을 1회만 호출했습니다.
- 동일 `paymentId` 결제 취소 동시 요청은 PG cancel Mock을 1회만 호출했습니다.
- 동일 Refresh Token 동시 재발급 요청 `20`개 중 하나만 성공했습니다.

## 실행 명령

성능 테스트 전제:

- 백엔드는 `dev,perf` 프로필로 실행합니다.
- Mock PG는 `127.0.0.1:18080`에서 실행합니다.
- 성능 테스트 사용자 풀은 `performance/data/perf-users.json`에 준비합니다.

```powershell
node performance/mock-pg/mock-pg-server.js
```

```powershell
.\performance\reset-load-test-seats.ps1
```

```powershell
$env:LOAD_PROFILE="arrival"
k6 run performance/k6/popular-event-payment-arrival-rate-spike.js
```

로컬 HTTPS 인증서를 사용하는 경우 k6 실행 시 인증서 검증을 생략하는 옵션을 사용할 수 있습니다.

## 참고 자료

- Grafana k6 테스트 유형: https://grafana.com/docs/k6/latest/testing-guides/test-types/
- Spring Data JPA Locking: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- Spring Data JPA Modifying Queries: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries
- Hibernate ORM User Guide, fetching and N+1: https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html
- PostgreSQL Explicit Locking: https://www.postgresql.org/docs/17/explicit-locking.html
- Toss Payments 결제 흐름: https://docs.tosspayments.com/guides/v2/get-started/payment-flow
- Toss Payments 멱등성 설명: https://docs.tosspayments.com/blog/what-is-idempotency
