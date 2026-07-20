package com.jipi.ticket_ledger.featureflag.presentation;

import com.jipi.ticket_ledger.featureflag.application.FeatureFlagService;
import com.jipi.ticket_ledger.featureflag.presentation.dto.QueueModeResponse;
import com.jipi.ticket_ledger.featureflag.presentation.dto.UpdateQueueModeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Feature Flag API", description = "관리자용 큐 롤아웃 기능 플래그 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/feature-flags")
public class AdminFeatureFlagController {

    private final FeatureFlagService featureFlagService;

    @Operation(summary = "현재 큐 모드 조회")
    @GetMapping
    public QueueModeResponse getFeatureFlags() {
        return QueueModeResponse.from(featureFlagService.getCurrentQueueMode());
    }

    @Operation(summary = "큐 모드 변경")
    @PutMapping("/queue-mode")
    public QueueModeResponse updateQueueMode(@Valid @RequestBody UpdateQueueModeRequest request) {
        return QueueModeResponse.from(featureFlagService.updateQueueMode(request.mode(), request.expectedVersion()));
    }
}
