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
    // Access Token 쿠키 이름을 상수로 관리한다.
    private static final String ACCESS_TOKEN_COOKIE_NAME = AuthCookieNames.ACCESS_TOKEN;

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    // 매 요청마다 Access Token 쿠키를 확인하고, 유효한 사용자라면 SecurityContext에 인증 객체를 등록한다.
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // 요청 쿠키에서 Access Token 값을 추출한다.
        String accessToken = extractCookieValue(request, ACCESS_TOKEN_COOKIE_NAME);

        // 토큰이 없거나 공백이면 인증 없이 다음 필터로 진행한다.
        if (accessToken == null || accessToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 토큰 유효성 또는 Access Token 여부가 아니면 인증 정보를 제거하고 다음으로 진행한다.
        if (!jwtTokenProvider.isValidToken(accessToken) || !jwtTokenProvider.isAccessToken(accessToken)) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // 토큰에서 사용자 식별자를 추출한다.
        Long userId = jwtTokenProvider.getUserId(accessToken);
        // 식별자로 사용자 정보를 조회한다.
        User user = userRepository.findById(userId).orElse(null);

        // 사용자가 없거나 비활성 상태면 인증 정보를 제거하고 다음으로 진행한다.
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // 사용자 역할을 권한으로 매핑한다.
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(user.getRole().name())
        );
        // 인증 객체를 생성해 SecurityContext에 저장한다.
        Authentication authentication = new UsernamePasswordAuthenticationToken(user.getId(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 다음 필터로 요청을 넘긴다.
        filterChain.doFilter(request, response);
    }

    // 요청 쿠키 목록에서 지정한 이름의 쿠키 값을 찾아 반환한다.
    private String extractCookieValue(HttpServletRequest request, String cookieName) {
        // 쿠키가 없으면 null을 반환한다.
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        // 쿠키 목록에서 이름이 일치하는 값을 찾아 반환한다.
        return Arrays.stream(cookies)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
