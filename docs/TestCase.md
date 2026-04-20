# 테스트 체크리스트 (현재 코드 기준)

## 1. ReservationService 테스트

### 1.1 createReservation()

#### 성공 케이스
- [ ] 정상적으로 예약 생성된다
- [ ] Seat 상태: `AVAILABLE -> HELD`
- [ ] Reservation 상태: `PENDING`
- [ ] `expiresAt`이 정상 설정된다

#### 실패 케이스
- [ ] 존재하지 않는 사용자면 `EntityNotFoundException`
- [ ] 존재하지 않는 좌석이면 `EntityNotFoundException`
- [ ] 좌석이 `AVAILABLE`이 아니면 `IllegalStateException`

---

### 1.2 expireReservations()

#### 성공 케이스
- [ ] 만료된 예약은 `PENDING -> EXPIRED`
- [ ] 만료된 예약의 좌석은 `HELD -> AVAILABLE`
- [ ] 만료된 예약에 연결된 결제가 `READY`면 `FAILED`로 전이된다

#### 검증 포인트
- [ ] 만료 대상이 없으면 상태 변화가 없다
- [ ] 결제가 없거나 `READY`가 아니면 결제 상태는 변경하지 않는다

---

## 2. PaymentService 테스트

### 2.1 readyPayment()

#### 성공 케이스
- [ ] `PENDING` 예약이면 Payment가 `READY`로 생성된다
- [ ] Payment.amount는 Seat.price 기준으로 저장된다
- [ ] Payment.orderId가 생성된다
- [ ] 동일 reservationId 재요청 시 기존 Payment를 재사용한다

#### 실패 케이스
- [ ] 존재하지 않는 reservationId면 `EntityNotFoundException`
- [ ] Reservation 상태가 `PENDING`이 아니면 `IllegalStateException`
- [ ] Reservation이 만료되었으면 `IllegalStateException`

---

### 2.2 confirmPayment()

#### 성공 케이스
- [ ] orderId로 Payment를 조회한다
- [ ] Payment 상태가 `READY`일 때 승인된다
- [ ] Reservation 상태가 `PENDING`일 때 승인된다
- [ ] amount(총액, VAT 포함) 검증 통과 시 승인된다
- [ ] 승인 성공 시 `Payment APPROVED / Reservation CONFIRMED / Seat BOOKED`
- [ ] `paymentKey`, `method`, `pgStatus`가 저장된다

#### 실패 케이스
- [ ] 존재하지 않는 orderId면 `EntityNotFoundException`
- [ ] Payment 상태가 `READY`가 아니면 `IllegalStateException`
- [ ] Reservation 상태가 `PENDING`이 아니면 `IllegalStateException`
- [ ] Reservation이 만료되었으면 `IllegalStateException`
- [ ] amount 불일치면 `IllegalStateException`
- [ ] PG 승인 응답 금액/통화 불일치면 `IllegalStateException`

---

### 2.3 failPayment()

#### 성공 케이스
- [ ] `READY -> FAILED`
- [ ] 예약이 미만료면 Reservation/Seat 상태는 유지된다
- [ ] 예약이 만료면 `Reservation EXPIRED`, `Seat AVAILABLE`로 전이된다

#### 실패 케이스
- [ ] `READY`가 아니면 `IllegalStateException`

---

### 2.4 cancelPayment()

#### 성공 케이스
- [ ] `APPROVED` 결제만 취소 가능하다
- [ ] PG 취소 성공 후 `Payment CANCELED / Reservation CANCELED / Seat AVAILABLE`

#### 실패 케이스
- [ ] `APPROVED`가 아니면 `IllegalStateException`
- [ ] `paymentKey`가 없으면 `IllegalStateException`
- [ ] PG 취소 응답의 결제키/통화/상태가 불일치하면 `IllegalStateException`

---

## 3. Controller 테스트

### 3.1 ReservationController
- [ ] 예약 생성 성공 (200)
- [ ] validation 실패 (400)
- [ ] 존재하지 않는 user/seat (404)

### 3.2 PayMentController
- [ ] `/payments/ready` 성공 (200)
- [ ] `/payments/confirm` 성공 (200)
- [ ] `/payments/{id}/fail` 성공 (200)
- [ ] `/payments/{id}/cancel` 성공 (200)
- [ ] 잘못된 상태 전이 (409)

---

## 4. 우선순위

1. [ ] `PaymentService.confirmPayment()`
2. [ ] `PaymentService.cancelPayment()`
3. [ ] `PaymentService.readyPayment()`
4. [ ] `ReservationService.expireReservations()`
5. [ ] `ReservationService.createReservation()`
6. [ ] `PaymentService.failPayment()`

---

## 핵심 목표

- 상태 전이 정합성 보장
- 트랜잭션 내 Seat / Reservation / Payment 동기화 확인
- 만료/이탈/취소 등 예외 상황에서도 상태 불일치가 없도록 검증
