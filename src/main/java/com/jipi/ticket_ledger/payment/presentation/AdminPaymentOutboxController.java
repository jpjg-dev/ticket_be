package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxAdminService;
import com.jipi.ticket_ledger.payment.presentation.dto.PaymentOutboxRequeueResponse;
import com.jipi.ticket_ledger.payment.presentation.dto.PaymentOutboxRequeueRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import jakarta.validation.Valid;

@Tag(name = "Admin Payment Outbox API", description = "관리자용 결제 Outbox 운영 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/payment-outbox")
public class AdminPaymentOutboxController {

    private final PaymentOutboxAdminService paymentOutboxAdminService;

    @Operation(summary = "HOLD_MANUAL Outbox 이벤트 재처리")
    @PostMapping("/events/{eventId}/requeue")
    public PaymentOutboxRequeueResponse requeue(
            @PathVariable UUID eventId,
            @AuthenticationPrincipal Long adminUserId,
            @Valid @RequestBody PaymentOutboxRequeueRequest request
    ) {
        return PaymentOutboxRequeueResponse.from(
                paymentOutboxAdminService.requeue(eventId, adminUserId, request.reason())
        );
    }
}
