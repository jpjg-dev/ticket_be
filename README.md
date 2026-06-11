# TicketLedger Backend

TicketLedger 백엔드는 공연 예매 과정에서 발생하는 좌석 선점, 예약 만료, 결제 승인/취소, 매진 조회 병목을 상태 전이와 트랜잭션 기준으로 관리하는 Spring Boot API 서버다.

## 바로가기

- [운영 환경 로그인 정보](#운영-환경-로그인-정보)
- [핵심 문제 정의](#핵심-문제-정의)
- [핵심 해결 전략](#핵심-해결-전략)
- [도메인 모델과 상태 전이](#도메인-모델과-상태-전이)
- [주요 API 흐름](#주요-api-흐름)
- [정합성 보장 전략](#정합성-보장-전략)
- [성능 개선 실험](#성능-개선-실험)
- [테스트와 검증](#테스트와-검증)
- [기술 스택](#기술-스택)
- [실행 방법](#실행-방법)
- [환경 설정과 운영 메모](#환경-설정과-운영-메모)
- [상세 문서 바로가기](#상세-문서-바로가기)

## 운영 환경 로그인 정보

포트폴리오 확인용 운영 계정이다.

| 구분 | 이메일 | 비밀번호 |
| --- | --- | --- |
| 관리자 | `admin@admin.com` | `a123456789` |
| 일반 사용자 | `user@user.com` | `a123456789` |

## 핵심 문제 정의

공연 예매 시스템은 단순 CRUD보다 상태 정합성이 중요하다. 사용자는 같은 좌석을 동시에 선택할 수 있고, 결제 승인과 예약 만료가 같은 예매 건을 동시에 처리할 수 있으며, PG 응답이 지연되거나 끊긴 뒤에도 내부 상태는 하나의 결과로 수렴해야 한다.

이 프로젝트에서 집중한 백엔드 문제는 다음과 같다.

| 문제 | 위험 |
| --- | --- |
| 동일 좌석 동시 선점 | 하나의 좌석이 여러 사용자에게 배정될 수 있음 |
| 다중 좌석 예매 | 일부 좌석만 선점되는 부분 성공이 생길 수 있음 |
| 예약 만료와 결제 승인 경합 | 결제는 승인됐지만 좌석은 만료되는 상태 불일치 가능 |
| 결제 중복 요청 | 같은 예매 그룹에 결제가 여러 번 생성될 수 있음 |
| PG 응답 불확실성 | 외부 결제 결과와 내부 결제 상태가 어긋날 수 있음 |
| 매진 이후 반복 조회 | 1,000석 payload 반복 전송으로 전체 여정 지연이 커짐 |

## 핵심 해결 전략

- 예매 단위를 개별 좌석이 아니라 `ReservationGroup`으로 묶어 다중 좌석 예매와 결제를 하나의 흐름으로 관리한다.
- 좌석 선점은 좌석 ID를 정렬한 뒤 DB row lock을 획득해 락 순서를 고정한다.
- 예약 생성, 결제 승인, 결제 취소, 예약 만료는 상태 전이 규칙을 통과해야만 처리한다.
- 결제 승인과 만료 경합 구간은 `Payment`와 `ReservationGroup` 기준 lock과 상태 재확인으로 직렬화한다.
- Toss Payments 응답이 불확실하면 `paymentKey` 조회로 실제 PG 상태를 재확인한 뒤 내부 상태를 확정한다.
- 공연 목록/상세는 카탈로그 캐시로 최적화하되, 좌석 상태와 매진 여부는 캐시하지 않고 DB 상태 기반 availability로 계산한다.
- 매진 이후 `/seats`는 좌석 목록 projection 조회와 JSON 직렬화를 생략해 반복 조회 병목을 줄인다.

<a id="도메인-모델과-상태-전이"></a>
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

상세 상태 정책은 [docs/state-design.md](docs/state-design.md)에 정리되어 있다.

</details>

<a id="주요-api-흐름"></a>
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

인증 흐름과 Cookie 기반 토큰 정책은 [docs/auth-flow-readme.md](docs/auth-flow-readme.md)에 정리되어 있다.

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

<a id="정합성-보장-전략"></a>
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

<a id="성능-개선-실험"></a>
<details>
<summary>성능 개선 실험</summary>

인기 공연 오픈 직후 시나리오는 공연 목록, 상세, 좌석 조회, 예약 생성, 결제 준비, Mock PG 승인, 결제 확정까지 이어지는 E2E 여정으로 측정했다.

| 단계 | 핵심 변경 | 전체 여정 TPS | 초기 대비 | 전체 여정 p95 | 좌석 조회 p95 | dropped iteration | 네트워크 수신량 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 초기 관찰 | 캐시/fast-path 없음 | `223.06 journeys/s` | - | `15.60s` | `3.16s` | `5,694` | 미측정 |
| 공연 캐시 | 목록/상세 Caffeine 캐시 | `311.46 journeys/s` | `+39.63%` | `2.93s` | `1.41s` | `1,274` | 미측정 |
| 트랜잭션 분리 | 만료 처리 write tx와 좌석 조회 분리 | `314.78 journeys/s` | `+41.12%` | `2.66s` | `1.31s` | `1,108` | `1.6GB` |
| 매진 fast-path | 매진 후 좌석 목록 payload 생략 | `336.94 journeys/s` | `+51.05%` | `244ms` | `11.58ms` | `0` | `206MB` |

중요한 판단은 좌석 상태 자체를 캐시하지 않았다는 점이다. 좌석 정합성은 계속 DB를 기준으로 유지하고, 매진 이후 반복 전송되던 1,000석 응답 payload만 제거했다.

완료 결제 TPS는 `17.13 -> 19.92 payments/s`로 `+16.29%` 개선됐다. 단, 결제 TPS는 1,000석 매진 이후 좌석 수에 의해 상한이 생기므로, 전체 여정 TPS와 함께 해석한다.

상세 실험 과정과 해석은 [docs/performance-e2e-optimization-summary.md](docs/performance-e2e-optimization-summary.md), 전체 성능 테스트 전략은 [docs/performance-test-strategy.md](docs/performance-test-strategy.md)에 정리되어 있다.

</details>

<a id="테스트와-검증"></a>
<details>
<summary>테스트와 검증</summary>

| 검증 대상 | 확인한 내용 |
| --- | --- |
| 예약 생성 동시성 | 동일 좌석 또는 겹치는 좌석 묶음에서 하나의 group만 성공 |
| 다중 좌석 예매 | 선택 좌석 전체가 가능할 때만 group 생성, 부분 성공 없음 |
| 예약 만료 | 만료 group, reservation, seat, payment 상태 전이 일관성 |
| 결제 승인 | `Payment`, `ReservationGroup`, `Reservation`, `Seat` 동시 확정 |
| 결제 취소 | 결제 취소 후 예매/좌석 상태 복구 |
| 결제 승인과 만료 경합 | 먼저 확정된 흐름만 최종 상태로 남음 |
| Refresh Token 재발급 | 동일 refresh token 동시 요청 중 하나만 성공 |
| 성능 E2E | 완료 결제, 예상 밖 오류, dropped iteration, DB 정합성 동시 확인 |

성능 테스트 후 DB 사후 검증에서는 `BOOKED` 좌석 `1,000`, reservation group `1,000`, payment `1,000`, 중복 active 좌석 배정 `0`, 부분 성공 group `0`, 상태 불일치 `0`을 확인했다.

</details>

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

<a id="실행-방법"></a>
<details>
<summary>실행 방법</summary>

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

<a id="환경-설정과-운영-메모"></a>
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

## 상세 문서 바로가기

| 문서 | 내용 |
| --- | --- |
| [docs/state-design.md](docs/state-design.md) | 좌석, 예매, 결제 상태 전이 정책 |
| [docs/auth-flow-readme.md](docs/auth-flow-readme.md) | 로그인, 재발급, HttpOnly Cookie 인증 흐름 |
| [docs/performance-e2e-optimization-summary.md](docs/performance-e2e-optimization-summary.md) | 인기 공연 E2E 성능 개선 과정과 최종 지표 |
| [docs/performance-test-strategy.md](docs/performance-test-strategy.md) | k6 성능 테스트 시나리오와 측정 기준 |
| [docs/change-history.md](docs/change-history.md) | 주요 변경 이력과 설계 판단 기록 |
