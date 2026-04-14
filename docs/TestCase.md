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

1. [ ] PaymentService.approvePayment()
2. [ ] ReservationService.createReservation()
3. [ ] PaymentService.cancelPayment()
4. [ ] ReservationService.expireReservations()
5. [ ] PaymentService.failPayment()

---

## 핵심 목표

- 상태 전이 정합성 보장
- 트랜잭션 내에서 3개 객체 (Seat / Reservation / Payment) 동기화 확인
- 예외 상황에서 상태가 깨지지 않는지 검증