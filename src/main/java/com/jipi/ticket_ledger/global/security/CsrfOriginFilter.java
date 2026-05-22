package com.jipi.ticket_ledger.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipi.ticket_ledger.global.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class CsrfOriginFilter extends OncePerRequestFilter {

    private final RequestMatcher requireCsrfProtectionMatcher = CsrfFilter.DEFAULT_CSRF_MATCHER;
    private final CsrfOriginProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (!requireCsrfProtectionMatcher.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isAllowedRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ErrorResponse("CSRF_ORIGIN_DENIED", "허용되지 않은 요청 출처입니다."));
    }

    private boolean isAllowedRequest(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            boolean allowed = isAllowedOrigin(origin);
            log.debug("csrf origin check method={} uri={} origin={} allowed={}", request.getMethod(), request.getRequestURI(), origin, allowed);
            return allowed;
        }

        String referer = request.getHeader("Referer");
        boolean allowed = referer != null && isAllowedOrigin(extractOrigin(referer));
        log.debug("csrf referer check method={} uri={} referer={} allowed={}", request.getMethod(), request.getRequestURI(), referer, allowed);
        return allowed;
    }

    private boolean isAllowedOrigin(String value) {
        String requestOrigin = normalizeOrigin(value);
        if (requestOrigin == null) {
            return false;
        }

        return allowedOrigins().stream().map(this::normalizeOrigin).anyMatch(requestOrigin::equals);
    }

    private List<String> allowedOrigins() {
        return properties.getAllowedOrigins();
    }

    private String extractOrigin(String referer) {
        return normalizeOrigin(referer);
    }

    private String normalizeOrigin(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();

            if (scheme == null || host == null) {
                return null;
            }

            String normalized = scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT);
            if (port != -1) {
                normalized += ":" + port;
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
