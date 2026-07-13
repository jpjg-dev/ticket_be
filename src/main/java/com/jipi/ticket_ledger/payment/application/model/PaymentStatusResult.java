package com.jipi.ticket_ledger.payment.application.model;

import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;

public record PaymentStatusResult(
        Long paymentId,
        String orderId,
        PaymentStatus paymentStatus,
        ReservationStatus reservationStatus,
        SeatStatus seatStatus
) {
}
