package com.jipi.ticket_ledger.global.config;

public final class SecurityURLs {
    private SecurityURLs() {
    }

    // 관리자 허용
    public static final String[] ADMIN_URLS = {
            "/admin/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/api-docs/**"
    };

    // 유저 허용
    public static final String[] AUTHENTICATED_URLS = {
            "/api/v1/reservations/**",
            "/api/v1/payments/**",
            "/api/v1/users/{userId}", //MyPage
            "/api/v1/users/me",
    };

    // 아무나 허용
    public static final String[] PUBLIC_URLS = {
            "/api/v1/users/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/logout",
            "/api/v1/event/**",
            "/api/v1/seat/**",
            "/api/v1/schedules/**",
            "/api/v1/auth/reissue",
            // 컨테이너 헬스체크가 쓰는 liveness 프로브만 연다.
            // bare /actuator/health, metrics/env 등 나머지 actuator 는 어떤 규칙에도
            // 안 걸려 anyRequest().denyAll() 로 잠긴다.
            "/actuator/health/liveness"
    };
}
