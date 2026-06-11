# 인증/인가 흐름

## 문서 목적

이 문서는 TicketLedger 백엔드가 HttpOnly Cookie 기반 인증을 어떻게 처리하는지 정리합니다. 포트폴리오 관점에서는 토큰 발급 자체보다 **쿠키 기반 환경에서 재발급, 로그아웃, 권한 검증, CSRF 출처 검증을 어디서 책임지는지**를 보여주는 문서입니다.

## 바로가기

- [핵심 문제](#핵심-문제)
- [로그인 흐름](#로그인-흐름)
- [요청 인증](#요청-인증)
- [사용자 상태 복구](#사용자-상태-복구)
- [재발급과 로그아웃](#재발급과-로그아웃)
- [인가 정책](#인가-정책)
- [CSRF 출처 검증](#csrf-출처-검증)
- [검증 근거](#검증-근거)

## 핵심 문제

| 문제 | 처리 기준 |
| --- | --- |
| 브라우저 JavaScript에서 토큰 원문을 읽지 못합니다. | Access Token과 Refresh Token은 HttpOnly Cookie로만 전달합니다. |
| 같은 Refresh Token으로 동시 재발급이 들어올 수 있습니다. | 조건부 update로 한 번만 소비되게 처리합니다. |
| 프론트 role 값은 조작될 수 있습니다. | 최종 권한 검증은 Spring Security가 처리합니다. |
| Cookie 기반 인증은 CSRF 위험이 있습니다. | 상태 변경 요청의 `Origin`/`Referer`를 검증합니다. |

## 로그인 흐름

```text
POST /api/v1/auth/login
-> 이메일/비밀번호 검증
-> Access Token + Refresh Token 발급
-> HttpOnly Cookie 반환
```

| Cookie | 용도 | 주요 속성 |
| --- | --- | --- |
| `__Host-access_token` | 일반 API 인증 | `HttpOnly`, `Secure`, `SameSite=Lax` |
| `refresh_token` | 재발급/로그아웃 | `HttpOnly`, `Secure`, `SameSite=Lax` |

Refresh Token 원문은 DB에 저장하지 않고 hash 값만 저장합니다.

## 요청 인증

`JwtAuthenticationFilter`가 매 요청마다 Access Token Cookie를 확인합니다.

검증 순서:

1. Access Token Cookie가 있는지 확인합니다.
2. 토큰 타입과 서명을 검증합니다.
3. 토큰에서 `userId`를 추출합니다.
4. DB에서 사용자 존재 여부와 `ACTIVE` 상태를 확인합니다.
5. `SecurityContext`에 principal로 `userId`, authority로 role을 저장합니다.

컨트롤러는 `@AuthenticationPrincipal Long userId`로 현재 로그인 사용자를 받습니다.

## 사용자 상태 복구

프론트는 로그인 직후 또는 새로고침 후 `GET /api/v1/users/me`를 호출해 현재 사용자 정보를 복구합니다.

`/users/me` 응답은 UI 유지에 필요한 최소 정보만 포함합니다.

```text
id, email, name, role, status
```

`role`은 관리자 메뉴 노출 같은 UI 편의에 사용할 수 있지만, 최종 권한 판단은 백엔드 인가 정책이 담당합니다.

## 재발급과 로그아웃

Access Token이 만료되면 프론트는 `POST /api/v1/auth/reissue`로 재발급을 요청합니다.

재발급은 기존 Refresh Token을 조건부 update로 소비합니다.

조건:

```text
jti 일치
userId 일치
token hash 일치
revoked_at is null
expires_at > now
```

영향 row가 `1`이면 최초 소비 요청으로 판단하고 새 토큰 쌍을 발급합니다. 영향 row가 `0`이면 이미 소비됐거나 유효하지 않은 토큰으로 보고 `401`로 거부합니다.

`POST /api/v1/auth/logout`은 Refresh Token을 revoke하고 인증 Cookie를 삭제합니다.

## 인가 정책

`SecurityConfig`는 URL 범위를 나눠 권한을 검사합니다.

| URL 범위 | 정책 |
| --- | --- |
| 관리자 URL | `hasRole("ADMIN")` |
| 사용자 인증 URL | `hasRole("USER")` |
| 공개 URL | 인증 없이 접근 가능 |

Role hierarchy는 `ROLE_ADMIN > ROLE_USER`입니다. 관리자는 사용자 권한 API에도 접근할 수 있습니다.

인증이 없으면 `401 AUTH_REQUIRED`, 권한이 부족하면 `403 ACCESS_DENIED`를 반환합니다.

## CSRF 출처 검증

백엔드는 Cookie 기반 인증을 사용하므로 상태 변경 요청의 출처를 검증합니다.

| 요청 | 처리 |
| --- | --- |
| `GET`, `HEAD`, `TRACE`, `OPTIONS` | 검증 대상에서 제외합니다. |
| `POST`, `PUT`, `PATCH`, `DELETE` | 허용된 `Origin` 또는 `Referer`에서 온 요청만 처리합니다. |

`Origin` 헤더가 있으면 `Origin`을 우선 검증하고, 없으면 `Referer`의 origin을 검증합니다. 둘 다 없거나 허용 목록에 없으면 `403 CSRF_ORIGIN_DENIED`를 반환합니다.

Next.js API Route는 브라우저 요청의 `Origin`/`Referer`를 백엔드 요청으로 전달합니다.

## 검증 근거

- 같은 Refresh Token 동시 재발급 요청 `20`개 중 하나만 성공하고 나머지 `19`개는 거부되도록 검증했습니다.
- 같은 사용자의 서로 다른 Refresh Token은 각각 독립적으로 재발급되는 것을 확인했습니다.
- `users/me`는 인증 principal 기준으로 현재 사용자 정보를 반환하도록 테스트했습니다.
- 마이페이지 조회는 path variable의 `userId`와 principal의 `userId`가 다르면 거부하도록 검증했습니다.
