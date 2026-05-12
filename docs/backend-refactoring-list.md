# Backend Refactoring List

TicketLedger 백엔드에서 기능 구현 이후 정리할 리팩토링 항목을 정리한다.

## 1. ReservationGroup 전환 후 레거시 정리

- [ ] `Payment.reservation` 단일 예매 연결 필드 제거 검토
  - 현재 `Payment`는 `reservationGroup` 기준으로 동작하지만, 기존 단일 reservation 호환 코드가 일부 남아 있다.
  - 신규 데이터 흐름이 안정되면 `reservation_id` 컬럼, 생성자, fallback 로직 제거 여부를 결정한다.
- [ ] `PaymentRepository.findByReservationId()` 계열 메서드 제거 검토
  - 현재 만료 처리와 일부 테스트 호환 때문에 남아 있다.
  - 만료 처리를 group 기준으로 바꾼 뒤 제거한다.
- [ ] 로그 키의 `reservationId` 표현 정리
  - 결제 로그는 그룹 기준 흐름이 되었지만 대표 reservation id를 기록하고 있다.
  - `reservationGroupId`를 함께 기록하거나 로그 기준을 group 중심으로 통일한다.

## 2. 결제 서비스 책임 분리

- [ ] `PaymentService.confirmPayment()` 검증/PG 호출/상태전이 분리
  - 현재 승인 검증, PG 승인 요청, timeout 재조회, 내부 상태 전이가 한 메서드에 집중되어 있다.
  - 후보: `PaymentValidator`, `PaymentReconciler`, `PaymentStateTransitionService`.
- [ ] `PaymentService.cancelPayment()` 취소 검증/PG 호출/상태전이 분리
  - 승인 취소와 동일하게 외부 API 처리와 내부 상태 변경 책임을 나눈다.
- [ ] 금액 계산 책임 분리
  - 현재 공급가 합산과 VAT 계산이 `PaymentService` 내부 helper에 있다.
  - 후보: `PaymentAmountCalculator` 또는 `ReservationGroupPricingService`.
- [ ] PG 상태 문자열 처리 상수화
  - `"DONE"`, `"CANCELED"`, `"PARTIAL_CANCELED"` 문자열 비교가 서비스 내부에 흩어져 있다.
  - enum 또는 상수 클래스로 이동한다.

## 3. 예약 만료 처리 group 기준 정리

- [ ] `ReservationService.expireReservations()`를 group/payment 기준으로 정리
  - 현재 만료 대상은 reservation 단위로 조회하고, payment도 `reservationId`로 찾는다.
  - group 기반 결제 모델에서는 같은 group의 reservation들을 한 번에 만료 처리하는 흐름이 더 자연스럽다.
- [ ] 만료 정책 명확화
  - `Payment FAILED -> Reservation EXPIRED 또는 유지` 정책이 문서에 남아 있다.
  - 실제 정책을 확정하고 상태 전이 문서와 코드 테스트를 맞춘다.
- [ ] 만료 시간 기준 통일
  - `ReservationGroup.expiresAt`과 `Reservation.expiresAt`이 함께 존재한다.
  - group 기준으로만 볼지, reservation에도 남길지 결정한다.

## 4. 마이페이지 조회 책임 분리

- [ ] `UserService.getUserInfo()`에서 마이페이지 조립 책임 분리
  - 현재 사용자 검증, 예약 조회, 결제 조회, DTO 매핑이 한 서비스에 있다.
  - 후보: `MyPageQueryService`.
- [ ] 마이페이지 전용 조회 최적화
  - 현재 reservation/payment 조회 후 Java에서 group 매핑을 한다.
  - 데이터가 늘어나면 fetch join, query projection, 전용 repository query를 검토한다.
- [ ] DTO 매핑 로직 분리
  - `ResponseMyPageDTO` 조립 로직을 mapper 또는 정적 factory로 이동할지 검토한다.

## 5. 테스트 보강 및 구조 정리

- [ ] 다중 좌석 group 단위 동시성 테스트 추가
  - 현재 동시성 테스트는 같은 seatId 단일 충돌 중심이다.
  - 예: `[A1, A2]`와 `[A2, A3]` 동시 요청 시 전체 성공/실패 정합성 검증.
- [ ] group 기준 결제 상태 전이 통합 테스트 확장
  - 승인 시 group 내 모든 reservation/seat 상태가 함께 바뀌는지 2석 케이스로 검증한다.
- [ ] 마이페이지 group 응답 테스트 추가
  - `CONFIRMED`, `CANCELED` 예약과 `APPROVED`, `CANCELED` 결제가 group 기준으로 내려오는지 검증한다.
- [ ] 테스트 fixture 생성 유틸 정리
  - user/event/schedule/seat/reservationGroup/reservation 생성 코드가 테스트마다 반복된다.
  - 테스트 전용 fixture builder를 만들어 중복을 줄인다.

## 6. 네이밍/패키지 정리

- [ ] `PayMentController` 클래스명 정리
  - Java 관례상 `PaymentController`가 자연스럽다.
  - 파일명/테스트명까지 함께 변경해야 하므로 별도 리팩토링으로 진행한다.
- [ ] 주석 정리
  - 구현 중 설명용 주석과 실제 유지보수용 주석을 구분한다.
  - 코드로 충분히 읽히는 주석은 제거하고, 정책 설명만 남긴다.
- [ ] 예외 메시지/예외 타입 표준화
  - `IllegalStateException`, `IllegalArgumentException`, `EntityNotFoundException` 사용 기준을 정리한다.
  - API 응답 코드와 에러 코드 정책까지 함께 맞춘다.
