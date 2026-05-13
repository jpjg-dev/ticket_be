# Backend Refactoring List

TicketLedger 백엔드에서 기능 구현 이후 정리할 리팩토링 항목을 정리한다.

## 1. ReservationGroup 전환 후 레거시 정리

- [x] `Payment`의 이전 연결 필드 제거
  - 결제는 `ReservationGroup`만 참조한다.
  - 이전 생성자와 fallback 로직을 제거했다.
- [x] `PaymentRepository` 이전 조회 메서드 제거
  - 결제 중복 조회와 만료 처리는 `reservationGroupId` 기준으로 수행한다.
- [x] 결제 로그 키 group 기준 정리
  - 결제 승인/실패/취소 로그는 `reservationGroupId`를 기록한다.

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

- [x] `ReservationService.expireReservations()`를 group/payment 기준으로 정리
  - 만료 대상은 `ReservationGroup.expiresAt` 기준으로 조회한다.
  - 같은 group의 pending reservation들을 한 번에 만료 처리한다.
  - 연결된 READY payment는 group 기준으로 FAILED 처리한다.
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

- [x] 다중 좌석 group 단위 동시성 테스트 추가
  - `[A1, A2]`와 `[A2, A3]` 동시 요청 시 하나의 group만 성공하는지 검증한다.
- [x] group 기준 결제 상태 전이 통합 테스트 확장
  - 승인/취소 시 group 내 모든 reservation/seat 상태가 함께 바뀌는지 2석 케이스로 검증한다.
- [x] 마이페이지 group 응답 테스트 추가
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
