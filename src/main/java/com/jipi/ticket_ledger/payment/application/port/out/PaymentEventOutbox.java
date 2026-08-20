package com.jipi.ticket_ledger.payment.application.port.out;

import com.jipi.ticket_ledger.payment.application.event.PaymentEvent;

public interface PaymentEventOutbox {

    void append(PaymentEvent event);
}
