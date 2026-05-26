package com.jipi.ticket_ledger.reservation.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationExpirationScheduler {

    private final ReservationExpirationService reservationExpirationService;

    @Scheduled(fixedDelayString = "${reservation.expire-scheduler.fixed-delay-ms}")
    public void expireReservations() {
        int expiredCount = reservationExpirationService.expireAll();
        log.info("Expire reservations completed. expiredCount={}", expiredCount);
    }
}
