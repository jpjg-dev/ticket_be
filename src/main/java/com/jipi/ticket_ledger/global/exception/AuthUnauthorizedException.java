package com.jipi.ticket_ledger.global.exception;

public class AuthUnauthorizedException extends RuntimeException {

    public AuthUnauthorizedException() {
        super("인증이 필요합니다.");
    }

    public AuthUnauthorizedException(String message) {
        super(message);
    }
}
