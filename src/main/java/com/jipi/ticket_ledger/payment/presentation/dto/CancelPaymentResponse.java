package com.jipi.ticket_ledger.payment.presentation.dto;

import com.jipi.ticket_ledger.payment.domain.PaymentStatus;

/**
 * 결제 취소 응답. paymentStatus 로 확정(CANCELED)과 처리중(CANCELING)을 구분해
 * 클라이언트가 "취소 처리 중"을 표시할 수 있게 한다.
 */
public record CancelPaymentResponse(
        Long paymentId,
        PaymentStatus paymentStatus
) {
}
