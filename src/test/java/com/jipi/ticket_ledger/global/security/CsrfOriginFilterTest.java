package com.jipi.ticket_ledger.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsrfOriginFilterTest {

    private final CsrfOriginFilter filter = new CsrfOriginFilter(
            new CsrfOriginProperties(List.of(
                    "https://ticketledger.dev",
                    "https://www.ticketledger.dev",
                    "http://localhost:3000"
            )),
            new ObjectMapper()
    );

    @Test
    @DisplayName("GET 요청은 Origin 없이도 통과한다")
    void doFilter_safeMethod_skipsOriginCheck() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("상태 변경 요청은 허용된 Origin이면 통과한다")
    void doFilter_mutationWithAllowedOrigin_passes() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservations");
        request.addHeader("Origin", "https://ticketledger.dev");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Origin이 없으면 허용된 Referer 기준으로 통과한다")
    void doFilter_mutationWithAllowedReferer_passes() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservations");
        request.addHeader("Referer", "https://ticketledger.dev/booking");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("상태 변경 요청은 허용되지 않은 Origin이면 거부한다")
    void doFilter_mutationWithDeniedOrigin_rejects() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservations");
        request.addHeader("Origin", "https://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertEquals(403, response.getStatus());
        assertEquals("{\"code\":\"CSRF_ORIGIN_DENIED\",\"message\":\"허용되지 않은 요청 출처입니다.\"}", response.getContentAsString());
    }

    @Test
    @DisplayName("상태 변경 요청은 Origin과 Referer가 모두 없으면 거부한다")
    void doFilter_mutationWithoutOriginAndReferer_rejects() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertEquals(403, response.getStatus());
    }
}
