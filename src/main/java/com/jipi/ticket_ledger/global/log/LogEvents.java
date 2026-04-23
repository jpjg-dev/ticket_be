package com.jipi.ticket_ledger.global.log;

public final class LogEvents {
    private LogEvents() {
    }

    public static final String PAYMENT_CONFIRM_START = "PAYMENT_CONFIRM_START";
    public static final String PAYMENT_CONFIRM_REJECT = "PAYMENT_CONFIRM_REJECT";
    public static final String PAYMENT_CONFIRM_SUCCESS = "PAYMENT_CONFIRM_SUCCESS";

    public static final String PAYMENT_FAIL_START = "PAYMENT_FAIL_START";
    public static final String PAYMENT_FAIL_SUCCESS = "PAYMENT_FAIL_SUCCESS";

    public static final String PAYMENT_CANCEL_START = "PAYMENT_CANCEL_START";
    public static final String PAYMENT_CANCEL_REJECT = "PAYMENT_CANCEL_REJECT";
    public static final String PAYMENT_CANCEL_SUCCESS = "PAYMENT_CANCEL_SUCCESS";

    public static final String RESERVATION_EXPIRE_START = "RESERVATION_EXPIRE_START";
    public static final String RESERVATION_EXPIRE_SUCCESS = "RESERVATION_EXPIRE_SUCCESS";
    public static final String PAYMENT_EXPIRE_SUCCESS = "PAYMENT_EXPIRE_SUCCESS";
}
