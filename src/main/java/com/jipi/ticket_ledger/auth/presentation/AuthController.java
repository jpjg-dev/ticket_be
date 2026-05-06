package com.jipi.ticket_ledger.auth.presentation;

import com.jipi.ticket_ledger.auth.application.AuthService;
import com.jipi.ticket_ledger.auth.infrastructure.AuthCookieProvider;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthRequestLoginDTO;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthResponseLoginDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "Auth API", description = "인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService AuthService;
    private final AuthCookieProvider authCookieProvider;

    @Operation(summary = "사용자 로그인", description = "사용자 이메일과 비밀번호를 입력받아 로그인 처리합니다.")
    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody AuthRequestLoginDTO request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieProvider.createAccessTokenCookie(AuthService.login(request).accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, authCookieProvider.createRefreshTokenCookie(AuthService.login(request).refreshToken()).toString())
                .build();
    }

}
