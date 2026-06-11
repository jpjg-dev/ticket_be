# TicketLedger Backend

TicketLedger 백엔드는 공연 예매 과정에서 발생하는 좌석 선점, 예약 만료, 결제 승인/취소, 매진 조회 병목을 상태 전이와 트랜잭션 기준으로 관리하는 Spring Boot API 서버다.

## 핵심 요약

- 좌석, 예매 그룹, 결제 상태를 분리하고 상태 전이 규칙으로 정합성을 관리한다.
- 다중 좌석 예매는 `ReservationGroup` 단위로 묶어 부분 성공을 만들지 않는다.
- 좌석 선점과 결제/만료 경합 구간은 DB lock과 트랜잭션으로 직렬화한다.
- 공연 목록/상세는 Caffeine local cache로 반복 조회 비용을 줄인다.
- 좌석 상태와 매진 여부는 캐시하지 않고 DB 상태 기반 availability로 계산한다.
- 매진 이후 `/seats`는 좌석 목록 payload를 내려주지 않는 fast-path를 사용한다.
- k6 E2E 테스트는 응답 시간뿐 아니라 완료 결제 수, 예상 밖 오류, DB 정합성을 함께 검증한다.

## 기술 스택

| 영역 | 사용 기술 |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.5.13, Spring Web |
| Persistence | Spring Data JPA, PostgreSQL, Flyway |
| Security | Spring Security, JWT, HttpOnly Cookie |
| Cache | Spring Cache, Caffeine |
| API Docs | springdoc-openapi, Swagger UI |
| Observability | Spring Actuator, Micrometer, Prometheus registry |
| Test | JUnit 5, Spring Boot Test, Spring Security Test, Mockito |
| Performance | k6, Mock PG |

<details>
<summary>도메인 모델과 상태 전이</summary>

### 핵심 모델

- `Event`: 공연 정보
- `Schedule`: 공연 회차
- `Seat`: 회차별 좌석과 좌석 상태
- `ReservationGroup`: 사용자의 예매 묶음
- `Reservation`: 좌석별 예매 row
- `Payment`: 예매 그룹 기준 결제
- `User`: 인증 사용자

### 상태 체계

| 대상 | 상태 |
| --- | --- |
| Seat | `AVAILABLE`, `HELD`, `BOOKED` |
| ReservationGroup | `PENDING`, `CONFIRMED`, `CANCELED`, `EXPIRED` |
| Reservation | `PENDING`, `CONFIRMED`, `CANCELED`, `EXPIRED` |
| Payment | `READY`, `APPROVED`, `FAILED`, `CANCELED` |

### 주요 전이

```text
좌석 선점:
AVAILABLE -> HELD

결제 승인:
Payment READY -> APPROVED
ReservationGroup PENDING -> CONFIRMED
Reservation PENDING -> CONFIRMED
Seat HELD -> BOOKED

예약 만료:
ReservationGroup PENDING -> EXPIRED
Reservation PENDING -> EXPIRED
Seat HELD -> AVAILABLE
Payment READY -> FAILED

결제 취소:
Payment APPROVED -> CANCELED
ReservationGroup CONFIRMED -> CANCELED
Reservation CONFIRMED -> CANCELED
Seat BOOKED -> AVAILABLE
```

상세 상태 정책은 `docs/state-design.md`에 정리되어 있다.

</details>

<details>
<summary>주요 API 흐름</summary>

### 인증

```text
POST /api/v1/auth/login
-> email/password 검증
-> Access Token + Refresh Token 발급
-> HttpOnly Cookie 반환
```

```text
POST /api/v1/auth/reissue
-> Refresh Token 조건부 update
-> 한 번만 소비되도록 재발급
```

```text
GET /api/v1/users/me
-> 현재 로그인 사용자 정보 반환
```

### 공연/좌석

```text
GET /api/v1/event
GET /api/v1/event/{eventId}
GET /api/v1/event/schedules/availability?scheduleIds=...
GET /api/v1/event/schedules/{scheduleId}/seats
```

`/seats`는 회차별 만료 처리를 먼저 수행한다. 이후 availability 기준으로 매진이면 좌석 목록 projection 조회 없이 `{ scheduleId, soldOut: true, seats: [] }`를 반환한다.

### 예약/결제

```text
POST /api/v1/reservations
POST /api/v1/payments/ready
POST /api/v1/payments/confirm
POST /api/v1/payments/{paymentId}/cancel
GET  /api/v1/payments/{paymentId}/status
```

결제 승인과 취소는 `Payment`, `ReservationGroup`, `Reservation`, `Seat` 상태를 하나의 트랜잭션에서 함께 변경한다.

</details>

<details>
<summary>정합성 보장 전략</summary>

### 좌석 선점

- 좌석 ID를 distinct 처리하고 정렬한 뒤 lock을 획득한다.
- 선택 좌석 중 하나라도 `AVAILABLE`이 아니면 전체 요청을 실패시킨다.
- 다중 좌석 예매에서 일부 좌석만 성공하는 상태를 만들지 않는다.

### 결제와 만료 경합

- 결제 승인, 취소, 만료가 같은 예매 그룹을 동시에 처리할 수 있다.
- 이 구간은 `Payment`와 `ReservationGroup` 기준 lock과 상태 재확인으로 직렬화한다.
- 먼저 확정된 상태 전이 이후 뒤늦게 들어온 흐름은 현재 상태를 다시 보고 거부한다.

### DB 방어선

- PostgreSQL row lock과 unique/FK 제약을 보조 방어선으로 사용한다.
- 운영 profile은 Hibernate `validate`와 Flyway schema를 기준으로 스키마 불일치를 감지한다.
- 통합 테스트는 별도 test DB에서 실행해 운영/개발 데이터와 분리한다.

</details>

<details>
<summary>성능 개선 실험</summary>

인기 공연 오픈 직후 시나리오는 공연 목록, 상세, 좌석 조회, 예약 생성, 결제 준비, Mock PG 승인, 결제 확정까지 이어지는 E2E 여정으로 측정했다.

| 단계 | 핵심 변경 | 전체 여정 p95 | 좌석 조회 p95 | dropped iteration | 네트워크 수신량 |
| --- | --- | ---: | ---: | ---: | ---: |
| 초기 관찰 | 캐시/fast-path 없음 | `15.60s` | `3.16s` | `5,694` | 미측정 |
| 공연 캐시 | 목록/상세 Caffeine 캐시 | `2.93s` | `1.41s` | `1,274` | 미측정 |
| 트랜잭션 분리 | 만료 처리 write tx와 좌석 조회 분리 | `2.66s` | `1.31s` | `1,108` | `1.6GB` |
| 매진 fast-path | 매진 후 좌석 목록 payload 생략 | `244ms` | `11.58ms` | `0` | `206MB` |

중요한 판단은 좌석 상태 자체를 캐시하지 않았다는 점이다. 좌석 정합성은 계속 DB를 기준으로 유지하고, 매진 이후 반복 전송되던 1,000석 응답 payload만 제거했다.

상세 결과는 `docs/performance-e2e-optimization-summary.md`와 `docs/performance-test-strategy.md`에 정리되어 있다.

</details>

<details>
<summary>실행과 테스트</summary>

### 로컬 테스트

```powershell
.\gradlew.bat test
```

특정 테스트만 실행할 때:

```powershell
.\gradlew.bat test --tests "com.jipi.ticket_ledger.event.application.EventServiceTest"
```

### 성능 테스트 전제

- 성능 테스트는 `dev,perf` profile을 기준으로 실행한다.
- Mock PG는 `127.0.0.1:18080`에서 실행한다.
- 성능 테스트 사용자 풀은 `performance/data/perf-users.json`에 준비한다.

```powershell
node performance/mock-pg/mock-pg-server.js
.\performance\reset-load-test-seats.ps1
$env:LOAD_PROFILE="arrival"
k6 run performance/k6/popular-event-payment-arrival-rate-spike.js
```

### 주요 확인값

- `arrival_e2e_journey_duration`
- `arrival_e2e_seat_lookup_duration`
- `arrival_e2e_payment_completed`
- `arrival_e2e_unexpected_rate`
- `dropped_iterations`
- DB 사후 정합성: 중복 active 좌석, 부분 성공 group, 상태 불일치

</details>

<details>
<summary>환경 설정과 운영 메모</summary>

### 주요 profile

- `dev`: 로컬 개발
- `test`: 테스트 DB와 테스트 설정
- `perf`: 성능 테스트용 Mock PG, 긴 AT 만료, SQL 상세 로그 축소
- `prod`: 운영 배포

### 외부 설정

- PostgreSQL datasource
- JWT secret 및 만료 시간
- Toss Payments secret/base-url
- CSRF 허용 origin
- Caffeine cache TTL/size

### 운영 주의

- `/actuator/**` 노출은 성능 측정 편의를 위한 임시 설정일 수 있으므로 배포 전 정책을 다시 확인한다.
- 실제 Toss Payments에는 부하 테스트를 걸지 않고 Mock PG로 내부 흐름만 검증한다.
- k6 로컬 수치는 운영 처리량 보장이 아니라 같은 조건에서의 개선 전후 비교 수치로 해석한다.

</details>

## 관련 문서

- `docs/change-history.md`
- `docs/state-design.md`
- `docs/auth-flow-readme.md`
- `docs/performance-test-strategy.md`
- `docs/performance-e2e-optimization-summary.md`
