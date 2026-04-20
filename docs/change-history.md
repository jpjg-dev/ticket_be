# 변경 이력 추적 (결제/예약 흐름)

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
