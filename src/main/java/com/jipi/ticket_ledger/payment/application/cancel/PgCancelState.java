package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayPayment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;

/**
 * PG 취소/조회 응답을 정책 판단에 필요한 최소 필드로 정규화한 값.
 * TossCancelResponse(취소 응답)와 TossPaymentLookupResponse(조회 fallback)를 같은 형태로 다루기 위한 어댑터.
 */
record PgCancelState(String paymentKey, String status, String currency, PaymentGatewayState state) {

    static PgCancelState from(PaymentGatewayPayment response) {
        return new PgCancelState(response.paymentKey(), response.status(), response.currency(), response.state());
    }
}
