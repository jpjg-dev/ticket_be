package com.jipi.ticket_ledger.reservation.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ReservationExpirationScheduler {

    private final ReservationExpirationService reservationExpirationService;

    @Scheduled(fixedDelayString = "${reservation.expire-scheduler.fixed-delay-ms}")
    public void expireReservations() {
        int expiredCount = reservationExpirationService.expireAll();
        // 처리할 게 없는 빈 주기는 로그를 더럽히므로 debug 로만 남기고, 실제 만료가 있을 때만 info 로 남긴다.
        if (expiredCount > 0) {
            log.info("Expire reservations completed. expiredCount={}", expiredCount);
        } else {
            log.debug("Expire reservations completed. expiredCount={}", expiredCount);
        }
    }
}
