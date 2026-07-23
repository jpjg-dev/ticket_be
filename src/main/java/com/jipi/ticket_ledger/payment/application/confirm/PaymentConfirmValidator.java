package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.global.log.PaymentLogFormatter;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class PaymentConfirmValidator {

    public void validate(
            String paymentKey,
            String orderId,
            Integer amount,
            Payment payment,
            List<Reservation> reservations,
            Instant now
    ) {
        Long reservationGroupId = payment.getReservationGroup().getId();
        if (payment.getStatus() != PaymentStatus.READY) {
            reject(paymentKey, orderId, payment, reservationGroupId, "INVALID_PAYMENT_STATUS",
                    "결제 대기 상태에서만 승인할 수 있습니다.");
        }
        if (reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.CONFIRMED)) {
            reject(paymentKey, orderId, payment, reservationGroupId, "PAYMENT_READY_BUT_RESERVATION_CONFIRMED",
                    "결제와 예매 상태가 일치하지 않습니다.");
        }
        if (reservations.stream().anyMatch(reservation -> reservation.getStatus() != ReservationStatus.PENDING)) {
            reject(paymentKey, orderId, payment, reservationGroupId, "INVALID_RESERVATION_STATUS",
                    "결제 대기 중인 예약만 승인할 수 있습니다.");
        }
        if (payment.getReservationGroup().isExpiredAt(now)) {
            reject(paymentKey, orderId, payment, reservationGroupId, "RESERVATION_EXPIRED",
                    "예약 시간이 만료되어 결제를 승인할 수 없습니다.");
        }
        if (!payment.totalAmountWithVat().equals(amount)) {
            reject(paymentKey, orderId, payment, reservationGroupId, "AMOUNT_MISMATCH",
                    "결제 금액이 일치하지 않습니다.");
        }
    }

    private void reject(String paymentKey, String orderId, Payment payment, Long reservationGroupId,
                        String reason, String message) {
        log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CONFIRM_REJECT, orderId, payment.getId(), reservationGroupId,
                reason, PaymentLogFormatter.maskPaymentKey(paymentKey));
        throw new IllegalStateException(message);
    }
}
