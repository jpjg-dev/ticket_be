package com.jipi.ticket_ledger.user.application.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record ResponseMyPageDTO(
        List<ReservationGroupItem> reservations,
        List<PaymentItem> payments
) {
    public record ReservationGroupItem(
            Long reservationGroupId,
            String status,
            String eventTitle,
            String venue,
            LocalDateTime scheduleStartAt,
            List<SeatItem> seats
    ) {
    }

    public record SeatItem(
            String seatNumber,
            String seatGrade
    ) {
    }

    public record PaymentItem(
            Long reservationGroupId,
            Long paymentId,
            String status,
            Integer amount,
            String method,
            Instant requestedAt,
            List<SeatItem> seats
    ) {
    }
}
