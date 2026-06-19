package com.jipi.ticket_ledger.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jipi.ticket_ledger.global.log.TraceIdFilter;
import org.slf4j.MDC;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code,
                            String message,
                            String traceId) {

    /**
     * 기존 호출부 호환 + 추적용 traceId 자동 주입 생성자.
     * 현재 요청의 MDC traceId 를 응답에 실어, 사용자가 본 에러 ID로 로그를 역추적할 수 있게 한다.
     * (traceId 가 없으면 NON_NULL 직렬화로 응답에서 생략된다.)
     */
    public ErrorResponse(String code, String message) {
        this(code, message, MDC.get(TraceIdFilter.TRACE_ID));
    }
}
