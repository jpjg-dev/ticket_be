package com.jipi.ticket_ledger.payment.domain;

public enum PaymentStatus {
    READY, // 준비
    CONFIRMING, // 승인 진행 중
    APPROVED, // 승인
    FAILED, // 실패
    CANCELING, // 취소 진행 중
    CANCELED // 취소
}
