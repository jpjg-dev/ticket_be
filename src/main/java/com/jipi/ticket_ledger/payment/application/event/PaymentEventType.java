package com.jipi.ticket_ledger.payment.application.event;

public enum PaymentEventType {
    PAYMENT_APPROVED("PaymentApproved", 1),
    PAYMENT_CANCELED("PaymentCanceled", 1);

    private final String eventName;
    private final int version;

    PaymentEventType(String eventName, int version) {
        this.eventName = eventName;
        this.version = version;
    }

    public String eventName() {
        return eventName;
    }

    public int version() {
        return version;
    }
}
