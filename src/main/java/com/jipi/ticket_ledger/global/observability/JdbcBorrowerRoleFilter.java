package com.jipi.ticket_ledger.global.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(name = "ticketledger.cache.diagnostic", havingValue = "true")
public class JdbcBorrowerRoleFilter extends OncePerRequestFilter {
    private final JdbcBorrowerRoleContext roleContext;

    public JdbcBorrowerRoleFilter(JdbcBorrowerRoleContext roleContext) {
        this.roleContext = roleContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (JdbcBorrowerRoleContext.Scope ignored = roleContext.openRoot(classify(request))) {
            chain.doFilter(request, response);
        }
    }

    static String classify(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = uri.substring(context.length());
        if (path.startsWith("/api/v1/event/schedules/") && path.endsWith("/seats")
                || path.equals("/api/v1/event/schedules/availability")) {
            return JdbcBorrowerRoleContext.HTTP_SEATS;
        }
        if (matches(path, "/api/v1/reservations")) return JdbcBorrowerRoleContext.HTTP_RESERVATION;
        if (matches(path, "/api/v1/payments")) return JdbcBorrowerRoleContext.HTTP_PAYMENT;
        if (matches(path, "/api/v1/event")) return JdbcBorrowerRoleContext.HTTP_EVENTS;
        if (matches(path, "/api/v1/users") || matches(path, "/api/v1/auth")) {
            return JdbcBorrowerRoleContext.HTTP_USER;
        }
        return JdbcBorrowerRoleContext.HTTP_OTHER;
    }

    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
