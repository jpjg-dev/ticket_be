package com.jipi.ticket_ledger.auth.application.port.out;

import java.time.Instant;

public interface TokenProvider {

    String createAccessToken(Long userId);

    String createRefreshToken(Long userId, String jti);

    Long getUserId(String token);

    String getJti(String token);

    Instant getExpirationAsInstant(String token);

    boolean isRefreshToken(String token);

    boolean isValidToken(String token);
}
