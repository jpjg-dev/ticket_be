package com.jipi.ticket_ledger.global.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청 1건당 접근 로그 요약 한 줄을 남긴다(method/path/status/소요시간).
 * 누가(userId)·어느 요청(traceId)인지는 MDC 꼬리표로 자동 부착되므로 여기서는 요청 메타만 기록한다.
 *
 * <p>{@link TraceIdFilter} 직후(= MDC 설정 이후)에 실행되도록 우선순위를 한 칸 뒤로 둔다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class AccessLogFilter extends OncePerRequestFilter {

    // nginx 가 proxy_set_header 로 넘겨주는 실제 클라이언트 IP 헤더.
    // X-Real-IP 는 nginx 가 $remote_addr 로 "덮어써서" 보내므로(append 아님) 신뢰 가능하고,
    // nginx access log 의 $remote_addr 와 같은 값이라 로그 대조가 쉽다.
    private static final String HEADER_X_REAL_IP = "X-Real-IP";
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();
            String clientIp = resolveClientIp(request);
            // 요청 본문/쿼리스트링에는 민감정보가 섞일 수 있어 남기지 않는다(method/path/status/소요시간/IP만).
            if (status >= 500) {
                log.warn("event={} method={} path={} status={} durationMs={} ip={}",
                        LogEvents.HTTP_REQUEST, request.getMethod(), request.getRequestURI(), status, durationMs, clientIp);
            } else {
                log.info("event={} method={} path={} status={} durationMs={} ip={}",
                        LogEvents.HTTP_REQUEST, request.getMethod(), request.getRequestURI(), status, durationMs, clientIp);
            }
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
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
    private String sanitize(String ip) {
        return ip.trim().replaceAll("[^0-9A-Fa-f:.]", "");
    }
}
