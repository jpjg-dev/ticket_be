# Backend Issue List

TicketLedger 백엔드에서 추적해야 할 항목을 카테고리별로 정리한다.

## 1. 인증/인가

- [ ] `JwtTokenProvider` 단위 테스트 추가
- [ ] `TokenHasher` 단위 테스트 추가
- [ ] 로그인 성공/실패 테스트 추가
- [ ] JWT 인증 필터 테스트 추가
- [ ] 인증 필요 API 접근 테스트 추가
- [ ] Refresh Token rotation 테스트 추가
- [ ] 로그아웃 revoke 테스트 추가
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

- [ ] 마이페이지 조회 API 설계 및 구현
  - 후보: `GET /api/v1/users/mypage`
  - 포함 후보: 회원 요약, 예매 내역, 결제 내역.
- [ ] 사용자별 예매 내역 조회 API 설계
  - 후보: `GET /api/v1/users/me/reservations`
  - 정렬 후보: 최신 예매 또는 최신 결제 기준.
- [ ] 사용자별 결제 내역 조회 API 설계
  - 후보: `GET /api/v1/users/me/payments`
  - 정렬 후보: `payments.requested_at DESC`.

## 4. 예약/좌석

- [ ] 다중 좌석 예약 생성 API 설계 및 구현
  - 현재 예약 생성 API는 단일 `seatId` 기준이다.
  - 프론트는 최대 2석 선택 UI를 갖고 있으나 실제 연동은 단일 좌석 기준이다.
- [ ] 다중 좌석 예약 시 트랜잭션/락 정책 결정
  - 일부 좌석만 성공하는 상황을 허용할지, 전체 실패로 롤백할지 결정한다.
- [ ] 좌석 예약 동시성 테스트 범위 확장
  - 현재 문서화된 동시성 테스트 범위는 `createReservation()`과 동일 `seatId` 동시 요청이다.
  - 다중 좌석 예약 도입 시 좌석 묶음 단위 동시성 검증이 필요하다.

## 5. 결제/상태 전이

- [ ] 다중 좌석 결제 금액 합산 정책 설계
  - 현재 결제 금액은 단일 좌석 가격 기준이다.
  - 다중 좌석 도입 시 공급가, 부가세, 최종 금액 계산 기준을 백엔드에서도 검증해야 한다.
- [ ] 결제 실패 후 Reservation 상태 정책 재검토
  - 현재 문서에는 `Payment FAILED -> Reservation EXPIRED 또는 유지`가 정책 선택 사항으로 남아 있다.
- [ ] Toss Payments 외부 호출 장애 대응 개선
  - 후보: timeout, retry, circuit breaker, 장애 로그 표준화.
  - 외부 호출 대상이 늘어나면 Resilience4j, OpenFeign, WebClient 전환 여부를 재검토한다.

## 6. 테스트/문서

- [ ] `GET /api/v1/users/me` 컨트롤러/서비스 테스트 추가
- [ ] 마이페이지 API 구현 시 컨트롤러/서비스 테스트 추가
- [ ] 다중 좌석 예약/결제 구현 시 상태 전이 테스트 추가
- [ ] 인증/인가 흐름 README 정리
  - HttpOnly Cookie, Access Token/Refresh Token, `/users/me`, Spring Security 권한 검증 흐름을 설명한다.
