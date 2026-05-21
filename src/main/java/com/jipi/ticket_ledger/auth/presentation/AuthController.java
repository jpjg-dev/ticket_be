package com.jipi.ticket_ledger.auth.presentation;

import com.jipi.ticket_ledger.auth.application.AuthService;
import com.jipi.ticket_ledger.auth.infrastructure.AuthCookieNames;
import com.jipi.ticket_ledger.auth.infrastructure.AuthCookieProvider;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthRequestLoginDTO;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthResponseLoginDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth API", description = "인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieProvider authCookieProvider;

    @Operation(summary = "사용자 로그인", description = "사용자 이메일, 비밀번호를 입력받아 로그인 처리합니다.")
    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody AuthRequestLoginDTO request) {
        AuthResponseLoginDTO token = authService.login(request);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieProvider.createAccessTokenCookie(token.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, authCookieProvider.createRefreshTokenCookie(token.refreshToken()).toString())
                .build();
    }

    @Operation(summary = "Access Token 재발급", description = "Refresh Token 쿠키를 기반으로 새로운 Access Token을 발급합니다.")
    @PostMapping("/reissue")
    public ResponseEntity<Void> reissue(@CookieValue(name = AuthCookieNames.REFRESH_TOKEN, required = false) String refreshToken) {
        AuthResponseLoginDTO token = authService.reissue(refreshToken);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieProvider.createAccessTokenCookie(token.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, authCookieProvider.createRefreshTokenCookie(token.refreshToken()).toString())
                .build();
    }

    @Operation(summary = "사용자 로그아웃", description = "Refresh Token을 revoke 처리하고 인증 쿠키를 삭제합니다.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = AuthCookieNames.REFRESH_TOKEN, required = false) String refreshToken) {
        authService.logout(refreshToken);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieProvider.deleteAccessTokenCookie().toString())
                .header(HttpHeaders.SET_COOKIE, authCookieProvider.deleteRefreshTokenCookie().toString())
                .build();
    }
}
