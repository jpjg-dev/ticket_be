package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.global.log.PaymentLogFormatter;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentConfirmTransactionService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentConfirmValidator paymentConfirmValidator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Payment getPayment(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));
    }

    @Transactional
    public ConfirmingPayment markConfirming(String paymentKey, String orderId, Integer amount) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));

        List<Reservation> reservations = getReservationsForPayment(payment);
        Long reservationGroupId = payment.getReservationGroup().getId();
        log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CONFIRM_START, orderId, payment.getId(), reservationGroupId, "REQUEST",
                PaymentLogFormatter.maskPaymentKey(paymentKey));

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} pgStatus={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_SUCCESS, orderId, payment.getId(), reservationGroupId,
                    payment.getPgStatus(), payment.getPgStatus(), PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));
            return ConfirmingPayment.alreadyApproved(payment.getId(), orderId, reservationGroupId);
        }

        if (payment.getStatus() == PaymentStatus.CONFIRMING) {
            return ConfirmingPayment.from(payment);
        }

        paymentConfirmValidator.validate(paymentKey, orderId, amount, payment, reservations, clock.instant());

        payment.confirming();
        return ConfirmingPayment.from(payment);
    }

    @Transactional
    public Payment applyApproved(ConfirmingPayment confirmingPayment, PaymentPgApproval approval) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(confirmingPayment.orderId())
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            return payment;
        }

        if (payment.getStatus() != PaymentStatus.CONFIRMING) {
            throw new IllegalStateException("승인 진행 중인 결제만 확정할 수 있습니다.");
        }

        List<Reservation> reservations = getReservationsForPayment(payment);
        payment.approve(approval.paymentKey(), approval.method(), approval.status());
        payment.getReservationGroup().confirm();
        reservations.forEach(reservation -> {
            reservation.confirm();
            reservation.getSeat().book();
        });

        log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} pgStatus={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CONFIRM_SUCCESS, confirmingPayment.orderId(), payment.getId(),
                payment.getReservationGroup().getId(), approval.status(), approval.status(),
                PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));

        return payment;
    }

    private List<Reservation> getReservationsForPayment(Payment payment) {
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(payment.getReservationGroup().getId());
        if (reservations.isEmpty()) {
            throw new EntityNotFoundException("예매를 찾을 수 없습니다.");
        }
        return reservations;
    }
}
