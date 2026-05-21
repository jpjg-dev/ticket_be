package com.jipi.ticket_ledger.payment.presentation.dto;

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
}
