# Backend Refactoring List

TicketLedger 백엔드에서 기능 구현 이후 정리할 리팩토링 항목을 정리한다.
완료된 항목은 이 문서에서 제거한다.

## 1. 결제 서비스 책임 분리

- [ ] `PaymentService.confirmPayment()` 검증/PG 호출/상태전이 분리
  - 현재 승인 검증, PG 승인 요청, timeout 재조회, 내부 상태 전이가 한 메서드에 집중되어 있다.
  - 현재 구조는 `Payment` row 비관적 락으로 동일 `orderId` 승인 요청을 직렬화하고, `READY -> APPROVED`, `Reservation PENDING -> CONFIRMED`, `Seat HELD -> BOOKED` 상태 전이를 한 트랜잭션에서 처리한다.
  - 리팩토링 시 상태 전이 정책과 중복 결제 방어는 유지해야 한다.
  - 멱등 응답 조건은 `Payment APPROVED + group 안의 Reservation CONFIRMED` 조합으로 명확히 정리한다.
  - `Payment READY`인데 Reservation이 이미 `CONFIRMED`인 경우는 성공 응답이 아니라 상태 불일치로 판단할지 검토한다.
  - 단순 클래스 분리는 가독성 개선에는 도움이 되지만, 비관적 락 보유 시간을 줄이지는 못한다.
  - 락 보유 시간을 줄이려면 추후 `CONFIRMING` 중간 상태 도입 후 `READY -> CONFIRMING` 커밋, PG confirm 호출, `CONFIRMING -> APPROVED` 커밋으로 트랜잭션을 분리하는 방안을 검토한다.
  - 다만 현재 포트폴리오 단계에서는 정합성과 구현 명확성을 우선해 기존 상태 모델을 유지한다.
  - 후보: `PaymentValidator`, `PaymentReconciler`, `PaymentStateTransitionService`.
- [ ] `PaymentService.cancelPayment()` 취소 검증/PG 호출/상태전이 분리
  - 승인 취소와 동일하게 외부 API 처리와 내부 상태 변경 책임을 나눈다.
  - 현재 구조는 `Payment` row 비관적 락으로 동일 `paymentId` 취소 요청을 직렬화하고, `APPROVED -> CANCELED`, `Reservation CONFIRMED -> CANCELED`, `Seat BOOKED -> AVAILABLE` 상태 전이를 한 트랜잭션에서 처리한다.
  - 리팩토링 시 중복 취소 요청은 `Payment CANCELED` 상태에서 멱등 응답하고, `APPROVED`가 아닌 상태에서는 취소를 거부하는 정책을 유지한다.
  - 락 보유 시간을 줄이려면 추후 `CANCELING` 중간 상태 도입 후 PG cancel 호출과 내부 상태 전이를 분리하는 방안을 검토한다.
  - 단, 중간 상태 도입 시 timeout/서버 중단 후 복구 배치 또는 PG 조회 기반 보정 로직이 함께 필요하다.
- [ ] 금액 계산 책임 분리
  - 현재 공급가 합산과 VAT 계산이 `PaymentService` 내부 helper에 있다.
  - 후보: `PaymentAmountCalculator` 또는 `ReservationGroupPricingService`.
- [ ] PG 상태 문자열 처리 상수화
  - `"DONE"`, `"CANCELED"`, `"PARTIAL_CANCELED"` 문자열 비교가 서비스 내부에 흩어져 있다.
  - enum 또는 상수 클래스로 이동한다.

## 2. 예약 만료 처리 정책 정리

- [ ] 만료 정책 명확화
  - `Payment FAILED -> Reservation EXPIRED 또는 유지` 정책이 문서에 남아 있다.
  - 실제 정책을 확정하고 상태 전이 문서와 코드 테스트를 맞춘다.
- [ ] 만료 시간 기준 통일
  - `ReservationGroup.expiresAt`과 `Reservation.expiresAt`이 함께 존재한다.
  - group 기준으로만 볼지, reservation에도 남길지 결정한다.

## 3. 마이페이지 조회 책임 분리

- [ ] `UserService.getUserInfo()`에서 마이페이지 조립 책임 분리
  - 현재 사용자 검증, 예약 조회, 결제 조회, DTO 매핑이 한 서비스에 있다.
  - 후보: `MyPageQueryService`.
- [ ] 마이페이지 전용 조회 최적화
  - 현재 reservation/payment 조회 후 Java에서 group 매핑을 한다.
  - 데이터가 늘어나면 fetch join, query projection, 전용 repository query를 검토한다.
- [ ] DTO 매핑 로직 분리
  - `ResponseMyPageDTO` 조립 로직을 mapper 또는 정적 factory로 이동할지 검토한다.

## 4. 테스트 구조 정리

- [ ] 테스트 fixture 생성 유틸 정리
  - user/event/schedule/seat/reservationGroup/reservation 생성 코드가 테스트마다 반복된다.
  - 테스트 전용 fixture builder를 만들어 중복을 줄인다.

## 5. 네이밍/패키지 정리

- [ ] `PayMentController` 클래스명 정리
  - Java 관례상 `PaymentController`가 자연스럽다.
  - 파일명/테스트명까지 함께 변경해야 하므로 별도 리팩토링으로 진행한다.
- [ ] 주석 정리
  - 구현 중 설명용 주석과 실제 유지보수용 주석을 구분한다.
  - 코드로 충분히 읽히는 주석은 제거하고, 정책 설명만 남긴다.
- [ ] 예외 메시지/예외 타입 표준화
  - `IllegalStateException`, `IllegalArgumentException`, `EntityNotFoundException` 사용 기준을 정리한다.
  - API 응답 코드와 에러 코드 정책까지 함께 맞춘다.
