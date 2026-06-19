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

    @Value("${payment.recovery-scheduler.grace-ms:60000}")
    private long graceMs = 60000;

    @Value("${payment.recovery-scheduler.batch-size:20}")
    private int batchSize = 20;

    @Scheduled(fixedDelayString = "${payment.recovery-scheduler.fixed-delay-ms:60000}")
    public void recoverConfirmingPayments() {
        int recoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(Duration.ofMillis(graceMs), batchSize);
        // 처리할 게 없는 빈 주기는 로그를 더럽히므로 debug 로만 남기고, 실제 보정이 있을 때만 info 로 남긴다.
        if (recoveredCount > 0) {
            log.info("Recover confirming payments completed. recoveredCount={}", recoveredCount);
        } else {
            log.debug("Recover confirming payments completed. recoveredCount={}", recoveredCount);
        }
    }
}
