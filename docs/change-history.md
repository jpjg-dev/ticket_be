# 변경 이력 추적 (결제/예약 흐름)

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
