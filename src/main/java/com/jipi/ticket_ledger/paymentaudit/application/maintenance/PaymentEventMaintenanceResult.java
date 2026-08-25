package com.jipi.ticket_ledger.paymentaudit.application.maintenance;

public record PaymentEventMaintenanceResult(
        int deletedOutboxCount,
        int deletedInboxCount
) {
}
