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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationExpirationTransactionService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationGroupRepository reservationGroupRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireGroup(Long reservationGroupId, String trigger) {
        Payment payment = paymentRepository.findByReservationGroupIdForUpdate(reservationGroupId).orElse(null);
        ReservationGroup reservationGroup = reservationGroupRepository.findByIdForUpdate(reservationGroupId).orElse(null);
        if (reservationGroup == null
                || reservationGroup.getStatus() != ReservationGroupStatus.PENDING
                || !reservationGroup.isExpiredAt(clock.instant())) {
            return 0;
        }

        if (payment != null && payment.getStatus() == PaymentStatus.CONFIRMING) {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                    LogEvents.RESERVATION_EXPIRE_START, payment.getOrderId(), payment.getId(), reservationGroup.getId(), "SKIP_CONFIRMING_PAYMENT");
            return 0;
        }

        List<Reservation> pendingReservations = reservationRepository.findByReservationGroupIdWithSeat(reservationGroup.getId()).stream()
                .filter(Reservation::isPending)
                .toList();
        if (pendingReservations.isEmpty()) {
            return 0;
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
        return pendingReservations.size();
    }
}
