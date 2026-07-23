package com.jipi.ticket_ledger.auth.infrastructure;

import com.jipi.ticket_ledger.auth.application.port.out.TokenProvider;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider implements TokenProvider {
    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String ACCESS_TOKEN_TYPE = "ACCESS";
    private static final String REFRESH_TOKEN_TYPE = "REFRESH";

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    // 생성자: 비밀키와 만료시간 설정을 주입받아 초기화한다.
    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    // 액세스 토큰 생성: 사용자 ID와 타입 정보를 담아 서명한다.
    public String createAccessToken(Long userId) {
        long nowMillis = System.currentTimeMillis();

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .issuedAt(new Date(nowMillis))
                .expiration(new Date(nowMillis + accessTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    // 리프레시 토큰 생성: 사용자 ID, 타입, JTI를 담아 서명한다.
    public String createRefreshToken(Long userId, String jti) {
        long nowMillis = System.currentTimeMillis();

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE)
                .id(jti)
                .issuedAt(new Date(nowMillis))
                .expiration(new Date(nowMillis + refreshTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    // 토큰에서 사용자 ID(Subject) 추출
    public Long getUserId(String token) {
        return Long.valueOf(getClaims(token).getSubject());
    }

    // 토큰의 타입(ACCESS/REFRESH) 값 추출
    public String getTokenType(String token) {
        return getClaims(token).get(TOKEN_TYPE_CLAIM, String.class);
    }

    // 토큰의 JTI 값 추출
    public String getJti(String token) {
        return getClaims(token).getId();
    }

    // 토큰의 만료 시각 추출
    public Date getExpiration(String token) {
        return getClaims(token).getExpiration();
    }

    // 토큰의 만료 시각을 RefreshToken 엔티티 저장 형식에 맞춰 변환한다.
    public Instant getExpirationAsInstant(String token) {
        return getExpiration(token).toInstant();
    }

    // 액세스 토큰인지 타입으로 확인한다.
    public boolean isAccessToken(String token) {
        return ACCESS_TOKEN_TYPE.equals(getTokenType(token));
    }

    // 리프레시 토큰인지 타입으로 확인한다.
    public boolean isRefreshToken(String token) {
        return REFRESH_TOKEN_TYPE.equals(getTokenType(token));
    }

    // 서명, 만료, 토큰 형식이 유효한지 확인한다.
    public boolean isValidToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // 토큰을 파싱해 Claims(payload)를 반환
    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
