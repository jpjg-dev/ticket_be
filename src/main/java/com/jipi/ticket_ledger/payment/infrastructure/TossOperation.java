package com.jipi.ticket_ledger.payment.infrastructure;

// Toss 외부호출 종류. 실패 로그의 operation 필드로 남겨 승인/취소/조회 실패를 같은 기준으로 비교한다.
public enum TossOperation {
    CONFIRM,
    CANCEL,
    LOOKUP_BY_PAYMENT_KEY,
    LOOKUP_BY_ORDER_ID
}
