package com.jipi.ticket_ledger.global.log;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 프록시(nginx→FE→BE) 뒤에서 실제 클라이언트 IP를 해석한다.
 * MDC 꼬리표({@link TraceIdFilter})와 접근 로그({@link AccessLogFilter})가 같은 값을 쓰도록 한 곳에 모은다.
 */
public final class ClientIpResolver {

    // nginx 가 proxy_set_header 로 넘겨주는 실제 클라이언트 IP 헤더.
    // X-Real-IP 는 nginx 가 $remote_addr 로 "덮어써서" 보내므로(append 아님) 신뢰 가능하고,
    // nginx access log 의 $remote_addr 와 같은 값이라 로그 대조가 쉽다.
    private static final String HEADER_X_REAL_IP = "X-Real-IP";
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String realIp = request.getHeader(HEADER_X_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return sanitize(realIp);
        }
        // 프록시가 여러 단일 때 "client, proxy1, proxy2" 형태이며 맨 앞이 최초 클라이언트다.
        String forwardedFor = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return sanitize(forwardedFor.split(",")[0]);
        }
        // nginx 를 거치지 않은 직접 호출(내부망/로컬) 대비 폴백.
        return sanitize(request.getRemoteAddr());
    }

    // 헤더 기반 값이라 로그 위조(개행 주입)를 막기 위해 IP 구성 문자(IPv4/IPv6)만 통과시킨다.
    private static String sanitize(String ip) {
        return ip.trim().replaceAll("[^0-9A-Fa-f:.]", "");
    }
}
