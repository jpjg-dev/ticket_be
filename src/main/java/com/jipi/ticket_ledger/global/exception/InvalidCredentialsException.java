package com.jipi.ticket_ledger.global.exception;

/**
 * 로그인 인증 실패(이메일 없음/비활성/비밀번호 불일치)에 대한 예외.
 * 응답은 401 + 제네릭 메시지로 통일해 어느 필드가 틀렸는지(=계정 존재 여부) 노출하지 않는다.
 * 어떤 사유로 거부됐는지는 서버 로그(AUTH_LOGIN_REJECT reason=...)에만 상세히 남긴다.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
