# 변경 이력 추적 (결제/예약 흐름)

## 2026-06-09

### 1) 좌석 조회 트랜잭션 범위 분리
- `EventService.getSeats()`가 직접 write transaction을 감싸지 않도록 변경했다.
- 좌석 조회 전 수동 만료 상태 전이는 기존 정책대로 `ReservationExpirationService.expireByScheduleId()`의 write transaction에서 처리한다.
- 좌석 조회 응답 매핑은 path variable로 이미 알고 있는 `scheduleId`를 직접 사용해 `Seat -> Schedule` 접근을 줄였다.
- 동일한 `dev,perf` 인기 공연 E2E arrival-rate 조건에서 2차 개선 후 전체 여정 p95는 `2.93s -> 2.66s`, 좌석 조회 p95는 `1.41s -> 1.31s`, dropped iteration은 `1,274 -> 1,108`로 추가 개선됐다.
- 2차 개선 후에도 완료 결제는 `1,000`, 예상 밖 오류는 `0`, DB 사후 검증의 중복 active 좌석 배정/부분 성공 group/상태 불일치는 모두 `0`이다.

### 2) 회차 매진 여부 실시간 조회와 좌석 조회 fast-path
- `GET /api/v1/event/schedules/availability?scheduleIds=...` batch API를 추가했다.
- 응답은 회차별 `available`, `held`, `booked`, `soldOut`을 반환한다.
- 매진 기준은 `AVAILABLE=0`, `HELD=0`, `BOOKED>0`이다. `HELD`는 만료 후 복구될 수 있으므로 매진으로 보지 않는다.
- 공연 목록/상세 캐시는 카탈로그 데이터로 유지하고, `soldOut` 같은 예약 상태는 캐시에 포함하지 않는다.
- `GET /api/v1/event/schedules/{scheduleId}/seats` 응답을 `{ scheduleId, soldOut, seats }` 형태로 변경했다.
- `soldOut=true`이면 좌석 목록을 조회/직렬화하지 않고 빈 `seats`를 반환해 매진 이후 좌석 payload 전송을 줄인다.
- 프론트 서버는 `/event` 캐시 응답과 availability 응답을 조합해 메인 예매 버튼을 비활성화한다.

## 2026-06-08

### 1) 공연 표시 상태 책임 분리
- 백엔드 `EventResponse`에서 `displayStatus`를 제거했다.
- 백엔드는 `bookingOpenAt`, `runStartAt`, `runEndAt` 같은 원천 시간 데이터만 응답한다.
- 프론트는 서버 컴포넌트 데이터 조합 단계에서 `runStartAt`, `runEndAt` 기준으로 `UPCOMING / NOW_SHOWING / ENDED` 표시 상태를 계산한다.
- 해당 상태는 홈 화면 섹션 분류와 배지 표시용 UI 파생값이며, 예매/결제 정합성 검증 기준으로 사용하지 않는다.

### 2) 예매 오픈 전 예약 생성 차단
- `ReservationService.createReservation()`에서 좌석을 `HELD`로 변경하거나 `ReservationGroup`을 생성하기 전에 대상 공연의 `bookingOpenAt`을 검증한다.
- `bookingOpenAt`이 현재 시각보다 미래이면 예약 생성을 거부하고 좌석 상태는 `AVAILABLE`로 유지한다.
- 예매 오픈 전 좌석 예약 거부 테스트를 추가했다.

### 3) 공연 목록/상세 로컬 캐시 적용
- 공연 목록과 공연 상세 조회에 `Spring Cache + Caffeine` 기반 JVM 로컬 캐시를 적용했다.
- 캐시 이름은 `CacheNames`에서 상수로 관리하고, TTL/최대 크기는 `cache.event.*` 설정을 `CacheConfig`에서 `@Value`로 주입한다.
- 운영 기본값은 `eventList` TTL `60s`/최대 크기 `1`, `eventDetail` TTL `300s`/최대 크기 `500`이다.
- 개발 환경은 데이터 변경 확인이 빠르도록 `eventList` TTL `10s`, `eventDetail` TTL `30s`로 낮췄다.
- 서버 재시작 시 캐시는 사라지며, 첫 요청이 DB에서 다시 조회해 캐시를 채우는 read-through 방식을 사용한다.
- 좌석 조회, 예약 생성, 결제, 마이페이지는 실시간 상태와 사용자별 상태가 섞이므로 캐시 대상에서 제외했다.
- 동일한 `dev,perf` 인기 공연 E2E arrival-rate 조건에서 캐시 적용 후 전체 여정 p95는 `15.60s -> 2.93s`, dropped iteration은 `5,694 -> 1,274`, 완료 iteration은 `11,153 -> 15,573`으로 개선됐다.
- 캐시 적용 후에도 완료 결제는 `1,000`, 예상 밖 오류는 `0`, DB 사후 검증의 중복 active 좌석 배정/부분 성공 group/상태 불일치는 모두 `0`이다.

### 4) 운영 데모 공연/회차 시간 최신화 migration 추가
- 운영 서버의 기존 데모 공연 시간이 과거가 되는 문제를 해결하기 위해 `V2__refresh_demo_event_schedule_times.sql`을 추가했다.
- 기존 운영 DB row는 공연 제목과 회차 순서 기준으로 `booking_open_at`, `schedules.start_at`, `schedules.end_at`을 현재 migration 실행 시점 기준 미래 일정으로 갱신한다.
- 신규 빈 DB에서는 V2가 no-op으로 동작하고, 이후 `DataInitializer`가 현재 시각 기준 초기 데이터를 생성한다.
- 운영 데이터 갱신은 서버 재시작 때마다 실행되는 `DataInitializer`가 아니라 Flyway migration으로 추적한다.

## 2026-06-02

### 1) 결제 PG 응답 검증 보강
- `confirmPayment`에서 PG 승인 응답의 `totalAmount`, `currency`뿐 아니라 `paymentKey`, `orderId`, `status=DONE`도 검증한다.
- 승인 응답 검증 실패 시 내부 `Payment / ReservationGroup / Reservation / Seat` 상태 전이는 반영하지 않는다.
- `cancelPayment`의 PG 취소 응답은 기존 정책대로 `paymentKey`, `currency`, `status`를 검증한다.

### 2) 결제 예외 흐름 통합 테스트 보강
- PG 승인 응답의 금액, 통화, 결제키, 주문번호, 상태 불일치 테스트를 추가했다.
- `READY` 결제 취소, `paymentKey` 없는 `APPROVED` 결제 취소 거부 테스트를 추가했다.
- PG 취소 응답의 결제키, 통화, 상태 불일치 테스트를 추가했다.
- 모든 검증 실패에서 트랜잭션 롤백 후 기존 상태 유지 여부를 확인한다.

### 3) 결제 서비스 마감 테스트 보강
- `getPaymentStatus()`가 `paymentId`로 현재 결제를 조회하는지 명시적으로 검증한다.
- `FAILED` 결제와 `PENDING`이 아닌 예약은 PG 승인 호출 전에 거부되는지 검증한다.
- 승인/취소 성공 흐름에서 `PAYMENT_CONFIRM_*`, `PAYMENT_CANCEL_*` 로그 이벤트 키가 출력되는지 검증한다.
- 기존 Controller 테스트를 재검증하고 `docs/TestCase.md` 체크리스트를 현재 코드 기준으로 동기화했다.

## 2026-04-24

### 1) 상태 조회 API 및 프론트 확인중 처리
- `GET /payments/{paymentId}/status` 추가
- 프론트는 `confirm/cancel` 응답이 애매할 때 상태 조회 API를 폴링해 최종 상태를 확인하도록 조정
- `ready` 성공 시 `paymentId`, `orderId`를 저장해 성공 페이지에서 재확인에 사용

### 2) 불필요 외부 fail API 정리
- `/payments/{paymentId}/fail` 외부 API 제거
- `failUrl`은 상태 전이 경로가 아니라 `code/message/orderId` 서버 로그 기록 경로로 유지

## 2026-04-23

### 4) 결제 상태 재확인 정책 반영
- `PaymentService`에 PG 응답 미수신 시 조회 기반 재확인 로직 추가
- `confirmPayment`는 `paymentKey` 우선 조회로 `DONE` 상태를 확인하면 내부 승인 상태를 확정
- `cancelPayment`는 `paymentKey` 조회로 `CANCELED` 상태를 확인하면 내부 취소 상태를 확정
- 승인/취소 성공 경로와 재확인 경로가 동일한 상태 전이 헬퍼를 사용하도록 정리

### 1) 결제/만료 로그 표준화
- `event` 키를 문자열 하드코딩에서 공통 상수(`LogEvents`)로 통일
- `PaymentService`의 `confirm/cancel/fail` 로그에 표준 키(`orderId`, `paymentId`, `reservationGroupId`, `reason`) 유지
- 승인/취소 성공 로그는 PG 응답 상태(`tossResponse.status()`)를 `reason`/`pgStatus`로 기록
- `paymentKey`는 원문 대신 마스킹(`paymentKeyMasked`)으로만 기록

### 2) 취소 API 프론트 오탐 수정
- `/api/toss/cancel` 프록시에서 백엔드 `200 + empty body`를 에러로 처리하던 문제 수정
- `response.ok`이고 본문이 없으면 `{ success: true }`를 반환하도록 변경

### 3) 스케줄러 만료 처리 가시성 보강
- `ReservationService.expireReservations()`의 반환값(`expiredCount`) 테스트 검증 추가
- 만료 처리 건수가 0/1인 케이스를 단위 테스트에서 명시적으로 확인

## 2026-04-20

### 1) 결제 승인/취소 흐름 정리
- `approvePayment` 운영 경로 제거 (승인은 `confirmPayment` 단일 경로로 고정)
- `confirmPayment`에서 내부 검증 후 PG 승인 호출, 성공 시 상태 전이
  - `Payment: READY -> APPROVED`
  - `Reservation: PENDING -> CONFIRMED`
  - `Seat: HELD -> BOOKED`
- `cancelPayment`에서 PG 취소 성공 후 상태 전이
  - `Payment: APPROVED -> CANCELED`
  - `Reservation: CONFIRMED -> CANCELED`
  - `Seat: BOOKED -> AVAILABLE`

### 2) 예약 취소 경로 단일화
- `ReservationController`의 예약 취소 API 제거
- 사용자 취소는 결제 취소 API(`/payments/{paymentId}/cancel`)로 단일화

### 3) 만료 정리 정책 보강
- `ReservationService.expireReservations()`에서 만료 예약 처리 시
  - `Reservation: PENDING -> EXPIRED`
  - `Seat: HELD -> AVAILABLE`
  - 연결된 `Payment`가 `READY`면 `FAILED`로 함께 정리

### 4) 프론트 연동 조정
- 결제 취소 프록시 API 추가 (`/api/toss/cancel`)
- 성공 페이지에서 `paymentId + cancelReason`으로 결제 취소 호출 연결
- 홈 화면의 `activeReservation` 자동 복구 제거
- 삭제된 예약 취소 프록시(`/api/toss/reservations/cancel`) 및 버튼 정리

### 5) 테스트/문서 동기화
- `docs/TestCase.md`를 현재 코드 기준으로 전면 업데이트
- 컨트롤러/서비스 테스트를 현재 API 및 상태 정책에 맞게 동기화
  - `approve` 관련 테스트 제거
  - `expireReservations`의 `READY -> FAILED` 케이스 추가
