package com.jipi.ticket_ledger.payment.application.confirm;

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
}
