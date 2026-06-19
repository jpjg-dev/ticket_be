package com.jipi.ticket_ledger.user.presentation;

import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.global.exception.GlobalExceptionHandler;
import com.jipi.ticket_ledger.global.security.CsrfOriginFilter;
import com.jipi.ticket_ledger.user.application.UserService;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import com.jipi.ticket_ledger.user.presentation.dto.ResponseMeDTO;
import com.jipi.ticket_ledger.user.presentation.dto.ResponseMyPageDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CsrfOriginFilter csrfOriginFilter;

    @MockitoBean
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/v1/users/me: 로그인 사용자 기본 정보를 반환한다")
    void getMyInfoSuccess() throws Exception {
        when(userService.getMyInfo(1L))
                .thenReturn(new ResponseMeDTO(1L, "user@test.com", "테스터", "ROLE_USER", "ACTIVE"));
        SecurityContextHolder.getContext().setAuthentication(authenticationPrincipal(1L));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.email").value("user@test.com"))
                .andExpect(jsonPath("$.name").value("테스터"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(userService).getMyInfo(1L);
    }

    @Test
    @DisplayName("GET /api/v1/users/{userId}: 본인 마이페이지 정보를 반환한다")
    void getUserInfoSuccess() throws Exception {
        LocalDateTime startAt = LocalDateTime.of(2026, 5, 13, 19, 30);
        Instant requestedAt = Instant.parse("2026-05-12T01:00:00Z");
        ResponseMyPageDTO response = new ResponseMyPageDTO(
                List.of(new ResponseMyPageDTO.ReservationGroupItem(
                        10L,
                        "CONFIRMED",
                        "테스트 공연",
                        "테스트홀",
                        startAt,
                        List.of(
                                new ResponseMyPageDTO.SeatItem("A-1", "VIP"),
                                new ResponseMyPageDTO.SeatItem("A-2", "VIP")
                        )
                )),
                List.of(new ResponseMyPageDTO.PaymentItem(
                        10L,
                        100L,
                        "APPROVED",
                        200000,
                        "CARD",
                        requestedAt,
                        List.of(
                                new ResponseMyPageDTO.SeatItem("A-1", "VIP"),
                                new ResponseMyPageDTO.SeatItem("A-2", "VIP")
                        )
                ))
        );
        when(userService.getUserInfo(1L, 1L)).thenReturn(response);
        SecurityContextHolder.getContext().setAuthentication(authenticationPrincipal(1L));

        mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations[0].reservationGroupId").value(10L))
                .andExpect(jsonPath("$.reservations[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reservations[0].seats[0].seatNumber").value("A-1"))
                .andExpect(jsonPath("$.payments[0].reservationGroupId").value(10L))
                .andExpect(jsonPath("$.payments[0].paymentId").value(100L))
                .andExpect(jsonPath("$.payments[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.payments[0].seats[1].seatNumber").value("A-2"));

        verify(userService).getUserInfo(1L, 1L);
    }

    @Test
    @DisplayName("GET /api/v1/users/{userId}: 다른 사용자 조회 예외는 409로 반환한다")
    void getUserInfoForbiddenOtherUser() throws Exception {
        when(userService.getUserInfo(2L, 1L))
                .thenThrow(new IllegalStateException("잘못된 접근 입니다."));
        SecurityContextHolder.getContext().setAuthentication(authenticationPrincipal(1L));

        mockMvc.perform(get("/api/v1/users/2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"))
                .andExpect(jsonPath("$.message").value("잘못된 접근 입니다."));

        verify(userService).getUserInfo(2L, 1L);
    }

    private UsernamePasswordAuthenticationToken authenticationPrincipal(Long userId) {
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER")
        );
        return new UsernamePasswordAuthenticationToken(
                userId,
                null,
                authorities
        );
    }
}
