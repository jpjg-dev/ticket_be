package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReservationExpirationService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationGroupRepository reservationGroupRepository;

    public int expireAll() {
        return expireGroups(
                reservationGroupRepository.findExpiredPendingIds(LocalDateTime.now()),
                "SCHEDULER"
        );
    }

    public int expireByScheduleId(Long scheduleId) {
        return expireGroups(
                reservationGroupRepository.findExpiredPendingIdsByScheduleId(scheduleId, LocalDateTime.now()),
                "SEAT_QUERY"
        );
    }

    private int expireGroups(List<Long> expiredGroupIds, String trigger) {
        int expiredCount = 0;
        for (Long reservationGroupId : expiredGroupIds) {
            Payment payment = paymentRepository.findByReservationGroupIdForUpdate(reservationGroupId).orElse(null);
            ReservationGroup reservationGroup = payment != null
                    ? reservationGroupRepository.findById(reservationGroupId).orElse(null)
                    : reservationGroupRepository.findByIdForUpdate(reservationGroupId).orElse(null);
            if (reservationGroup == null
                    || reservationGroup.getStatus() != ReservationGroupStatus.PENDING
                    || !reservationGroup.isExpiredAt(LocalDateTime.now())) {
                continue;
            }

            List<Reservation> pendingReservations = reservationRepository.findByReservationGroupId(reservationGroup.getId()).stream()
                    .filter(Reservation::isPending)
                    .toList();
            if (pendingReservations.isEmpty()) {
                continue;
            }

            String orderId = payment != null ? payment.getOrderId() : "N/A";
            Long paymentId = payment != null ? payment.getId() : null;
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                    LogEvents.RESERVATION_EXPIRE_START, orderId, paymentId, reservationGroup.getId(), trigger);

            if (payment != null && payment.getStatus() == PaymentStatus.READY) {
                payment.fail();
                log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                        LogEvents.PAYMENT_EXPIRE_SUCCESS, orderId, paymentId, reservationGroup.getId(), "READY_TO_FAILED");
            }

            reservationGroup.expire();
            pendingReservations.forEach(reservation -> {
                reservation.expire();
                reservation.getSeat().release();
            });
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} expiredCount={} reason={}",
                    LogEvents.RESERVATION_EXPIRE_SUCCESS, orderId, paymentId, reservationGroup.getId(), pendingReservations.size(), trigger);
            expiredCount += pendingReservations.size();
        }
        return expiredCount;
    }
}
