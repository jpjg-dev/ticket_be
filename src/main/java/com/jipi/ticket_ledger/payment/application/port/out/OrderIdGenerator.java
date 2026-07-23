package com.jipi.ticket_ledger.payment.application.port.out;

public interface OrderIdGenerator {

    String generate(Long reservationGroupId);
}
