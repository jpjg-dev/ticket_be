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
            // liveness와 Prometheus scrape endpoint만 연다. 운영에서는 backend host port를
            // publish하거나 nginx로 route하지 않고 내부 monitoring network에서만 접근한다.
            "/actuator/health/liveness",
            "/actuator/prometheus"
    };
}
