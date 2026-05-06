package com.jipi.ticket_ledger.auth.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AuthCookieProvider {

    public AuthCookieProvider(
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration
    ) {
        this.ACCESS_TOKEN_MAX_AGE = Duration.ofMillis(accessTokenExpiration);
        this.REFRESH_TOKEN_MAX_AGE = Duration.ofMillis(refreshTokenExpiration);
    }

    private final String ACCESS_TOKEN_COOKIE_NAME = "__Host-access_token";
    private final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private final Duration ACCESS_TOKEN_MAX_AGE;
    private final Duration REFRESH_TOKEN_MAX_AGE;

    // Access Token을 일반 API 요청에 사용할 수 있도록 루트 경로 쿠키로 생성한다.
    public ResponseCookie createAccessTokenCookie(String accessToken) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, accessToken)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(ACCESS_TOKEN_MAX_AGE)
                .sameSite("Lax")
                .build();
    }

    // Refresh Token이 auth 경로 요청에만 전송되도록 Path를 제한한 쿠키로 생성한다.
    public ResponseCookie createRefreshTokenCookie(String refreshToken) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(true)
                .path("/api/v1/auth")
                .maxAge(REFRESH_TOKEN_MAX_AGE)
                .sameSite("Lax")
                .build();
    }
}
