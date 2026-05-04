package com.jipi.ticket_ledger.auth.presentation;

import com.jipi.ticket_ledger.auth.application.AuthService;
import com.jipi.ticket_ledger.auth.presentation.dto.AuthRequestLoginDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth API", description = "인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService AuthService;

    @Operation(summary = "사용자 로그인", description = "사용자 이메일과 비밀번호를 입력받아 로그인 처리합니다.")
    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody AuthRequestLoginDTO request) {
        //  TODO: 로그인 로직 구현 (JWT 토큰 발급 등)
        AuthService.login(request);
        return ResponseEntity.ok().build();
    }
}
