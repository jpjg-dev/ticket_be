package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.payment.domain.Payment;

record ConfirmingPayment(
        Long paymentId,
        String orderId,
        Long reservationGroupId,
        Integer totalAmountWithVat,
        String currency,
        boolean alreadyApproved
) {
    static ConfirmingPayment alreadyApproved(Long paymentId, String orderId, Long reservationGroupId) {
        return new ConfirmingPayment(paymentId, orderId, reservationGroupId, null, null, true);
    }

    static ConfirmingPayment from(Payment payment) {
        return new ConfirmingPayment(
                payment.getId(), payment.getOrderId(), payment.getReservationGroup().getId(),
                payment.totalAmountWithVat(), payment.getCurrency(), false
        );
    }
}
