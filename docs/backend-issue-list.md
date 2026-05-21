# Backend Issue List

TicketLedger 백엔드에서 추적해야 할 항목을 카테고리별로 정리한다.

## 1. 인증/인가

- [x] `JwtTokenProvider` 단위 테스트 추가
  - Access Token 생성/검증 테스트
  - Refresh Token 생성/검증 테스트
- [x] `TokenHasher` 단위 테스트 추가
- [x] 로그인 성공/실패 테스트 추가
- [x] JWT 인증 필터 테스트 추가
- [x] 인증 필요 API 접근 테스트 추가
- [x] Refresh Token rotation 테스트 추가
- [x] 로그아웃 revoke 테스트 추가
- [ ] Refresh Token 재발급 동시 요청 방어 정책 결정 및 구현
  - 같은 Refresh Token으로 `/reissue` 요청이 동시에 들어왔을 때 하나의 요청만 성공하도록 보장할지 결정한다.
  - 후보: `RefreshToken` 조회 시 pessimistic lock 사용, 또는 `revoked_at is null` 조건부 update 사용.
- [ ] CSRF 대응 정책 검토
  - 현재 JWT 쿠키 인증 흐름을 먼저 구현했고, CSRF 대응은 별도 단계에서 검토한다.

## 2. Swagger/Admin

- [ ] Swagger UI 사용 중 Access Token 만료 시 UX 개선
  - 현상: Swagger UI 직접 접근 중 AT가 만료되면 `AUTH_REQUIRED` JSON 응답이 노출된다.
  - 원인: Swagger는 프론트 `appFetch`의 `/auth/reissue` 재발급 흐름을 타지 않는다.
  - 후보: 재로그인 안내, HTML 요청에 한한 로그인/홈 리다이렉트, Swagger 전용 인증 방식, 개발 환경 전용 Basic Auth, 운영 환경 Swagger 비공개 처리.
- [ ] 운영 환경 Swagger 접근 정책 확정
  - 후보: 운영 비활성화, 내부망 제한, 관리자 권한 유지, 별도 문서 배포.

## 3. 사용자/마이페이지 API

- [x] 마이페이지 조회 API 설계 및 구현
  - 최종 경로: `GET /api/v1/users/{userId}`
  - 포함 정보: 예매 내역, 결제 내역.
  - 예매 내역은 `CONFIRMED`, `CANCELED`만 조회한다.
  - 결제 내역은 `APPROVED`, `CANCELED`만 조회한다.
  - 예매/결제 내역은 `reservationGroupId` 기준으로 묶어 내려준다.
  - 결제 취소에 필요한 `paymentId`를 포함한다.

## 4. 예약/좌석

- [x] 다중 좌석 예약 생성 API 설계 및 구현
  - 예약 생성 API는 `seatIds` 배열 기준으로 동작한다.
  - `ReservationGroup` 1건에 `Reservation` N건을 묶는다.
  - 좌석 선택은 최대 2석으로 제한한다.
- [x] 다중 좌석 예약 시 트랜잭션/락 정책 결정
  - 선택 좌석 중 일부만 성공하는 흐름은 허용하지 않고 전체 성공 또는 전체 실패로 처리한다.
  - 좌석은 정렬된 id 기준으로 비관적 락을 잡아 동시성 충돌을 줄인다.
- [x] 좌석 예약 동시성 테스트 범위 확장
  - 겹치는 좌석 묶음 요청 경쟁을 검증한다.
  - 다중 좌석 예약은 하나의 group만 성공하고 나머지는 전체 실패해야 한다.
- [ ] 수동 예약 만료 트리거 정책 명확화
  - 현재 수동 만료 정리는 `POST /api/v1/reservations` 예약 생성 요청 진입 시 `ReservationService.createReservation()`에서 `expireReservations()`를 먼저 호출하는 방식이다.
  - 이 흐름은 UI의 "예매하기" 버튼 자체가 아니라, 사용자가 특정 좌석을 선택해 `seatIds`로 예약 생성을 요청하는 시점에 기존 만료 group을 정리하는 의미다.
  - 정리 대상은 방금 선택한 좌석만이 아니라 `ReservationGroup.expiresAt <= now`인 전체 만료 group이며, 각 group의 pending reservation은 `EXPIRED`, HELD 좌석은 `AVAILABLE`로 복구된다.
  - 정책 결정 필요: 수동 정리를 예약 생성 요청에 계속 둘지, 좌석 조회/선택 단계 또는 별도 관리자/스케줄러 전용 흐름으로 분리할지 결정한다.

## 5. 결제/상태 전이

- [x] 다중 좌석 결제 금액 합산 정책 설계
  - 결제는 `ReservationGroup` 1건 기준으로 생성한다.
  - 공급가는 그룹에 포함된 좌석 가격 합계로 계산한다.
  - 승인 요청 금액은 공급가에 부가세를 더한 금액으로 검증한다.
- [x] 결제 실패 후 Reservation 상태 정책 재검토
  - 결제 실패 시 group이 만료 전이면 reservation/seat 상태는 유지한다.
  - group이 만료된 상태에서 실패 처리되면 pending reservation은 EXPIRED, seat는 AVAILABLE로 복구한다.
- [ ] Toss Payments 외부 호출 장애 대응 개선
  - 후보: timeout, retry, circuit breaker, 장애 로그 표준화.
  - 외부 호출 대상이 늘어나면 Resilience4j, OpenFeign, WebClient 전환 여부를 재검토한다.

## 6. 테스트/문서

- [x] `GET /api/v1/users/me` 컨트롤러/서비스 테스트 추가
- [x] 마이페이지 API 구현 시 컨트롤러/서비스 테스트 추가
- [x] 다중 좌석 예약/결제 구현 시 상태 전이 테스트 추가
- [x] 인증/인가 흐름 README 정리
  - HttpOnly Cookie, Access Token/Refresh Token, `/users/me`, Spring Security 권한 검증 흐름을 설명한다.
- [ ] 마이페이지 조회 N+1 제거 및 `reservation_group_id` 인덱스 검증
  - 현재 `UserService.getUserInfo()`의 `paymentItems` 생성 과정에서 결제 건마다 `reservationsForPayment()`가 추가 조회를 발생시킬 수 있다.
  - 1차 개선 후보: payment 목록의 `reservationGroupId`를 모아 `reservation_group_id in (...)` 조회로 예약 목록을 한 번에 가져온 뒤 Java에서 group 기준으로 묶는다.
  - 단, IN 쿼리만으로는 충분하지 않을 수 있다. PostgreSQL에서 FK 컬럼은 자동 인덱싱되지 않으므로 `reservations.reservation_group_id` 인덱스가 없으면 IN 조회도 풀스캔 가능성이 있다.
  - 인덱스 후보: `reservations(reservation_group_id)`.
  - 트레이드오프: 마이페이지/결제취소/결제내역 같은 group 기준 read 성능은 개선되지만, 예약 생성 시 reservation row마다 인덱스 엔트리도 함께 갱신되어 write 비용이 증가한다.
  - 현재 예약 생성은 반복 `save()`가 아니라 `saveAll()`로 정리되어 있어, 인덱스 적용 시 발생할 수 있는 write 비용 증가를 일부 완화할 수 있는 구조다.
  - 검증 기준: 인덱스 적용 전후 `EXPLAIN ANALYZE`, 쿼리 호출 횟수, 마이페이지 조회 시간, 예약 생성 write 시간, p95 응답 시간을 함께 비교한다.
  - 판단 기준: read 성능 개선 폭이 write 성능 손실보다 도메인상 더 큰 가치가 있는지 수치로 확인한 뒤 적용 여부를 결정한다.
