# 인증/인가 흐름 README

TicketLedger 백엔드는 Spring Security와 JWT Cookie 기반 인증을 사용한다.

## 로그인

- `POST /api/v1/auth/login`에서 이메일/비밀번호를 검증한다.
- 성공하면 Access Token과 Refresh Token을 `HttpOnly`, `Secure`, `SameSite=Lax` Cookie로 내려준다.
- Access Token Cookie 이름은 `__Host-access_token`이고 일반 API 요청에 사용한다.
- Refresh Token Cookie 이름은 `refresh_token`이고 재발급/로그아웃 흐름에 사용한다.
- Refresh Token 원문은 DB에 저장하지 않고 hash 값만 저장한다.

## 요청 인증

- `JwtAuthenticationFilter`가 매 요청마다 Access Token Cookie를 확인한다.
- 토큰이 유효하고 Access Token 타입이면 userId를 추출한다.
- DB에서 사용자 존재 여부와 `ACTIVE` 상태를 확인한다.
- 검증이 끝나면 `SecurityContext`에 principal로 userId, authority로 사용자 role을 저장한다.
- 컨트롤러는 `@AuthenticationPrincipal Long userId`로 현재 로그인 사용자를 받는다.

## 사용자 세션 정보

- 프론트는 로그인 직후 또는 새로고침 후 `GET /api/v1/users/me`를 호출해 현재 사용자 정보를 가져온다.
- `/users/me` 응답은 프론트 UI 상태 유지에 필요한 최소 정보만 포함한다.
- 현재 포함 값은 `id`, `email`, `name`, `role`, `status`이다.
- 권한 판단의 최종 기준은 백엔드 Spring Security이며, 프론트 role 값은 메뉴 노출 같은 UI 편의 용도다.

## 재발급과 로그아웃

- Access Token이 만료되면 프론트는 `POST /api/v1/auth/reissue`로 재발급을 요청한다.
- 재발급은 Refresh Token Cookie를 검증하고, 기존 Refresh Token을 revoke한 뒤 새 토큰 쌍을 발급한다.
- `POST /api/v1/auth/logout`은 Refresh Token을 revoke하고 인증 Cookie를 삭제한다.

## 인가 정책

- `SecurityConfig`는 `ADMIN_URLS`, `PUBLIC_URLS`, `AUTHENTICATED_URLS` 순서로 요청 권한을 검사한다.
- 관리자 URL은 `hasRole("ADMIN")`으로 보호한다.
- 사용자 인증 URL은 `hasRole("USER")`로 보호한다.
- Role hierarchy는 `ROLE_ADMIN > ROLE_USER`이므로 관리자는 사용자 권한 API에도 접근할 수 있다.
- 인증이 없으면 `401 AUTH_REQUIRED`, 권한이 부족하면 `403 ACCESS_DENIED`를 반환한다.

## CSRF 출처 검증

- 백엔드는 HttpOnly Cookie 기반 인증을 사용하므로 CSRF 위험을 고려한다.
- Spring Security의 기본 CSRF Token 검증은 사용하지 않고, 현재 구조에서는 상태 변경 요청의 `Origin`/`Referer`를 검증한다.
- `GET`, `HEAD`, `TRACE`, `OPTIONS`는 검증 대상에서 제외한다.
- `POST`, `PUT`, `PATCH`, `DELETE` 요청은 허용된 출처에서 온 요청만 처리한다.
- `Origin` 헤더가 있으면 `Origin`을 우선 검증하고, 없으면 `Referer`의 origin을 검증한다.
- 둘 다 없거나 허용 목록에 없으면 `403 CSRF_ORIGIN_DENIED`를 반환한다.
- 허용 출처는 환경별 설정의 `security.csrf-origin.allowed-origins`에서 관리한다.
- Next.js API Route는 브라우저 요청의 `Origin`/`Referer`를 백엔드 요청으로 전달한다.
