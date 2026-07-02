package com.jipi.ticket_ledger.payment.infrastructure;

public record TossCancelResponse(String paymentKey,
                                 String status,
                                 Integer totalAmount,
                                 String currency) {
}
