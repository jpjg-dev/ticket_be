package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TossPaymentStatusTest {

    @ParameterizedTest
    @CsvSource({
            "DONE, APPROVED",
            "CANCELED, CANCELED",
            "PARTIAL_CANCELED, CANCELED",
            "READY, PENDING",
            "IN_PROGRESS, PENDING",
            "WAITING_FOR_DEPOSIT, PENDING",
            "ABORTED, FAILED",
            "EXPIRED, FAILED",
            "NEW_UNKNOWN_STATUS, UNKNOWN"
    })
    void mapsPgStatusToGatewayState(String status, PaymentGatewayState expected) {
        assertEquals(expected, TossPaymentStatus.toGatewayState(status));
    }
}
