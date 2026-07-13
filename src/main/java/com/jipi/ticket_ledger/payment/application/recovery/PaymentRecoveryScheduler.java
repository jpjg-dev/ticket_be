package com.jipi.ticket_ledger.payment.application.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.IntSupplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryScheduler {

    private final PaymentRecoveryService paymentRecoveryService;

    @Value("${payment.recovery-scheduler.grace-ms}")
    private long graceMs;

    @Value("${payment.recovery-scheduler.batch-size}")
    private int batchSize;

    // Spring 기본 TaskScheduler 는 1스레드라 @Scheduled 를 쪼개도 직렬이므로, 한 주기에서 confirm → cancel 배치를
    // 순차 실행하고 backlog gauge 를 갱신한다. 설정 노브(payment.recovery-scheduler.*)는 두 배치가 공유한다.
    @Scheduled(fixedDelayString = "${payment.recovery-scheduler.fixed-delay-ms}")
    public void recoverGrayZonePayments() {
        Duration grace = Duration.ofMillis(graceMs);
        // 배치·게이지를 서로 독립 실행한다. per-item 격리는 배치 내부에 있지만, 후보 조회 같은 루프 밖 실패가
        // 다른 배치와 게이지 갱신까지 건너뛰지 않게 각각 격리한다(다음 주기 자동 재시도).
        int confirmingRecovered = runBatch("confirming", () -> paymentRecoveryService.reconcileStaleConfirmingPayments(grace, batchSize));
        int cancelingRecovered = runBatch("canceling", () -> paymentRecoveryService.reconcileStaleCancelingPayments(grace, batchSize));
        updateBacklogGauges();

        // 처리할 게 없는 빈 주기는 로그를 더럽히므로 debug 로만 남기고, 실제 보정이 있을 때만 info 로 남긴다.
        if (confirmingRecovered + cancelingRecovered > 0) {
            log.info("Recover gray-zone payments completed. confirmingRecovered={} cancelingRecovered={}",
                    confirmingRecovered, cancelingRecovered);
        } else {
            log.debug("Recover gray-zone payments completed. confirmingRecovered={} cancelingRecovered={}",
                    confirmingRecovered, cancelingRecovered);
        }
    }

    private int runBatch(String operation, IntSupplier batch) {
        try {
            return batch.getAsInt();
        } catch (Exception e) {
            log.error("Gray-zone recovery batch failed, skipping to next cycle. operation={}", operation, e);
            return 0;
        }
    }

    private void updateBacklogGauges() {
        try {
            paymentRecoveryService.updateBacklogGauges();
        } catch (Exception e) {
            // 게이지 갱신 실패는 보정 자체와 무관하므로 삼키고 다음 주기에 재시도한다(게이지는 1주기 stale).
            log.error("Failed to update gray-zone backlog gauges.", e);
        }
    }
}
