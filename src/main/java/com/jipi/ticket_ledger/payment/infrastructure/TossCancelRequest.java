package com.jipi.ticket_ledger.payment.infrastructure;

public record TossCancelRequest(String cancelReason,
                                String currency) {
}
