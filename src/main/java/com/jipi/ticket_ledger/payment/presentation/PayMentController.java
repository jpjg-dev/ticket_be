package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.payment.application.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PayMentController {
    private final PaymentService paymentService;

    @PostMapping("/{paymentId}/approve")
    public void approvePayment(@PathVariable Long paymentId) {
        paymentService.approvePayment(paymentId);
    }

    @PostMapping("/{paymentId}/fail")
    public void failPayment(@PathVariable Long paymentId) {
        paymentService.failPayment(paymentId);
    }

    @PostMapping("/{paymentId}/cancel")
    public void cancelPayment(@PathVariable Long paymentId) {
        paymentService.cancelPayment(paymentId);
    }

}
