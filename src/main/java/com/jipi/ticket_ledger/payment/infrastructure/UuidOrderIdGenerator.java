package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.OrderIdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidOrderIdGenerator implements OrderIdGenerator {

    @Override
    public String generate(Long reservationGroupId) {
        return "reservation-group-" + reservationGroupId + "-" + UUID.randomUUID();
    }
}
