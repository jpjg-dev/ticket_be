package com.jipi.ticket_ledger.global.exception;

/**
 * 인가(BOLA) 실패 — 로그인은 됐으나(인증 O) 본인 소유가 아닌 리소스에 접근한 경우.
 * 401(AuthUnauthorizedException, 인증 자체 실패)과 구분되는 403 응답이다.
 */
public class ForbiddenAccessException extends RuntimeException {

    public ForbiddenAccessException(String message) {
        super(message);
    }
}
