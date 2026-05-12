package com.jipi.ticket_ledger.user.presentation.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ResponseMyPageDTO(
        List<ReservationItem> reservations,
        List<PaymentItem> payments
) {
    public record ReservationItem(
            String status,
            String eventTitle,
            String venue,
            LocalDateTime scheduleStartAt,
            String seatNumber,
            String seatGrade
    ) {
    }

    public record PaymentItem(
            Long reservationId,
            Long paymentId,
            String status,
            Integer amount,
            String method,
            LocalDateTime requestedAt
    ) {
    }
}
