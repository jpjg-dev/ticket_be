package com.jipi.ticket_ledger.paymentaudit.application.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PaymentAuditMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> consumeCounters;
    private final Map<String, Counter> auditCounters;

    public PaymentAuditMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.consumeCounters = Map.of(
                "processed", consumeCounter("processed"),
                "duplicate", consumeCounter("duplicate"),
                "dlt", consumeCounter("dlt")
        );
        this.auditCounters = Map.of(
                "PaymentApproved", auditCounter("PaymentApproved"),
                "PaymentCanceled", auditCounter("PaymentCanceled")
        );
    }

    public void recordConsume(String outcome) {
        consumeCounters.get(outcome).increment();
    }

    public void recordAudit(String eventType) {
        auditCounters.get(eventType).increment();
    }

    private Counter consumeCounter(String outcome) {
        return Counter.builder("payment_audit_consume_total")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private Counter auditCounter(String eventType) {
        return Counter.builder("payment_audit_record_total")
                .tag("event_type", eventType)
                .register(meterRegistry);
    }
}
