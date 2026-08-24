package com.jipi.ticket_ledger.payment.application.port.out;

import com.jipi.ticket_ledger.payment.application.outbox.ClaimedPaymentOutboxEvent;

public interface PaymentEventPublisher {

    void publish(ClaimedPaymentOutboxEvent event);
}
