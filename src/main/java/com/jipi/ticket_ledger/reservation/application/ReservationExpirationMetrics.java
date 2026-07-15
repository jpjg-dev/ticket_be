package com.jipi.ticket_ledger.reservation.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class ReservationExpirationMetrics {

    private static final String EXPIRATION_TOTAL = "reservation_expiration_group_total";

    private final MeterRegistry meterRegistry;

    public ReservationExpirationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String trigger, String outcome) {
        meterRegistry.counter(
                EXPIRATION_TOTAL,
                "trigger", trigger.toLowerCase(Locale.ROOT),
                "outcome", outcome
        ).increment();
    }
}
