package com.jipi.ticket_ledger.auth.application;

import com.jipi.ticket_ledger.auth.domain.RefreshToken;
import com.jipi.ticket_ledger.auth.domain.RefreshTokenRepository;
import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.auth.infrastructure.TokenHasher;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthRequestLoginDTO;
import com.jipi.ticket_ledger.global.exception.InvalidCredentialsException;

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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
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
        Instant expiresAt = Instant.parse("2026-05-13T03:00:00Z");
        User user = org.mockito.Mockito.mock(User.class);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getPassword()).thenReturn("encoded");
        when(user.getId()).thenReturn(1L);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(jwtTokenProvider.createAccessToken(1L)).thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken(eq(1L), anyString())).thenReturn("refresh-token");
        when(tokenHasher.hash("refresh-token")).thenReturn("refresh-hash");
        when(jwtTokenProvider.getExpirationAsInstant("refresh-token")).thenReturn(expiresAt);

        AuthTokens response = authService.login("user@test.com", "password");

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

        InvalidCredentialsException exception = assertThrows(InvalidCredentialsException.class,
                () -> authService.login("user@test.com", "password"));

        assertEquals("아이디 또는 비밀번호를 확인해주세요.", exception.getMessage());
        verify(refreshTokenRepository, never()).save(org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("reissue: 기존 Refresh Token을 폐기하고 새 토큰을 발급한다")
    void reissue_refreshTokenRotation() {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(30));
        User user = org.mockito.Mockito.mock(User.class);
        RefreshToken savedToken = new RefreshToken(user, "old-hash", "old-jti", expiresAt, Instant.now().minus(Duration.ofMinutes(1)));

        when(jwtTokenProvider.isValidToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.getUserId("old-refresh")).thenReturn(1L);
        when(jwtTokenProvider.getJti("old-refresh")).thenReturn("old-jti");
        when(tokenHasher.hash("old-refresh")).thenReturn("old-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getId()).thenReturn(1L);
        when(refreshTokenRepository.consumeIfActive(eq("old-jti"), eq(1L), eq("old-hash"), any(java.time.Instant.class))).thenReturn(1);
        when(jwtTokenProvider.createAccessToken(1L)).thenReturn("new-access");
        when(jwtTokenProvider.createRefreshToken(eq(1L), anyString())).thenReturn("new-refresh");
        when(tokenHasher.hash("new-refresh")).thenReturn("new-hash");
        when(jwtTokenProvider.getExpirationAsInstant("new-refresh")).thenReturn(expiresAt.plus(Duration.ofMinutes(30)));

        AuthTokens response = authService.reissue("old-refresh");

        assertEquals("new-access", response.accessToken());
        assertEquals("new-refresh", response.refreshToken());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken newToken = captor.getValue();
        assertEquals("new-hash", newToken.getTokenHash());
        assertEquals(user, newToken.getUser());
        assertNotNull(newToken.getJti());
        assertNotEquals("old-jti", newToken.getJti());
    }

    @Test
    @DisplayName("reissue: 이미 소비된 Refresh Token이면 재발급을 거부한다")
    void reissue_rejectsAlreadyConsumedRefreshToken() {
        User user = org.mockito.Mockito.mock(User.class);

        when(jwtTokenProvider.isValidToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.getUserId("old-refresh")).thenReturn(1L);
        when(jwtTokenProvider.getJti("old-refresh")).thenReturn("old-jti");
        when(tokenHasher.hash("old-refresh")).thenReturn("old-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(refreshTokenRepository.consumeIfActive(eq("old-jti"), eq(1L), eq("old-hash"), any(java.time.Instant.class))).thenReturn(0);

        assertThrows(com.jipi.ticket_ledger.global.exception.AuthUnauthorizedException.class,
                () -> authService.reissue("old-refresh"));

        verify(refreshTokenRepository, never()).save(org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("logout: 저장된 Refresh Token을 revoke 처리한다")
    void logout_revokesRefreshToken() {
        Instant now = Instant.parse("2026-05-13T01:00:00Z");
        User user = org.mockito.Mockito.mock(User.class);
        RefreshToken savedToken = new RefreshToken(user, "token-hash", "logout-jti", now.plus(Duration.ofMinutes(10)), now.minus(Duration.ofMinutes(1)));

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
