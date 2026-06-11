# TicketLedger 백엔드

TicketLedger 백엔드는 **인기 공연 오픈 시점의 예약·결제 정합성과 조회 성능을 함께 다루는 티켓 예매 API 서버**입니다. 좌석 선점, 예약 만료, 결제 확정이 서로 어긋나지 않도록 상태 전이와 트랜잭션 경계를 중심으로 설계했습니다.

## 바로가기

- [운영 환경 로그인 정보](#운영-환경-로그인-정보)
- [30초 요약](#30초-요약)
- [이 프로젝트가 보여주는 것](#이-프로젝트가-보여주는-것)
- [이 프로젝트가 주장하지 않는 것](#이-프로젝트가-주장하지-않는-것)
- [핵심 문제](#핵심-문제)
- [핵심 도메인 흐름](#핵심-도메인-흐름)
- [주요 설계 결정](#주요-설계-결정)
- [정합성 검증 시나리오](#정합성-검증-시나리오)
- [고부하 전체 여정 검증 결과](#고부하-전체-여정-검증-결과)
- [주요 API 흐름](#주요-api-흐름)
- [기술 스택](#기술-스택)
- [실행 방법](#실행-방법)
- [상세 문서 바로가기](#상세-문서-바로가기)

## 운영 환경 로그인 정보

포트폴리오 확인용 운영 계정입니다.

| 구분 | 이메일 | 비밀번호 |
| --- | --- | --- |
| 관리자 | `admin@admin.com` | `a123456789` |
| 일반 사용자 | `user@user.com` | `a123456789` |

## 30초 요약

- 인기 공연 오픈 시점에 같은 좌석으로 요청이 몰리는 상황을 가정했습니다.
- 예매 단위는 개별 좌석이 아니라 `ReservationGroup`으로 묶었습니다.
- 좌석 선점은 정렬된 좌석 ID와 DB 행 잠금으로 처리했습니다.
- 결제 승인 시 `Payment`, `ReservationGroup`, `Reservation`, `Seat` 상태를 함께 확정합니다.
- 결제되지 않은 선점 좌석은 만료 후 다시 `AVAILABLE`로 복구합니다.
- 고부하 전체 여정 테스트에서는 완료 결제 `1,000`건, 중복 좌석 `0`, 부분 성공 `0`, 상태 불일치 `0`을 확인했습니다.

## 이 프로젝트가 보여주는 것

- 동일 좌석 동시 요청에서 하나의 예매 그룹만 성공하도록 수렴시켰습니다.
- 다중 좌석 예매에서 일부 좌석만 선점되는 부분 성공을 만들지 않았습니다.
- 예약 만료, 결제 승인, 결제 취소가 상태 전이 규칙을 벗어나지 않도록 검증했습니다.
- 결제 완료 후 결제, 예매 그룹, 개별 예매, 좌석 상태가 하나의 결과로 함께 확정되도록 구성했습니다.
- 인기 공연 전체 여정 시나리오에서 정상 경합 거부와 예상 밖 오류를 분리해서 측정했습니다.
- 조회 성능 개선은 좌석 정합성 로직을 캐시하지 않고, 카탈로그 데이터와 예약 상태를 분리하는 방향으로 진행했습니다.

## 이 프로젝트가 주장하지 않는 것

- 운영 환경의 최대 TPS나 SLO를 보장하는 프로젝트는 아닙니다.
- 대기열 시스템을 구현했다고 주장하지 않습니다.
- Redis, Kafka 기반 대규모 분산 아키텍처를 구현했다고 주장하지 않습니다.
- k6 결과는 로컬 통제 환경에서의 비교 수치이며, 운영 처리량 보장 수치로 보지 않습니다.
- `soldOut` 빠른 경로는 중심 메시지가 아니라 카탈로그 데이터와 예약 상태를 분리한 설계의 보조 최적화로 봅니다.

## 핵심 문제

| 문제 | 필요한 처리 |
| --- | --- |
| 동일 좌석에 여러 사용자가 동시에 접근합니다. | 하나의 요청만 좌석 선점에 성공하고 나머지는 정상 경합으로 거부되어야 합니다. |
| 사용자는 여러 좌석을 한 번에 예매할 수 있습니다. | 선택한 좌석 전체가 가능할 때만 예매 그룹이 생성되어야 합니다. |
| 선점 후 결제하지 않는 사용자가 생깁니다. | 결제되지 않은 좌석은 만료 후 다시 예매 가능 상태로 돌아가야 합니다. |
| 결제 완료는 여러 상태를 함께 바꿉니다. | 결제, 예매 그룹, 개별 예매, 좌석 상태가 하나의 결과로 확정되어야 합니다. |
| 인기 공연 오픈 시점에는 조회와 경합 실패가 반복됩니다. | 정상 경합/매진 거부와 예상 밖 오류를 분리해서 처리해야 합니다. |

## 핵심 도메인 흐름

```text
공연/회차 선택
-> 좌석 조회
-> 좌석 선점
-> ReservationGroup 생성
-> Payment READY 생성
-> PG 승인 요청
-> Payment APPROVED
-> ReservationGroup / Reservation CONFIRMED
-> Seat BOOKED
```

### 상태 전이 요약

| 대상 | 주요 상태 전이 |
| --- | --- |
| Seat | `AVAILABLE -> HELD -> BOOKED`, `HELD -> AVAILABLE`, `BOOKED -> AVAILABLE` |
| ReservationGroup | `PENDING -> CONFIRMED`, `PENDING -> EXPIRED`, `CONFIRMED -> CANCELED` |
| Reservation | `PENDING -> CONFIRMED`, `PENDING -> EXPIRED`, `CONFIRMED -> CANCELED` |
| Payment | `READY -> APPROVED`, `READY -> FAILED`, `APPROVED -> CANCELED` |

상세 상태 정책은 [docs/state-design.md](docs/state-design.md)에 정리했습니다.

## 주요 설계 결정

| 설계 결정 | 이유 | 확인한 내용 |
| --- | --- | --- |
| `ReservationGroup` 기준 예매 | 다중 좌석 예매와 결제 1건의 관계를 명확히 관리하기 위해서입니다. | 부분 성공 예매 그룹 `0`을 확인했습니다. |
| 좌석 ID 정렬 후 행 잠금 | 같은 좌석 묶음에 대한 잠금 획득 순서를 통일하기 위해서입니다. | 동일/겹치는 좌석 요청에서 하나의 예매 그룹만 성공했습니다. |
| 결제와 만료 경합 구간 잠금 | 결제 승인과 예약 만료가 같은 예매 그룹을 동시에 바꾸지 못하게 하기 위해서입니다. | 먼저 확정된 흐름만 최종 상태로 남았습니다. |
| PG 상태 재확인 | 승인/취소 요청 후 응답이 불확실할 때 외부 결제 상태를 다시 확인하기 위해서입니다. | `paymentKey` 조회 결과를 기준으로 내부 상태를 수렴시켰습니다. |
| 카탈로그 캐시와 예약 상태 분리 | 공연 정보는 캐시하되 좌석/매진 상태는 DB 기준으로 판단하기 위해서입니다. | 좌석 상태 자체는 캐시하지 않았습니다. |
| 정상 경합 거부와 예상 밖 오류 분리 | 인기 공연에서는 실패 요청 자체가 장애가 아닐 수 있기 때문입니다. | k6 지표에서 예상된 거부와 예상 밖 오류를 나눴습니다. |

## 정합성 검증 시나리오

| 시나리오 | 검증 내용 |
| --- | --- |
| 동일 좌석 동시 선점 | 같은 좌석 묶음을 여러 사용자가 요청해도 하나의 예매 그룹만 성공합니다. |
| 겹치는 좌석 묶음 요청 | 일부 좌석이 겹치는 요청에서도 중복 활성 좌석이 생기지 않습니다. |
| 다중 좌석 예매 | 선택 좌석 전체가 가능할 때만 예매 그룹이 생성되고, 일부 좌석만 성공하지 않습니다. |
| 예약 만료 | 결제되지 않은 예매 그룹은 `EXPIRED`, 좌석은 `AVAILABLE`, `READY` 결제는 `FAILED`로 정리됩니다. |
| 결제 승인 | `Payment`, `ReservationGroup`, `Reservation`, `Seat`가 함께 확정됩니다. |
| 결제 취소 | 승인된 결제를 취소하면 예매와 좌석 상태가 함께 복구됩니다. |
| 결제 승인과 만료 경합 | 먼저 확정된 상태 전이 이후 뒤늦은 흐름은 현재 상태를 다시 보고 거부됩니다. |
| 리프레시 토큰 재발급 | 같은 리프레시 토큰 동시 재발급 요청 중 하나만 성공합니다. |

## 고부하 전체 여정 검증 결과

인기 공연 오픈 직후 시나리오는 공연 목록, 상세, 좌석 조회, 예약 생성, 결제 준비, 모의 PG 승인, 결제 확정까지 이어지는 전체 여정으로 측정했습니다.

성능 수치는 운영 최대 처리량을 보장하기 위한 값이 아니라, 같은 로컬 통제 조건에서 개선 전후를 비교하기 위한 값입니다. README에서는 p95와 TPS보다 **정합성이 깨지지 않았는지**를 더 중요한 기준으로 봅니다.

| 단계 | 핵심 변경 | 전체 여정 TPS | 전체 여정 p95 | 시작 실패 iteration | 완료 결제 | 예상 밖 오류 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 초기 관찰 | 캐시/상태 분리 전 | `223.06 여정/s` | `15.60s` | `5,694` | `1,000` | `0` |
| 공연 캐시 | 목록/상세 Caffeine 캐시 | `311.46 여정/s` | `2.93s` | `1,274` | `1,000` | `0` |
| 트랜잭션 분리 | 만료 처리 쓰기 트랜잭션과 좌석 조회 분리 | `314.78 여정/s` | `2.66s` | `1,108` | `1,000` | `0` |
| 상태 분리 보완 | 가용 상태 기반 매진 상태 처리 | `336.94 여정/s` | `244ms` | `0` | `1,000` | `0` |

DB 사후 검증 결과:

| 항목 | 결과 |
| --- | ---: |
| `BOOKED` 좌석 | `1,000` |
| 예매 그룹 | `1,000` |
| 결제 | `1,000` |
| 중복 활성 좌석 배정 | `0` |
| 부분 성공 예매 그룹 | `0` |
| `APPROVED / CONFIRMED / BOOKED` 상태 불일치 | `0` |

상세 실험 과정과 해석은 [docs/performance-e2e-optimization-summary.md](docs/performance-e2e-optimization-summary.md), 전체 성능 테스트 전략은 [docs/performance-test-strategy.md](docs/performance-test-strategy.md)에 정리했습니다.

<a id="주요-api-흐름"></a>
<details>
<summary>주요 API 흐름</summary>

### 인증

```text
POST /api/v1/auth/login
-> 이메일/비밀번호 검증
-> 액세스 토큰 + 리프레시 토큰 발급
-> HttpOnly 쿠키 반환
```

```text
POST /api/v1/auth/reissue
-> 리프레시 토큰 조건부 갱신
-> 한 번만 소비되도록 재발급
```

```text
GET /api/v1/users/me
-> 현재 로그인 사용자 정보 반환
```

인증 흐름과 쿠키 기반 토큰 정책은 [docs/auth-flow-readme.md](docs/auth-flow-readme.md)에 정리했습니다.

### 공연/좌석

```text
GET /api/v1/event
GET /api/v1/event/{eventId}
GET /api/v1/event/schedules/availability?scheduleIds=...
GET /api/v1/event/schedules/{scheduleId}/seats
```

`/seats`는 회차별 만료 처리를 먼저 수행합니다. 이후 가용 상태 기준으로 매진이면 `{ scheduleId, soldOut: true, seats: [] }`를 반환합니다.

### 예약/결제

```text
POST /api/v1/reservations
POST /api/v1/payments/ready
POST /api/v1/payments/confirm
POST /api/v1/payments/{paymentId}/cancel
GET  /api/v1/payments/{paymentId}/status
```

결제 승인과 취소는 `Payment`, `ReservationGroup`, `Reservation`, `Seat` 상태를 하나의 트랜잭션에서 함께 변경합니다.

</details>

## 기술 스택

| 영역 | 사용 기술 | 사용 이유 |
| --- | --- | --- |
| 언어 | Java 21 | Spring Boot 기반 백엔드 구현에 사용했습니다. |
| 프레임워크 | Spring Boot 3.5.13, Spring Web | REST API와 계층형 애플리케이션 구성을 위해 사용했습니다. |
| 영속성 | Spring Data JPA, PostgreSQL, Flyway | 상태 전이, 행 잠금, 스키마 관리를 위해 사용했습니다. |
| 보안 | Spring Security, JWT, HttpOnly 쿠키 | 쿠키 기반 인증과 권한 검증을 위해 사용했습니다. |
| 캐시 | Spring Cache, Caffeine | 공연 목록/상세 같은 카탈로그 조회 최적화에 사용했습니다. |
| API 문서 | springdoc-openapi, Swagger UI | 운영 환경에서 API 확인이 가능하도록 사용했습니다. |
| 관측 | Spring Actuator, Micrometer, Prometheus registry | 부하 테스트 중 커넥션 풀과 애플리케이션 상태를 확인하기 위해 사용했습니다. |
| 테스트 | JUnit 5, Spring Boot Test, Spring Security Test, Mockito | 상태 전이와 동시성 흐름 검증에 사용했습니다. |
| 성능 | k6, 모의 PG | 인기 공연 전체 여정 흐름을 외부 PG 부하 없이 검증하기 위해 사용했습니다. |

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

- 성능 테스트는 `dev,perf` 프로필을 기준으로 실행합니다.
- 모의 PG는 `127.0.0.1:18080`에서 실행합니다.
- 성능 테스트 사용자 풀은 `performance/data/perf-users.json`에 준비합니다.

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
- DB 사후 정합성: 중복 활성 좌석, 부분 성공 예매 그룹, 상태 불일치

</details>

## 상세 문서 바로가기

| 문서 | 내용 |
| --- | --- |
| [docs/state-design.md](docs/state-design.md) | 좌석, 예매, 결제 상태 전이 정책 |
| [docs/auth-flow-readme.md](docs/auth-flow-readme.md) | 로그인, 재발급, HttpOnly 쿠키 인증 흐름 |
| [docs/performance-e2e-optimization-summary.md](docs/performance-e2e-optimization-summary.md) | 인기 공연 전체 여정 성능 개선 과정과 최종 지표 |
| [docs/performance-test-strategy.md](docs/performance-test-strategy.md) | k6 성능 테스트 시나리오와 측정 기준 |
| [docs/change-history.md](docs/change-history.md) | 주요 변경 이력과 설계 판단 기록 |
