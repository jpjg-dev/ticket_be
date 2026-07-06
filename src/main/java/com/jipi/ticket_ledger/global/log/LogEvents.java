package com.jipi.ticket_ledger.global.log;

public final class LogEvents {
    private LogEvents() {
    }

    public static final String HTTP_REQUEST = "HTTP_REQUEST";
    public static final String API_ERROR = "API_ERROR";

    // Toss 외부호출 실패(승인/취소/조회)를 호출 단위에서 같은 형식으로 남기는 이벤트
    public static final String TOSS_CALL_FAIL = "TOSS_CALL_FAIL";

    public static final String PAYMENT_CONFIRM_START = "PAYMENT_CONFIRM_START";
    public static final String PAYMENT_CONFIRM_REJECT = "PAYMENT_CONFIRM_REJECT";
    public static final String PAYMENT_CONFIRM_SUCCESS = "PAYMENT_CONFIRM_SUCCESS";

    public static final String PAYMENT_FAIL_START = "PAYMENT_FAIL_START";
    public static final String PAYMENT_FAIL_SUCCESS = "PAYMENT_FAIL_SUCCESS";
    public static final String PAYMENT_FAIL_REDIRECT_RECEIVED = "PAYMENT_FAIL_REDIRECT_RECEIVED";

    public static final String PAYMENT_CANCEL_START = "PAYMENT_CANCEL_START";
    public static final String PAYMENT_CANCEL_REJECT = "PAYMENT_CANCEL_REJECT";
    public static final String PAYMENT_CANCEL_SUCCESS = "PAYMENT_CANCEL_SUCCESS";

    public static final String RESERVATION_CREATE_START = "RESERVATION_CREATE_START";
    public static final String RESERVATION_CREATE_REJECT = "RESERVATION_CREATE_REJECT";
    public static final String RESERVATION_CREATE_SUCCESS = "RESERVATION_CREATE_SUCCESS";
    public static final String RESERVATION_EXPIRE_START = "RESERVATION_EXPIRE_START";
    public static final String RESERVATION_EXPIRE_SUCCESS = "RESERVATION_EXPIRE_SUCCESS";
    public static final String PAYMENT_EXPIRE_SUCCESS = "PAYMENT_EXPIRE_SUCCESS";

    public static final String AUTH_LOGIN_SUCCESS = "AUTH_LOGIN_SUCCESS";
    public static final String AUTH_LOGIN_REJECT = "AUTH_LOGIN_REJECT";
    public static final String AUTH_REISSUE_SUCCESS = "AUTH_REISSUE_SUCCESS";
    public static final String AUTH_REISSUE_REJECT = "AUTH_REISSUE_REJECT";
    public static final String AUTH_LOGOUT_SUCCESS = "AUTH_LOGOUT_SUCCESS";

    public static final String USER_SIGNUP_SUCCESS = "USER_SIGNUP_SUCCESS";
    public static final String USER_SIGNUP_REJECT = "USER_SIGNUP_REJECT";
    public static final String USER_MYPAGE_REJECT = "USER_MYPAGE_REJECT";
}
