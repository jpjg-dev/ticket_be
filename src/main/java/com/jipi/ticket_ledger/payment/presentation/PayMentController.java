package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.payment.application.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payment API", description = "결제 관련 API")
public class PayMentController {
    private final PaymentService paymentService;

    @Operation(summary = "결제 승인", description = "결제 식별자로 결제를 승인합니다.")
    @PostMapping("/{paymentId}/approve")
    public void approvePayment(@PathVariable Long paymentId) {
        paymentService.approvePayment(paymentId);
    }

    @Operation(summary = "결제 실패", description = "결제 식별자로 결제를 실패 처리합니다.")
    @PostMapping("/{paymentId}/fail")
    public void failPayment(@PathVariable Long paymentId) {
        paymentService.failPayment(paymentId);
    }

    @Operation(summary = "결제 취소", description = "결제 식별자로 결제를 취소합니다.")
    @PostMapping("/{paymentId}/cancel")
    public void cancelPayment(@PathVariable Long paymentId) {
        paymentService.cancelPayment(paymentId);
    }

}
