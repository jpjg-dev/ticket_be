package com.jipi.ticket_ledger.auth.application;

import com.jipi.ticket_ledger.auth.domain.RefreshToken;
import com.jipi.ticket_ledger.auth.domain.RefreshTokenRepository;
import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.auth.infrastructure.TokenHasher;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthRequestLoginDTO;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthResponseLoginDTO;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import com.jipi.ticket_ledger.user.domain.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
