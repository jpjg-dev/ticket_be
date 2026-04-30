package com.jipi.ticket_ledger.user.presentation;

import com.jipi.ticket_ledger.user.application.UserService;
import com.jipi.ticket_ledger.user.presentation.dto.RequestSignUpDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.catalina.connector.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;

    @Operation(summary = "사용자 생성", description = "사용자 정보를 입력받아 새로운 사용자를 생성합니다.")
    @PostMapping("/signup")
    public ResponseEntity<String> signUp(@RequestBody @Valid RequestSignUpDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.signUp(request));
    }

}
