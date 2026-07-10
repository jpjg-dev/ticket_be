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
 * 누가(userId)·어느 요청(traceId)·어디서(clientIp)인지는 MDC 꼬리표로 모든 줄에 자동 부착되므로
 * 여기서는 요청 메타만 기록한다.
 *
 * <p>{@link TraceIdFilter} 직후(= MDC 설정 이후)에 실행되도록 우선순위를 한 칸 뒤로 둔다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class AccessLogFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 주기적으로 호출되는 헬스체크와 Prometheus scrape는 접근 로그를 남기지 않는다.
        String requestUri = request.getRequestURI();
        return requestUri.startsWith("/actuator/health")
                || requestUri.equals("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();
            // 요청 본문/쿼리스트링에는 민감정보가 섞일 수 있어 남기지 않는다(method/path/status/소요시간만).
            // IP는 MDC clientIp 로 모든 로그 줄 접두에 이미 붙는다.
            if (status >= 500) {
                log.warn("event={} method={} path={} status={} durationMs={}",
                        LogEvents.HTTP_REQUEST, request.getMethod(), request.getRequestURI(), status, durationMs);
            } else {
                log.info("event={} method={} path={} status={} durationMs={}",
                        LogEvents.HTTP_REQUEST, request.getMethod(), request.getRequestURI(), status, durationMs);
            }
        }
    }
}
