package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.queue.application.QueueAdmissionPermit;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionService;
import com.jipi.ticket_ledger.queue.application.QueueTemporarilyUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationCommandService {

    private final QueueAdmissionService queueAdmissionService;
    private final SeatReservationCoordinator seatReservationCoordinator;

    public Long createReservation(Long userId, Long scheduleId, List<Long> seatIds, String queueToken) {
        QueueAdmissionPermit permit = queueAdmissionService.claimForReservation(userId, scheduleId, queueToken);
        Long reservationGroupId;
        try {
            reservationGroupId = seatReservationCoordinator.createReservation(userId, scheduleId, seatIds);
        } catch (RuntimeException exception) {
            try {
                queueAdmissionService.release(permit);
            } catch (QueueTemporarilyUnavailableException releaseException) {
                exception.addSuppressed(releaseException);
                log.warn("Queue admission release failed after reservation rejection. userId={} scheduleId={}",
                        userId, scheduleId);
            }
            throw exception;
        }

        try {
            queueAdmissionService.complete(permit);
        } catch (QueueTemporarilyUnavailableException exception) {
            // The reservation transaction already committed. Queue keys expire by TTL, so do not turn success into a retry.
            log.warn("Queue admission cleanup failed after reservation commit. userId={} scheduleId={} reservationGroupId={}",
                    userId, scheduleId, reservationGroupId);
        }
        return reservationGroupId;
    }
}
