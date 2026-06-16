package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryTransactionService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final TossPaymentClient tossPaymentClient;

    @Transactional(readOnly = true)
    public ConfirmingPaymentCandidate loadConfirmingCandidate(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .filter(payment -> payment.getStatus() == PaymentStatus.CONFIRMING)
                .map(payment -> new ConfirmingPaymentCandidate(payment.getId(), payment.getOrderId()))
                .orElse(null);
    }

    @Transactional
    public boolean applyLookupResult(Long paymentId, TossPaymentLookupResponse lookupResponse) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.CONFIRMING) {
            return false;
        }

        List<Reservation> reservations = reservationRepository.findByReservationGroupIdWithSeat(payment.getReservationGroup().getId());

        if (isApprovedLookup(payment, lookupResponse)) {
            if (isReservationStillHeld(payment, reservations)) {
                applyApproval(payment, reservations, lookupResponse.paymentKey(), lookupResponse.method(), lookupResponse.status());
                log.info("Recovered CONFIRMING payment. paymentId={} orderId={} pgStatus={}",
                        payment.getId(), payment.getOrderId(), lookupResponse.status());
                return true;
            }

            if (!refundLostSeatPayment(payment, lookupResponse)) {
                return false;
            }
            failAndRelease(payment, reservations);
            log.warn("Refunded CONFIRMING payment after seat loss. paymentId={} orderId={} pgStatus={}",
                    payment.getId(), payment.getOrderId(), lookupResponse.status());
            return true;
        }

        if (!TossPaymentStatus.isApproved(lookupResponse.status())) {
            failAndRelease(payment, reservations);
            log.warn("Failed CONFIRMING payment after PG lookup. paymentId={} orderId={} pgStatus={}",
                    payment.getId(), payment.getOrderId(), lookupResponse.status());
            return true;
        }

        return false;
    }

    private boolean refundLostSeatPayment(Payment payment, TossPaymentLookupResponse lookupResponse) {
        try {
            tossPaymentClient.cancel(
                    lookupResponse.paymentKey(),
                    "SEAT_UNAVAILABLE",
                    payment.getCurrency(),
                    "cancel:" + payment.getId()
            );
            return true;
        } catch (RestClientException e) {
            log.error("Refund failed for CONFIRMING payment, will retry next cycle. paymentId={} orderId={}",
                    payment.getId(), payment.getOrderId(), e);
            return false;
        }
    }

    private boolean isApprovedLookup(Payment payment, TossPaymentLookupResponse lookupResponse) {
        return TossPaymentStatus.isApproved(lookupResponse.status())
                && payment.getOrderId().equals(lookupResponse.orderId())
                && payment.totalAmountWithVat().equals(lookupResponse.totalAmount())
                && payment.getCurrency().equals(lookupResponse.currency());
    }

    private boolean isReservationStillHeld(Payment payment, List<Reservation> reservations) {
        return payment.getReservationGroup().getStatus() == ReservationGroupStatus.PENDING
                && !payment.getReservationGroup().isExpiredAt(LocalDateTime.now())
                && reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.PENDING)
                && reservations.stream().allMatch(reservation -> reservation.getSeat().getStatus() == SeatStatus.HELD);
    }

    private void applyApproval(Payment payment, List<Reservation> reservations, String paymentKey, String method, String pgStatus) {
        payment.approve(paymentKey, method, pgStatus);
        payment.getReservationGroup().confirm();
        reservations.forEach(reservation -> {
            reservation.confirm();
            reservation.getSeat().book();
        });
    }

    private void failAndRelease(Payment payment, List<Reservation> reservations) {
        payment.fail();
        payment.getReservationGroup().expire();
        reservations.forEach(reservation -> {
            reservation.expire();
            reservation.getSeat().release();
        });
    }
}
