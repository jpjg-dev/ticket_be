# 테스트 체크리스트 (Reservation / Payment 시스템)

## 1. ReservationService 테스트

### 1.1 createReservation()

#### 성공 케이스
- [ ] 정상적으로 예약 생성된다
- [ ] Seat 상태: AVAILABLE → HELD 변경
- [ ] Reservation 상태: PENDING 생성
- [ ] expiresAt 정상 설정

#### 실패 케이스
- [ ] 존재하지 않는 사용자 → EntityNotFoundException
- [ ] 존재하지 않는 좌석 → EntityNotFoundException
- [ ] 좌석이 AVAILABLE이 아닐 경우 → IllegalStateException

---

### 1.2 cancelReservation()

#### 성공 케이스
- [ ] PENDING 예약 취소 성공
- [ ] CONFIRMED 예약 취소 성공
- [ ] Seat 상태 복구 (HELD 또는 BOOKED → AVAILABLE)
- [ ] Reservation 상태 → CANCELED

#### 실패 케이스
- [ ] 다른 사용자의 예약 취소 시도 → 예외
- [ ] 이미 CANCELED / EXPIRED 상태 → 예외

---

### 1.3 expireReservations()

#### 성공 케이스
- [ ] expiresAt 지난 예약 → EXPIRED 변경
- [ ] Seat 상태 HELD → AVAILABLE 복구

#### 검증 포인트
- [ ] 만료 안 된 예약은 유지됨

---

## 2. PaymentService 테스트

### 2.1 approvePayment()

#### 성공 케이스
- [ ] Payment: READY → APPROVED
- [ ] Reservation: PENDING → CONFIRMED
- [ ] Seat: HELD → BOOKED

#### 실패 케이스
- [ ] Payment 상태가 READY가 아님 → IllegalStateException
- [ ] Reservation 상태가 PENDING이 아님 → 예외

---

### 2.2 failPayment()

#### 성공 케이스
- [ ] Payment: READY → FAILED

#### 정책 확인
- [ ] Reservation 상태 유지 (PENDING)
- [ ] Seat 상태 유지 (HELD)

#### 실패 케이스
- [ ] READY 상태가 아닐 때 → 예외

---

### 2.3 cancelPayment()

#### 성공 케이스
- [ ] Payment: APPROVED → CANCELED
- [ ] Reservation: CONFIRMED → CANCELED
- [ ] Seat: BOOKED → AVAILABLE

#### 실패 케이스
- [ ] APPROVED 상태가 아닐 때 → 예외

---

### 2.4 readyPayment()

#### 목적
토스 결제창 진입 전에 서버 기준 결제 정보를 생성한다.

#### 성공 케이스
- [ ] PENDING 예약이면 Payment가 READY 상태로 생성된다
- [ ] Payment.amount는 Seat.price 기준으로 저장된다
- [ ] Payment.orderId가 생성된다
- [ ] 동일 reservationId로 다시 요청하면 기존 Payment를 재사용한다
- [ ] 중복 요청 시 Payment가 추가 생성되지 않는다

#### 상태 검증
- [ ] Reservation 상태는 PENDING으로 유지된다
- [ ] Seat 상태는 HELD로 유지된다
- [ ] Payment 상태는 READY이다

#### 실패 케이스
- [ ] 존재하지 않는 reservationId → EntityNotFoundException
- [ ] Reservation 상태가 PENDING이 아니면 → IllegalStateException
- [ ] Reservation이 이미 만료되었으면 → IllegalStateException

#### 만료 정책 검증
- [ ] 만료된 Reservation은 EXPIRED로 변경된다
- [ ] HELD Seat는 AVAILABLE로 복구된다
- [ ] 만료된 예약에 대해서는 Payment가 생성되지 않는다

#### 검증 포인트
```text
Reservation PENDING + Seat HELD
→ readyPayment()
→ Payment READY 생성
→ Reservation PENDING 유지
→ Seat HELD 유지
```

---

### 2.5 confirmPayment()

#### 목적
토스 successUrl 이후 전달받은 paymentKey, orderId, amount를 기준으로 결제 승인을 검증하고 내부 상태를 확정한다.

#### 성공 케이스
- [ ] orderId로 Payment를 조회한다
- [ ] Payment 상태가 READY이면 승인 가능하다
- [ ] Reservation 상태가 PENDING이면 승인 가능하다
- [ ] Reservation이 만료되지 않았으면 승인 가능하다
- [ ] 요청 amount와 Payment.amount가 일치하면 승인 가능하다
- [ ] 승인 성공 시 Payment 상태가 APPROVED로 변경된다
- [ ] 승인 성공 시 Reservation 상태가 CONFIRMED로 변경된다
- [ ] 승인 성공 시 Seat 상태가 BOOKED로 변경된다
- [ ] paymentKey가 Payment에 저장된다
- [ ] pgStatus는 임시로 DONE 저장을 검증한다
- [ ] method는 실제 PG 연동 전까지 null 허용을 검증한다

#### 실패 케이스
- [ ] 존재하지 않는 orderId → EntityNotFoundException
- [ ] Payment 상태가 READY가 아니면 → IllegalStateException
- [ ] Reservation 상태가 PENDING이 아니면 → IllegalStateException
- [ ] Reservation이 만료되었으면 → IllegalStateException
- [ ] 요청 amount와 Payment.amount가 다르면 → IllegalStateException

#### 만료 정책 검증
- [ ] 만료된 Reservation 승인 요청 시 Toss 승인 API 호출 전 차단한다
- [ ] Payment 상태는 FAILED로 변경된다
- [ ] Reservation 상태는 EXPIRED로 변경된다
- [ ] Seat 상태는 AVAILABLE로 복구된다

#### 금액 불일치 정책 검증
- [ ] amount 불일치 시 승인 처리하지 않는다
- [ ] amount 불일치 시 Payment 상태는 READY로 유지된다
- [ ] amount 불일치 시 Reservation 상태는 PENDING으로 유지된다
- [ ] amount 불일치 시 Seat 상태는 HELD로 유지된다

#### 검증 포인트
```text
Payment READY + Reservation PENDING + Seat HELD
→ confirmPayment()
→ Payment APPROVED
→ Reservation CONFIRMED
→ Seat BOOKED
```

```text
Payment READY + Reservation PENDING but expired + Seat HELD
→ confirmPayment()
→ Payment FAILED
→ Reservation EXPIRED
→ Seat AVAILABLE
```

```text
Payment READY + Reservation PENDING + amount mismatch
→ confirmPayment()
→ IllegalStateException
→ Payment READY 유지
→ Reservation PENDING 유지
→ Seat HELD 유지
```

---

## 3. Entity 단위 테스트 (선택)

### Seat
- [ ] hold(): AVAILABLE → HELD
- [ ] book(): HELD → BOOKED
- [ ] release(): HELD → AVAILABLE
- [ ] releaseBooked(): BOOKED → AVAILABLE

### Reservation
- [ ] confirm(): PENDING → CONFIRMED
- [ ] cancel(): 상태별 정상 동작
- [ ] expire(): PENDING → EXPIRED

### Payment
- [ ] approve(): READY → APPROVED
- [ ] fail(): READY → FAILED
- [ ] cancel(): APPROVED → CANCELED

---

## 4. Controller 테스트

### ReservationController
- [ ] 예약 생성 성공 (200 OK)
- [ ] validation 실패 (400 Bad Request)
- [ ] 존재하지 않는 user/seat (404)

### PaymentController
- [ ] 결제 승인 성공
- [ ] 결제 실패 처리
- [ ] 결제 취소 성공
- [ ] 잘못된 상태 → 409 Conflict

---

## 5. 우선순위 (중요도 순)

1. [ ] PaymentService.readyPayment()
2. [ ] PaymentService.confirmPayment()
3. [ ] ReservationService.createReservation()
4. [ ] PaymentService.cancelPayment()
5. [ ] ReservationService.expireReservations()
6. [ ] PaymentService.failPayment()
7. [ ] PaymentService.approvePayment()

---

## 핵심 목표

- 상태 전이 정합성 보장
- 트랜잭션 내에서 3개 객체 (Seat / Reservation / Payment) 동기화 확인
- 예외 상황에서 상태가 깨지지 않는지 검증
