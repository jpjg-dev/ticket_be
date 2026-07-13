package com.jipi.ticket_ledger.payment.application.port.out;

public interface PaymentGateway {

    PaymentGatewayPayment confirm(String paymentKey, String orderId, Integer amount, String idempotencyKey);

    PaymentGatewayPayment cancel(String paymentKey, String cancelReason, String currency, String idempotencyKey);

    PaymentGatewayPayment getPaymentByPaymentKey(String paymentKey);

    PaymentGatewayPayment getPaymentByOrderId(String orderId);
}
