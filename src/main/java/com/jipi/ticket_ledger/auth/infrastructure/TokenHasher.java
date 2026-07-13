package com.jipi.ticket_ledger.auth.infrastructure;

import com.jipi.ticket_ledger.auth.application.port.out.TokenHashEncoder;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component // 스프링이 이 클래스를 빈으로 관리하도록 지정
public class TokenHasher implements TokenHashEncoder {
    /**
     * Refresh Token 원문은 DB에 그대로 저장하지 않는다.
     * 토큰이 탈취되면 그대로 인증에 사용할 수 있기 때문에, DB에는 SHA-256 해시값만 저장한다.
     */
    public String hash(String token) { // 해시할 대상(토큰 문자열)을 받아서 반환
        try { // 해시 과정 중 예외가 발생할 수 있으므로 try-catch로 감싼다
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); // SHA-256 알고리즘 인스턴스를 가져온다
            byte[] hashedBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8)); // 토큰을 UTF-8 바이트로 변환 후 해시 계산
            return HexFormat.of().formatHex(hashedBytes); // 해시 바이트를 16진수 문자열로 변환해 반환
        } catch (NoSuchAlgorithmException e) { // SHA-256 알고리즘을 지원하지 않는 경우
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", e); // 런타임 예외로 감싸 호출부에 알림
        }
    }
}
