package com.jipi.ticket_ledger.payment.application.recovery;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
public class PaymentRecoverySchedulerProperties {

    @NotNull
    @PositiveOrZero
    private Long graceMs;

    @NotNull
    @Positive
    private Integer batchSize;

    @NotNull
    @Positive
    private Long fixedDelayMs;

    public Long getGraceMs() {
        return graceMs;
    }

    public void setGraceMs(Long graceMs) {
        this.graceMs = graceMs;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(Long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }
}
