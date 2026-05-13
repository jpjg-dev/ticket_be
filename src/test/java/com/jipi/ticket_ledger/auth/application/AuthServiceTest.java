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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private TokenHasher tokenHasher;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("login: 정상 로그인 시 토큰을 발급하고 Refresh Token을 저장한다")
    void login_success() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 5, 13, 12, 0);
        User user = org.mockito.Mockito.mock(User.class);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getPassword()).thenReturn("encoded");
        when(user.getId()).thenReturn(1L);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(jwtTokenProvider.createAccessToken(1L)).thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken(eq(1L), anyString())).thenReturn("refresh-token");
        when(tokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
        when(jwtTokenProvider.getExpirationAsLocalDateTime("refresh-token")).thenReturn(expiresAt);

        AuthResponseLoginDTO response = authService.login(new AuthRequestLoginDTO("user@test.com", "password"));

        assertEquals("access-token", response.accessToken());
        assertEquals("refresh-token", response.refreshToken());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken savedToken = captor.getValue();
        assertEquals(user, savedToken.getUser());
        assertEquals("refresh-hash", savedToken.getTokenHash());
        assertNotNull(savedToken.getJti());
        assertEquals(expiresAt, savedToken.getExpiresAt());
    }

    @Test
    @DisplayName("login: 비밀번호가 일치하지 않으면 예외가 발생한다")
    void login_passwordMismatch() {
        User user = org.mockito.Mockito.mock(User.class);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getPassword()).thenReturn("encoded");
        when(passwordEncoder.matches("password", "encoded")).thenReturn(false);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> authService.login(new AuthRequestLoginDTO("user@test.com", "password")));

        assertEquals("비밀번호가 일치하지 않습니다.", exception.getMessage());
        verify(refreshTokenRepository, never()).save(org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("reissue: 기존 Refresh Token을 폐기하고 새 토큰을 발급한다")
    void reissue_refreshTokenRotation() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        User user = org.mockito.Mockito.mock(User.class);
        RefreshToken savedToken = new RefreshToken(user, "old-hash", "old-jti", expiresAt, LocalDateTime.now().minusMinutes(1));

        when(jwtTokenProvider.isValidToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.getUserId("old-refresh")).thenReturn(1L);
        when(jwtTokenProvider.getJti("old-refresh")).thenReturn("old-jti");
        when(tokenHasher.hash("old-refresh")).thenReturn("old-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getId()).thenReturn(1L);
        when(refreshTokenRepository.findByJtiAndUserId("old-jti", 1L)).thenReturn(Optional.of(savedToken));
        when(jwtTokenProvider.createAccessToken(1L)).thenReturn("new-access");
        when(jwtTokenProvider.createRefreshToken(eq(1L), anyString())).thenReturn("new-refresh");
        when(tokenHasher.hash("new-refresh")).thenReturn("new-hash");
        when(jwtTokenProvider.getExpirationAsLocalDateTime("new-refresh")).thenReturn(expiresAt.plusMinutes(30));

        AuthResponseLoginDTO response = authService.reissue("old-refresh");

        assertEquals("new-access", response.accessToken());
        assertEquals("new-refresh", response.refreshToken());
        assertNotNull(savedToken.getRevokedAt());
        assertNotNull(savedToken.getLastUsedAt());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken newToken = captor.getValue();
        assertEquals("new-hash", newToken.getTokenHash());
        assertEquals(user, newToken.getUser());
        assertNotNull(newToken.getJti());
        assertNotEquals("old-jti", newToken.getJti());
    }

    @Test
    @DisplayName("logout: 저장된 Refresh Token을 revoke 처리한다")
    void logout_revokesRefreshToken() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 13, 10, 0);
        User user = org.mockito.Mockito.mock(User.class);
        RefreshToken savedToken = new RefreshToken(user, "token-hash", "logout-jti", now.plusMinutes(10), now.minusMinutes(1));

        when(jwtTokenProvider.isValidToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("refresh-token")).thenReturn(1L);
        when(jwtTokenProvider.getJti("refresh-token")).thenReturn("logout-jti");
        when(tokenHasher.hash("refresh-token")).thenReturn("token-hash");
        when(refreshTokenRepository.findByJtiAndUserId("logout-jti", 1L)).thenReturn(Optional.of(savedToken));

        authService.logout("refresh-token");

        assertNotNull(savedToken.getRevokedAt());
    }
}
