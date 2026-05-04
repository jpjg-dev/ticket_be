package com.jipi.ticket_ledger.auth.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
            // JWT 토큰 검증 로직을 여기에 구현할 예정입니다.
            // 1. Authorization 헤더에서 토큰 추출
            // 2. 토큰 유효성 검사 (예: 서명 검증, 만료 시간 확인)
            // 3. 유효한 토큰이면 SecurityContext에 인증 정보 설정
            // 4. 다음 필터로 요청 전달

            filterChain.doFilter(request, response); // 다음 필터로 요청 전달
    }
}
