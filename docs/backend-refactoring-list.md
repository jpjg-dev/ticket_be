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

## 7. 환경별 설정 분리

- [ ] 개발/배포 환경변수 분리
  - 현재 `.env` 중심으로 로컬 실행과 배포 설정이 섞일 수 있다.
  - 개발 환경과 배포 환경을 `.env.dev`, `.env.prod`처럼 분리하는 방안을 검토한다.
  - DB URL, JWT secret, Toss secret key, 서버 포트, 쿠키 Secure/SameSite 정책처럼 환경마다 달라지는 값을 명확히 나눈다.
  - Docker Compose, GCP 배포, 로컬 개발 실행에서 어떤 env 파일을 사용할지 문서화한다.
