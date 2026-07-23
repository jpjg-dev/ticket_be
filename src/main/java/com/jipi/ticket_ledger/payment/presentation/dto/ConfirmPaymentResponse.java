package com.jipi.ticket_ledger.payment.presentation.dto;

import com.jipi.ticket_ledger.payment.application.model.PaymentStatusResult;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;

public record ConfirmPaymentResponse(
        Long paymentId,
        String orderId,
        PaymentStatus paymentStatus,
        ReservationStatus reservationStatus,
        SeatStatus seatStatus
) {
    public static ConfirmPaymentResponse from(PaymentStatusResult result) {
        return new ConfirmPaymentResponse(
                result.paymentId(), result.orderId(), result.paymentStatus(),
                result.reservationStatus(), result.seatStatus()
        );
    }
}
