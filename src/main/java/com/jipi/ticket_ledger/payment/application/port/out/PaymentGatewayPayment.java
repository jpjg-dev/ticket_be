package com.jipi.ticket_ledger.payment.application.port.out;

public interface PaymentGatewayPayment {

    String paymentKey();

    String orderId();

    String status();

    String method();

    Integer totalAmount();

    default Integer balanceAmount() {
        return null;
    }

    String currency();

    PaymentGatewayState state();
}
