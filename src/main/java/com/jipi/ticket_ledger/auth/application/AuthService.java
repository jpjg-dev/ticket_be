package com.jipi.ticket_ledger.auth.application;

import com.jipi.ticket_ledger.auth.domain.RefreshToken;
import com.jipi.ticket_ledger.auth.domain.RefreshTokenRepository;
import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.auth.infrastructure.TokenHasher;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthRequestLoginDTO;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthResponseLoginDTO;
import com.jipi.ticket_ledger.global.exception.AuthUnauthorizedException;
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

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenHasher tokenHasher;
    private final RefreshTokenRepository refreshTokenRepository;

    // 로그인 요청을 검증하고 Access Token, Refresh Token을 발급한 뒤 Refresh Token 해시를 저장한다.
    @Transactional
    public AuthResponseLoginDTO login(AuthRequestLoginDTO request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalStateException("존재하지 않는 이메일입니다."));

        validateLoginUser(request, user);

        String accessToken = jwtTokenProvider.createAccessToken(user.getId());
        String jti = UUID.randomUUID().toString();
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), jti);
        String refreshTokenHash = tokenHasher.hash(refreshToken);

        refreshTokenRepository.save(new RefreshToken(
                user,
                refreshTokenHash,
                jti,
                jwtTokenProvider.getExpirationAsLocalDateTime(refreshToken),
                LocalDateTime.now()
        ));

        log.info("로그인 성공: email={}", request.email());
        return new AuthResponseLoginDTO(accessToken, refreshToken);
    }

    // Refresh Token을 검증하고 기존 토큰을 폐기한 뒤 새 Access Token과 Refresh Token을 발급한다.
    @Transactional
    public AuthResponseLoginDTO reissue(String refreshToken) {
        // 토큰이 비어있으면 인증 실패로 처리한다.
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthUnauthorizedException();
        }

        // 토큰이 유효하지 않거나 Refresh Token이 아니면 재발급을 허용하지 않는다.
        if (!jwtTokenProvider.isValidToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new AuthUnauthorizedException();
        }

        // 토큰에서 사용자 식별자와 jti를 추출해 DB 조회 키로 사용한다.
        Long userId = jwtTokenProvider.getUserId(refreshToken);
        String jti = jwtTokenProvider.getJti(refreshToken);
        // 토큰 원문을 저장하지 않기 위해 해시값으로 비교한다.
        String refreshTokenHash = tokenHasher.hash(refreshToken);
        // 이후 만료/사용 시각 비교에 사용할 현재 시각을 확보한다.
        LocalDateTime now = LocalDateTime.now();

        // 사용자 정보가 존재하고 활성 상태인지 확인한다.
        User user = userRepository.findById(userId)
                .filter(foundUser -> foundUser.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(AuthUnauthorizedException::new);

        // DB에 저장된 Refresh Token이 존재하고 유효한지 검증한다.
        RefreshToken savedToken = refreshTokenRepository.findByJtiAndUserId(jti, userId)
                // 해시가 일치해야 같은 토큰으로 인정한다.
                .filter(foundToken -> refreshTokenHash.equals(foundToken.getTokenHash()))
                // 이미 폐기된 토큰은 재사용하지 않는다.
                .filter(foundToken -> !foundToken.isRevoked())
                // 만료된 토큰이면 재발급을 허용하지 않는다.
                .filter(foundToken -> !foundToken.isExpiredAt(now))
                .orElseThrow(AuthUnauthorizedException::new);

        // 재발급 처리 시점을 기록한다.
        savedToken.markUsed(now);
        // 기존 Refresh Token은 즉시 폐기한다(재사용 방지).
        savedToken.revoke(now);

        // 새 Access Token을 발급한다.
        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId());
        // 새 Refresh Token의 jti를 생성한다.
        String newJti = UUID.randomUUID().toString();
        // 새 Refresh Token을 발급한다.
        String newRefreshToken = jwtTokenProvider.createRefreshToken(user.getId(), newJti);
        // 새 Refresh Token을 해시로 저장한다.
        String newRefreshTokenHash = tokenHasher.hash(newRefreshToken);

        // 새 Refresh Token 정보를 DB에 저장한다.
        refreshTokenRepository.save(new RefreshToken(
                user,
                newRefreshTokenHash,
                newJti,
                jwtTokenProvider.getExpirationAsLocalDateTime(newRefreshToken),
                now
        ));

        // 재발급 성공 로그를 남기고 새 토큰을 반환한다.
        log.info("토큰 재발급 성공: userId={}", user.getId());
        return new AuthResponseLoginDTO(newAccessToken, newRefreshToken);
    }

    // 로그아웃 요청의 Refresh Token을 찾으면 현재 토큰을 revoke 처리한다.
    @Transactional
    public void logout(String refreshToken) {
        // 요청에 토큰이 없거나 공백이면 로그아웃 처리 없이 종료한다.
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        // 토큰이 유효하지 않거나 Refresh Token이 아니면 처리하지 않는다.
        if (!jwtTokenProvider.isValidToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            return;
        }

        // 토큰에서 사용자 식별자와 jti를 추출해 DB 조회 키로 사용한다.
        Long userId = jwtTokenProvider.getUserId(refreshToken);
        String jti = jwtTokenProvider.getJti(refreshToken);
        // 토큰 원문을 그대로 저장하지 않기 위해 해시값으로 비교한다.
        String refreshTokenHash = tokenHasher.hash(refreshToken);

        // DB에 저장된 토큰이 존재하고, 해시가 일치하며, 아직 폐기되지 않은 경우에만 폐기 처리한다.
        refreshTokenRepository.findByJtiAndUserId(jti, userId)
                // 동일 jti라도 토큰 원문이 다르면 위조/교체 가능성이 있어 제외한다.
                .filter(savedToken -> refreshTokenHash.equals(savedToken.getTokenHash()))
                // 이미 폐기된 토큰은 중복 처리하지 않는다.
                .filter(savedToken -> !savedToken.isRevoked())
                // 조건을 모두 통과한 토큰에 대해 폐기 시점을 기록한다.
                .ifPresent(savedToken -> savedToken.revoke(LocalDateTime.now()));
    }

    // 로그인 가능한 사용자 상태와 비밀번호 일치 여부를 검증한다.
    private void validateLoginUser(AuthRequestLoginDTO request, User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("활성화된 사용자만 로그인할 수 있습니다.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalStateException("비밀번호가 일치하지 않습니다.");
        }
    }
}
