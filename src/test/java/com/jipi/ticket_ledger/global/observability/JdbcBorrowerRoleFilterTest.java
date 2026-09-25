package com.jipi.ticket_ledger.global.observability;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcBorrowerRoleFilterTest {

    private final JdbcBorrowerRoleContext context = new JdbcBorrowerRoleContext(true);
    private final JdbcBorrowerRoleFilter filter = new JdbcBorrowerRoleFilter(context);

    @Test
    void classifiesFixedRoutesWithoutIncludingPathIdentifiers() {
        assertEquals(JdbcBorrowerRoleContext.HTTP_SEATS, classify("GET", "/api/v1/event/schedules/91/seats"));
        assertEquals(JdbcBorrowerRoleContext.HTTP_SEATS, classify("GET", "/api/v1/event/schedules/availability"));
        assertEquals(JdbcBorrowerRoleContext.HTTP_RESERVATION, classify("POST", "/api/v1/reservations"));
        assertEquals(JdbcBorrowerRoleContext.HTTP_PAYMENT, classify("POST", "/api/v1/payments/ready"));
        assertEquals(JdbcBorrowerRoleContext.HTTP_EVENTS, classify("GET", "/api/v1/event/91"));
        assertEquals(JdbcBorrowerRoleContext.HTTP_USER, classify("GET", "/api/v1/users/91"));
        assertEquals(JdbcBorrowerRoleContext.HTTP_OTHER, classify("GET", "/api/v1/queue/admissions"));
    }

    @Test
    void keepsRootRoleForWholeServletChainAndClearsItAfterward() throws ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/api/v1/event/schedules/91/seats");

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            assertEquals(JdbcBorrowerRoleContext.HTTP_SEATS, context.acquisitionRole());
            assertEquals(JdbcBorrowerRoleContext.HTTP_SEATS, context.holdRole());
            try (JdbcBorrowerRoleContext.Scope ignored = context.openPhase(JdbcBorrowerRoleContext.JWT_USER_LOOKUP)) {
                assertEquals(JdbcBorrowerRoleContext.JWT_USER_LOOKUP, context.acquisitionRole());
                assertEquals(JdbcBorrowerRoleContext.HTTP_SEATS, context.holdRole());
            }
        });

        assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.acquisitionRole());
        assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.holdRole());
    }

    @Test
    void restoresRoleWhenFilterChainThrows() {
        MockHttpServletRequest request = request("POST", "/api/v1/reservations");
        IOException expected = new IOException("test failure");

        IOException thrown = assertThrows(IOException.class, () -> filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                    assertEquals(JdbcBorrowerRoleContext.HTTP_RESERVATION, context.holdRole());
                    throw expected;
                }
        ));

        assertEquals(expected, thrown);
        assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.holdRole());
    }

    private static String classify(String method, String path) {
        return JdbcBorrowerRoleFilter.classify(request(method, path));
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
