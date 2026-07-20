package com.jipi.ticket_ledger.queue.application;

public class QueueAdmissionRequiredException extends RuntimeException {
    public QueueAdmissionRequiredException() {
        super("대기열 입장 확인이 필요합니다.");
    }
}
