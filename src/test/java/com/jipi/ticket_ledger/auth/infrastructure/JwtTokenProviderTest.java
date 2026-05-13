package com.jipi.ticket_ledger.auth.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        String secret = "test-secret-key-test-secret-key-test-secret-key-1234567890";
        jwtTokenProvider = new JwtTokenProvider(secret, 60000L, 120000L);
    }

    @Test
    @DisplayName("Access Token 생성 시 사용자 ID와 타입이 포함된다")
    void createAccessToken_containsUserIdAndType() {
        String token = jwtTokenProvider.createAccessToken(1L);

        assertTrue(jwtTokenProvider.isValidToken(token));
        assertTrue(jwtTokenProvider.isAccessToken(token));
        assertEquals(1L, jwtTokenProvider.getUserId(token));
    }

    @Test
    @DisplayName("Refresh Token 생성 시 jti와 타입이 포함된다")
    void createRefreshToken_containsJtiAndType() {
        String token = jwtTokenProvider.createRefreshToken(1L, "jti-123");

        assertTrue(jwtTokenProvider.isValidToken(token));
        assertTrue(jwtTokenProvider.isRefreshToken(token));
        assertEquals("jti-123", jwtTokenProvider.getJti(token));
    }

    @Test
    @DisplayName("유효하지 않은 토큰은 검증에 실패한다")
    void invalidToken_returnsFalse() {
        assertFalse(jwtTokenProvider.isValidToken("invalid.token"));
    }
}
