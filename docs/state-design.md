# 상태 전이 설계 (State Transition Design)

## 개요

본 문서는 TicketLedger 시스템에서 사용하는 상태 체계를 정의하고,
각 상태 간 전이 규칙을 명확히 하기 위한 설계 문서이다.

이 시스템은 기능이 아닌 **상태 중심 설계**를 기반으로 동작하며,
모든 흐름은 상태 전이를 통해 관리된다.

---

# 1. 좌석 상태 (SeatStatus)

## 상태 정의

| 상태        | 설명                  |
| --------- | ------------------- |
| AVAILABLE | 예약 가능 상태           |
| HELD      | 예약 중 (일시적으로 보류된 상태) |
| BOOKED    | 예약 확정 상태          |

---

## 상태 전이

```text
AVAILABLE → HELD
HELD → BOOKED
HELD → AVAILABLE
BOOKED → AVAILABLE
```

---

## 전이 규칙

* AVAILABLE 상태에서만 좌석 선택 가능
* HELD 상태는 일정 시간 이후 자동 해제될 수 있음
* BOOKED 상태는 변경 불가 (불변 상태)
* BOOKED 상태는 취소 후 AVAILABLE로 복구 가능

---

## 금지 규칙

```text
BOOKED → HELD ❌
AVAILABLE → BOOKED ❌ (결제 없이 확정 불가)
```

---

# 2. 예매 상태 (ReservationStatus)

## 상태 정의

| 상태        | 설명                 |
| --------- | ------------------ |
| PENDING   | 예매 진행 중 (결제 대기 상태) |
| CONFIRMED | 결제 완료 후 예매 확정      |
| CANCELED  | 사용자가 취소한 상태        |
| EXPIRED   | 결제 미완료로 자동 만료된 상태  |

---

## 상태 전이

```text
PENDING → CONFIRMED
PENDING → CANCELED
PENDING → EXPIRED
CONFIRMED → CANCELED
```

---

## 전이 규칙

* 예매 생성 시 초기 상태는 PENDING
* 결제 성공 시 CONFIRMED로 변경
* 일정 시간 내 결제되지 않으면 EXPIRED
* CONFIRMED 상태에서만 정상 취소 가능

---

## 금지 규칙

```text
CONFIRMED → PENDING ❌
EXPIRED → CONFIRMED ❌
CANCELED → CONFIRMED ❌
```

---

# 3. 결제 상태 (PaymentStatus)

## 상태 정의

| 상태       | 설명          |
| -------- | ----------- |
| READY    | 결제 요청 생성 상태 |
| APPROVED | 결제 승인 완료    |
| FAILED   | 결제 실패       |
| CANCELED | 결제 취소       |

---

## 상태 전이

```text
READY → APPROVED
READY → FAILED
APPROVED → CANCELED
```

---

## 전이 규칙

* 결제 생성 시 READY 상태로 시작
* 결제 성공 시 APPROVED
* 결제 실패 시 FAILED
* 승인된 결제만 취소 가능

---

## 금지 규칙

```text
FAILED → APPROVED ❌
CANCELED → APPROVED ❌
READY → CANCELED ❌
```

---

# 4. 상태 간 관계

## 좌석 ↔ 예매

* 좌석 HELD 상태는 특정 예매(PENDING)와 연결됨
* 예매가 CONFIRMED 되면 좌석은 BOOKED 상태로 변경됨
* 예매가 CANCELED 또는 EXPIRED 되면 좌석은 AVAILABLE로 복구됨

---

## 예매 ↔ 결제

* 예매(PENDING)는 결제(READY)와 연결됨
* 결제 APPROVED → 예매 CONFIRMED
* 결제 FAILED → 예매 EXPIRED 또는 유지 (정책 선택)
* 결제 CANCELED → 예매 CANCELED

---

# 5. 설계 의도

* 상태를 통해 모든 흐름을 명확하게 표현
* 각 상태는 단일 책임을 가지도록 분리
* 상태 전이를 통해 정합성 유지
* 예외 상황에서도 일관된 처리 기준 확보

---

# 6. 한 줄 요약

**이 시스템은 상태 전이를 기반으로 예매와 결제의 정합성을 보장한다.**