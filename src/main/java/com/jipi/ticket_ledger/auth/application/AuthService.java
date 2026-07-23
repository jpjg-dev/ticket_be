package com.jipi.ticket_ledger.auth.application;

import com.jipi.ticket_ledger.auth.domain.RefreshToken;
import com.jipi.ticket_ledger.auth.domain.RefreshTokenRepository;
import com.jipi.ticket_ledger.auth.application.port.out.TokenHashEncoder;
import com.jipi.ticket_ledger.auth.application.port.out.TokenProvider;
import com.jipi.ticket_ledger.global.exception.AuthUnauthorizedException;
import com.jipi.ticket_ledger.global.exception.InvalidCredentialsException;
import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.global.log.TraceIdFilter;
import org.slf4j.MDC;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import com.jipi.ticket_ledger.user.domain.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final TokenHashEncoder tokenHashEncoder;
    private final RefreshTokenRepository refreshTokenRepository;

    // 로그인 요청을 검증하고 Access Token, Refresh Token을 발급한 뒤 Refresh Token 해시를 저장한다.
    @Transactional
    public AuthTokens login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("event={} email={} reason={}", LogEvents.AUTH_LOGIN_REJECT, email, "USER_NOT_FOUND");
                    return new InvalidCredentialsException("아이디 또는 비밀번호를 확인해주세요.");
                });

        validateLoginUser(email, password, user);

        String accessToken = tokenProvider.createAccessToken(user.getId());
        String jti = UUID.randomUUID().toString();
        String refreshToken = tokenProvider.createRefreshToken(user.getId(), jti);
        String refreshTokenHash = tokenHashEncoder.hash(refreshToken);

        refreshTokenRepository.save(new RefreshToken(
                user,
                refreshTokenHash,
                jti,
                tokenProvider.getExpirationAsInstant(refreshToken),
                Instant.now()
        ));

        // 로그인 요청엔 인증 토큰이 없어 JwtAuthenticationFilter가 userId를 못 채운다.
        // 성공 시점에 직접 MDC에 넣어, 요청 종료 시 찍히는 접근 로그(AccessLogFilter)에도 누가 로그인했는지 남긴다.
        // (요청 종료 시 TraceIdFilter가 MDC 전체를 정리하므로 누수 없음.)
        MDC.put(TraceIdFilter.USER_ID, String.valueOf(user.getId()));

        log.info("event={} userId={} email={} role={}", LogEvents.AUTH_LOGIN_SUCCESS, user.getId(), email, user.getRole());
        return new AuthTokens(accessToken, refreshToken);
    }

    // Refresh Token을 검증하고 기존 토큰을 폐기한 뒤 새 Access Token과 Refresh Token을 발급한다.
    @Transactional
    public AuthTokens reissue(String refreshToken) {
        // 토큰이 비어있으면 인증 실패로 처리한다.
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("event={} reason={}", LogEvents.AUTH_REISSUE_REJECT, "MISSING_REFRESH_TOKEN");
            throw new AuthUnauthorizedException();
        }

        // 토큰이 유효하지 않거나 Refresh Token이 아니면 재발급을 허용하지 않는다.
        if (!tokenProvider.isValidToken(refreshToken) || !tokenProvider.isRefreshToken(refreshToken)) {
            log.warn("event={} reason={}", LogEvents.AUTH_REISSUE_REJECT, "INVALID_REFRESH_TOKEN");
            throw new AuthUnauthorizedException();
        }

        // 토큰에서 사용자 식별자와 jti를 추출해 DB 조회 키로 사용한다.
        Long userId = tokenProvider.getUserId(refreshToken);
        String jti = tokenProvider.getJti(refreshToken);
        // 토큰 원문을 저장하지 않기 위해 해시값으로 비교한다.
        String refreshTokenHash = tokenHashEncoder.hash(refreshToken);
        // 이후 만료/사용 시각 비교에 사용할 현재 시각을 확보한다.
        Instant now = Instant.now();

        // 사용자 정보가 존재하고 활성 상태인지 확인한다.
        User user = userRepository.findById(userId)
                .filter(foundUser -> foundUser.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(AuthUnauthorizedException::new);

        // 유효한 Refresh Token row 하나만 조건부 갱신해 동일 토큰의 중복 소비를 방지한다.
        int consumedTokenCount = refreshTokenRepository.consumeIfActive(jti, userId, refreshTokenHash, now);
        if (consumedTokenCount != 1) {
            log.warn("event={} userId={} reason={}",
                    LogEvents.AUTH_REISSUE_REJECT, userId, "RT가 이미 사용되었거나 유효하지 않음");
            throw new AuthUnauthorizedException();
        }

        // 새 Access Token을 발급한다.
        String newAccessToken = tokenProvider.createAccessToken(user.getId());
        // 새 Refresh Token의 jti를 생성한다.
        String newJti = UUID.randomUUID().toString();
        // 새 Refresh Token을 발급한다.
        String newRefreshToken = tokenProvider.createRefreshToken(user.getId(), newJti);
        // 새 Refresh Token을 해시로 저장한다.
        String newRefreshTokenHash = tokenHashEncoder.hash(newRefreshToken);

        // 새 Refresh Token 정보를 DB에 저장한다.
        refreshTokenRepository.save(new RefreshToken(
                user,
                newRefreshTokenHash,
                newJti,
                tokenProvider.getExpirationAsInstant(newRefreshToken),
                now
        ));

        // 재발급 성공 로그를 남기고 새 토큰을 반환한다.
        log.info("event={} userId={}", LogEvents.AUTH_REISSUE_SUCCESS, user.getId());
        return new AuthTokens(newAccessToken, newRefreshToken);
    }

    // 로그아웃 요청의 Refresh Token을 찾으면 현재 토큰을 revoke 처리한다.
    @Transactional
    public void logout(String refreshToken) {
        // 요청에 토큰이 없거나 공백이면 로그아웃 처리 없이 종료한다.
        if (refreshToken == null || refreshToken.isBlank()) {
            log.debug("logout ignored because refresh token is missing");
            return;
        }

        // 토큰이 유효하지 않거나 Refresh Token이 아니면 처리하지 않는다.
        if (!tokenProvider.isValidToken(refreshToken) || !tokenProvider.isRefreshToken(refreshToken)) {
            log.warn("event={} reason={}", LogEvents.AUTH_REISSUE_REJECT, "INVALID_LOGOUT_REFRESH_TOKEN");
            return;
        }

        // 토큰에서 사용자 식별자와 jti를 추출해 DB 조회 키로 사용한다.
        Long userId = tokenProvider.getUserId(refreshToken);
        String jti = tokenProvider.getJti(refreshToken);
        // 토큰 원문을 그대로 저장하지 않기 위해 해시값으로 비교한다.
        String refreshTokenHash = tokenHashEncoder.hash(refreshToken);

        // DB에 저장된 토큰이 존재하고, 해시가 일치하며, 아직 폐기되지 않은 경우에만 폐기 처리한다.
        refreshTokenRepository.findByJtiAndUserId(jti, userId)
                // 동일 jti라도 토큰 원문이 다르면 위조/교체 가능성이 있어 제외한다.
                .filter(savedToken -> refreshTokenHash.equals(savedToken.getTokenHash()))
                // 이미 폐기된 토큰은 중복 처리하지 않는다.
                .filter(savedToken -> !savedToken.isRevoked())
                // 조건을 모두 통과한 토큰에 대해 폐기 시점을 기록한다.
                .ifPresent(savedToken -> {
                    savedToken.revoke(Instant.now());
                    log.info("event={} userId={}", LogEvents.AUTH_LOGOUT_SUCCESS, userId);
                });
    }

    // 로그인 가능한 사용자 상태와 비밀번호 일치 여부를 검증한다.
    private void validateLoginUser(String email, String password, User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("event={} email={} reason={}", LogEvents.AUTH_LOGIN_REJECT, email, "INACTIVE_USER");
            // 응답은 제네릭으로 통일 — 계정 존재(비활성) 사실을 노출하지 않는다. 사유는 위 로그에만 남는다.
            throw new InvalidCredentialsException("아이디 또는 비밀번호를 확인해주세요.");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("event={} email={} reason={}", LogEvents.AUTH_LOGIN_REJECT, email, "PASSWORD_MISMATCH");
            throw new InvalidCredentialsException("아이디 또는 비밀번호를 확인해주세요.");
        }
    }
}
