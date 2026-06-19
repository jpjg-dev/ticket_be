package com.jipi.ticket_ledger.global.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("헤더가 없으면 traceId를 생성해 MDC와 응답 헤더에 동일하게 넣는다")
    void generatesTraceIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) ->
                mdcDuringChain[0] = MDC.get(TraceIdFilter.TRACE_ID));

        assertThat(mdcDuringChain[0]).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(mdcDuringChain[0]);
    }

    @Test
    @DisplayName("들어온 X-Request-Id 헤더는 그대로 전파한다")
    void propagatesInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "edge-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) ->
                mdcDuringChain[0] = MDC.get(TraceIdFilter.TRACE_ID));

        assertThat(mdcDuringChain[0]).isEqualTo("edge-trace-123");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("edge-trace-123");
    }

    @Test
    @DisplayName("위험 문자가 섞인 헤더는 허용 문자만 남긴다")
    void sanitizesInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "abc\n123 INJECT");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) ->
                mdcDuringChain[0] = MDC.get(TraceIdFilter.TRACE_ID));

        assertThat(mdcDuringChain[0]).isEqualTo("abc123INJECT");
    }

    @Test
    @DisplayName("요청 처리가 끝나면 MDC를 비운다")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                MDC.put(TraceIdFilter.USER_ID, "42"));

        assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNull();
        assertThat(MDC.get(TraceIdFilter.USER_ID)).isNull();
    }
}
