package com.jipi.ticket_ledger.reservation.application.lock;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("seat-lock")
public record SeatLockPolicyProperties(
        Duration waitTime,
        Duration watchdogTimeout,
        int fallbackMaxConcurrent,
        Duration fallbackWaitTime,
        long retryAfterSeconds
) {
}
