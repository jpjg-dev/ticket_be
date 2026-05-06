package com.jipi.ticket_ledger.auth.infrastructure;

import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import com.jipi.ticket_ledger.user.domain.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String ACCESS_TOKEN_COOKIE_NAME = AuthCookieNames.ACCESS_TOKEN;

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    // 매 요청마다 Access Token 쿠키를 확인하고, 유효한 사용자라면 SecurityContext에 인증 객체를 등록한다.
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String accessToken = extractCookieValue(request, ACCESS_TOKEN_COOKIE_NAME);

        if (accessToken == null || accessToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!jwtTokenProvider.isValidToken(accessToken) || !jwtTokenProvider.isAccessToken(accessToken)) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        Long userId = jwtTokenProvider.getUserId(accessToken);
        User user = userRepository.findById(userId).orElse(null);

        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(user.getRole().name())
        );
        Authentication authentication = new UsernamePasswordAuthenticationToken(user.getId(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    // 요청 쿠키 목록에서 지정한 이름의 쿠키 값을 찾아 반환한다.
    private String extractCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
