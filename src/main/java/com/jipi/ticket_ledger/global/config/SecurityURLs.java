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
            "/api/v1//payments/**",
    };

    // 아무나 허용
    public static final String[] PUBLIC_URLS = {
            "/api/v1/users/signup",
            "/api/v1/event/**",
            "/api/v1/seat/**",
            "/api/v1/schedules/**"
    };
}
