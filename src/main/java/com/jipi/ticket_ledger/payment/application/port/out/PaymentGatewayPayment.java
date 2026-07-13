package com.jipi.ticket_ledger.payment.application.port.out;

public interface PaymentGatewayPayment {

    String paymentKey();

    String orderId();

    String status();

    String method();

    Integer totalAmount();

    String currency();

    PaymentGatewayState state();
}
