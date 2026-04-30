package com.jipi.ticket_ledger.reservation.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipi.ticket_ledger.global.exception.GlobalExceptionHandler;
import com.jipi.ticket_ledger.reservation.application.ReservationService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReservationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    @DisplayName("예약 생성 성공 시 200과 reservationId를 반환한다")
    void createReservationSuccess() throws Exception {
        when(reservationService.createReservation(any())).thenReturn(1L);

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequestFixture(1L, 10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(1L));
    }

    @Test
    @DisplayName("요청값 validation 실패 시 400을 반환한다")
    void createReservationValidationFail() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatId\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("사용자/좌석이 없으면 404를 반환한다")
    void createReservationNotFound() throws Exception {
        when(reservationService.createReservation(any()))
                .thenThrow(new EntityNotFoundException("좌석을 찾을 수 없습니다."));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequestFixture(1L, 999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private record CreateReservationRequestFixture(Long userId, Long seatId) {
    }
}
