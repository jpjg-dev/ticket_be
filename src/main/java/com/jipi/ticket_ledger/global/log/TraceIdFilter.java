package com.jipi.ticket_ledger.global.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청마다 추적용 traceId를 발급해 MDC에 넣고, 모든 로그 줄에 함께 찍히도록 한다.
 * 운영에서 한 요청의 처리 흐름(컨트롤러→서비스→외부호출)을 traceId로 묶어 장애를 추적하기 위함이다.
 *
 * <p>시큐리티 필터체인보다 먼저 실행되어야 JWT 인증 로그까지 traceId가 포함되므로
 * {@link Ordered#HIGHEST_PRECEDENCE} 로 등록한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String CLIENT_IP = "clientIp";
    // nginx $request_id 등 엣지 식별자와 연결할 수 있도록 표준 헤더명을 사용한다.
    public static final String TRACE_ID_HEADER = "X-Request-Id";

    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final int GENERATED_TRACE_ID_LENGTH = 8;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID, traceId);
        // 최우선 필터라 여기서 clientIp를 MDC에 넣으면 인증 실패 로그까지 모든 줄에 IP가 붙는다.
        MDC.put(CLIENT_IP, ClientIpResolver.resolve(request));
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 스레드 풀 재사용으로 인한 값 누수를 막기 위해 요청 종료 시 MDC를 비운다.
            // (하위 필터가 추가한 userId 등 다른 키까지 일괄 정리)
            MDC.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String headerValue = request.getHeader(TRACE_ID_HEADER);
        if (headerValue == null || headerValue.isBlank()) {
            return generateTraceId();
        }
        // 외부 입력을 그대로 신뢰하면 로그 위조(개행 주입 등) 위험이 있어 허용 문자/길이만 통과시킨다.
        String sanitized = headerValue.trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (sanitized.isEmpty()) {
            return generateTraceId();
        }
        return sanitized.length() > MAX_TRACE_ID_LENGTH
                ? sanitized.substring(0, MAX_TRACE_ID_LENGTH)
                : sanitized;
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, GENERATED_TRACE_ID_LENGTH);
    }
}
