# 테스트 체크리스트 (현재 코드 기준)

## 1. ReservationService 테스트

### 1.1 createReservation()

#### 성공 케이스
- [x] 정상적으로 예약 group이 생성된다
- [x] 선택 좌석들의 Seat 상태: `AVAILABLE -> HELD`
- [x] group 안의 Reservation 상태: `PENDING`
- [x] 환경 설정의 선점 유지 시간으로 `expiresAt`이 설정된다
- [x] 하나의 group과 group 안의 Reservation은 동일한 `expiresAt`을 공유한다
- [x] 겹치는 좌석 묶음 동시 요청 시 하나의 group만 성공한다

#### 실패 케이스
- [x] 존재하지 않는 사용자면 `EntityNotFoundException`
- [x] 존재하지 않는 좌석이면 `EntityNotFoundException`
- [x] 좌석이 `AVAILABLE`이 아니면 `IllegalStateException`

---

### 1.2 ReservationExpirationService

#### 성공 케이스
- [x] 만료된 group 안의 pending Reservation은 `PENDING -> EXPIRED`
- [x] 만료된 group 안의 좌석은 `HELD -> AVAILABLE`
- [x] 만료된 group에 연결된 결제가 `READY`면 `FAILED`로 전이된다
- [x] 스케줄러용 전체 만료 처리와 좌석 조회용 회차별 만료 처리가 분리된다
- [x] 좌석 조회 시 현재 `scheduleId`의 만료 group만 먼저 정리한다

#### 검증 포인트
- [x] 만료 대상 group이 없으면 상태 변화가 없다
- [x] 결제가 없거나 `READY`가 아니면 결제 상태는 변경하지 않는다
- [x] 만료 처리 반환값(`expiredCount`)이 실제 만료 처리 reservation 건수와 일치한다
- [x] `createReservation()`은 전체 만료 정리를 호출하지 않고 좌석 선점 생성만 담당한다

---

## 2. PaymentService 테스트

### 2.1 readyPayment()

#### 성공 케이스
- [x] group 안의 모든 Reservation이 `PENDING`이면 Payment가 `READY`로 생성된다
- [x] Payment.amount는 그룹에 포함된 Seat.price 합계 기준으로 저장된다
- [x] Payment.orderId가 생성된다
- [x] 동일 reservationGroupId 재요청 시 기존 Payment를 재사용한다

#### 실패 케이스
- [x] 존재하지 않는 reservationGroupId면 `EntityNotFoundException`
- [x] group 안에 `PENDING`이 아닌 Reservation이 있으면 `IllegalStateException`
- [x] ReservationGroup이 만료되었으면 `IllegalStateException`

---

### 2.2 confirmPayment()

#### 성공 케이스
- [ ] orderId로 Payment를 조회한다
- [ ] Payment 상태가 `READY`일 때 승인된다
- [x] group 안의 Reservation 상태가 모두 `PENDING`일 때 승인된다
- [ ] amount(총액, VAT 포함) 검증 통과 시 승인된다
- [x] 승인 성공 시 `Payment APPROVED / group 안의 Reservation CONFIRMED / Seat BOOKED`
- [ ] `paymentKey`, `method`, `pgStatus`가 저장된다
- [ ] PG confirm 응답을 받지 못해도 조회 결과가 `DONE`이면 승인 상태를 확정한다
- [ ] 로그 이벤트 키가 `PAYMENT_CONFIRM_*` 규칙으로 출력된다

#### 실패 케이스
- [ ] 존재하지 않는 orderId면 `EntityNotFoundException`
- [ ] Payment 상태가 `READY`가 아니면 `IllegalStateException`
- [ ] group 안의 Reservation 상태가 `PENDING`이 아니면 `IllegalStateException`
- [x] ReservationGroup이 만료되었으면 `IllegalStateException`
- [ ] amount 불일치면 `IllegalStateException`
- [ ] PG 승인 응답 금액/통화 불일치면 `IllegalStateException`

---

### 2.3 failPayment()

#### 성공 케이스
- [ ] `READY -> FAILED`
- [x] group이 미만료면 Reservation/Seat 상태는 유지된다
- [x] group이 만료면 group 안의 `Reservation EXPIRED`, `Seat AVAILABLE`로 전이된다

#### 실패 케이스
- [ ] `READY`가 아니면 `IllegalStateException`

---

### 2.4 cancelPayment()

#### 성공 케이스
- [ ] `APPROVED` 결제만 취소 가능하다
- [x] PG 취소 성공 후 `Payment CANCELED / group 안의 Reservation CANCELED / Seat AVAILABLE`
- [ ] PG cancel 응답을 받지 못해도 조회 결과가 `CANCELED`면 취소 상태를 확정한다
- [ ] 로그 이벤트 키가 `PAYMENT_CANCEL_*` 규칙으로 출력된다

#### 실패 케이스
- [ ] `APPROVED`가 아니면 `IllegalStateException`
- [ ] `paymentKey`가 없으면 `IllegalStateException`
- [ ] PG 취소 응답의 결제키/통화/상태가 불일치하면 `IllegalStateException`

---

### 2.5 getPaymentStatus()

#### 성공 케이스
- [ ] paymentId로 현재 결제 상태를 조회한다
- [x] `Payment / Reservation / Seat` 상태를 함께 반환한다
- [ ] 프론트 폴링 기준으로 사용할 수 있다

---

## 3. Controller 테스트

### 3.1 ReservationController
- [ ] 예약 생성 성공 (200)
- [ ] validation 실패 (400)
- [ ] 존재하지 않는 user/seat (404)

### 3.2 PayMentController
- [ ] `/payments/ready` 성공 (200)
- [ ] `/payments/confirm` 성공 (200)
- [ ] `/payments/{id}/status` 성공 (200)
- [ ] `/payments/{id}/cancel` 성공 (200)
- [ ] `/payments/fail-redirect` 성공 (200)
- [ ] 잘못된 상태 전이 (409)

---

## 4. User API 테스트

### 4.1 users/me
- [x] 로그인 사용자 id로 현재 사용자 기본 정보를 반환한다
- [x] 존재하지 않는 사용자면 `EntityNotFoundException`
- [x] 컨트롤러가 `@AuthenticationPrincipal Long userId`를 서비스로 전달한다

### 4.2 mypage
- [x] 본인 userId만 조회 가능하다
- [x] 다른 사용자 조회 시 `IllegalStateException`
- [x] `CONFIRMED`, `CANCELED` 예약을 group 기준으로 반환한다
- [x] `APPROVED`, `CANCELED` 결제를 group 기준으로 반환한다
- [x] 결제 취소에 필요한 `paymentId`와 좌석 목록을 포함한다
- [x] 컨트롤러가 path variable userId와 principal userId를 서비스로 전달한다

---

## 5. 우선순위

1. [x] `PaymentService.confirmPayment()`
2. [x] `PaymentService.cancelPayment()`
3. [x] `PaymentService.readyPayment()`
4. [x] `PaymentService.getPaymentStatus()`
5. [x] `ReservationExpirationService.expireAll()` / `expireByScheduleId()`
6. [x] `ReservationService.createReservation()`
7. [x] `PaymentService.failPayment()`

---

## 핵심 목표

- 상태 전이 정합성 보장
- 트랜잭션 내 Seat / Reservation / Payment 동기화 확인
- 만료/이탈/취소 등 예외 상황에서도 상태 불일치가 없도록 검증
