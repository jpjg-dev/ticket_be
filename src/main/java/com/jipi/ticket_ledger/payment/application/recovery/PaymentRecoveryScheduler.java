package com.jipi.ticket_ledger.payment.application.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryScheduler {

    private final PaymentRecoveryService paymentRecoveryService;

    @Value("${payment.recovery-scheduler.grace-ms}")
    private long graceMs;

    @Scheduled(fixedDelayString = "${payment.recovery-scheduler.fixed-delay-ms}")
    public void recoverConfirmingPayments() {
        int recoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(Duration.ofMillis(graceMs));
        log.info("Recover confirming payments completed. recoveredCount={}", recoveredCount);
    }
}
