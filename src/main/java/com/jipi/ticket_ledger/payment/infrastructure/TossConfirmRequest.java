package com.jipi.ticket_ledger.payment.infrastructure;

public record TossConfirmRequest(String paymentKey, String orderId, Integer amount) {
}
