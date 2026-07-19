package com.jipi.ticket_ledger.admin.presentation;

import com.jipi.ticket_ledger.admin.presentation.dto.AdminAccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin API", description = "관리자 접근 확인 API")
@RestController
@RequestMapping("/admin")
public class AdminAccessController {

    @Operation(summary = "관리자 접근 확인", description = "관리자 권한이 확인되면 접근 가능 여부를 반환합니다.")
    @GetMapping
    public ResponseEntity<AdminAccessResponse> confirmAccess() {
        return ResponseEntity.ok(new AdminAccessResponse(true));
    }
}
