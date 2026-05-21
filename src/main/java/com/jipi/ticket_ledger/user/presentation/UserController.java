package com.jipi.ticket_ledger.user.presentation;

import com.jipi.ticket_ledger.user.application.UserService;
import com.jipi.ticket_ledger.user.presentation.dto.RequestSignUpDTO;
import com.jipi.ticket_ledger.user.presentation.dto.ResponseMeDTO;
import com.jipi.ticket_ledger.user.presentation.dto.ResponseMyPageDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User API", description = "사용자 정보/관련 API")
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

    @Operation(summary = "사용자 정보 조회", description = "현재 로그인한 사용자의 정보를 조회합니다.")
    @GetMapping("/me")
    public ResponseEntity<ResponseMeDTO> getMyInfo(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(userService.getMyInfo(userId));
    }

    @Operation(summary = "사용자 상세 조회", description = "사용자 ID를 입력받아 해당 사용자의 상세 정보를 조회합니다.")
    @GetMapping("/{userId}")
    public ResponseEntity<ResponseMyPageDTO> getUserInfo(@PathVariable Long userId, @AuthenticationPrincipal Long principalUserId) {
        return ResponseEntity.ok(userService.getUserInfo(userId,principalUserId));
    }
}
