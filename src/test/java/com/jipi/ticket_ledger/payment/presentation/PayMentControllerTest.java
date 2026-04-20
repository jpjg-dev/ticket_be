package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.common.dto.GlobalExceptionHandler;
import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.application.PaymentService;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PayMentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PayMentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("결제 준비 성공 시 200과 결제 응답을 반환한다")
    void readyPaymentSuccess() throws Exception {
        Payment payment = createReadyPayment();
        when(paymentService.readyPayment(1L)).thenReturn(payment);

        mockMvc.perform(post("/payments/ready")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(1L))
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.amount").value(110000))
                .andExpect(jsonPath("$.currency").value("KRW"));
    }

    @Test
    @DisplayName("결제 승인 확인 성공 시 200을 반환한다")
    void confirmPaymentSuccess() throws Exception {
        Payment approvedPayment = createApprovedPayment();
        when(paymentService.confirmPayment(anyString(), anyString(), anyInt())).thenReturn(approvedPayment);

        mockMvc.perform(post("/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentKey": "pay-key",
                                  "orderId": "order-1",
                                  "amount": 110000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reservationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatStatus").value("BOOKED"));
    }

    @Test
    @DisplayName("결제 실패 처리 성공 시 200을 반환한다")
    void failPaymentSuccess() throws Exception {
        mockMvc.perform(post("/payments/1/fail"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("결제 취소 성공 시 200을 반환한다")
    void cancelPaymentSuccess() throws Exception {
        mockMvc.perform(post("/payments/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"사용자 요청 취소\""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("잘못된 상태 전이는 409를 반환한다")
    void cancelPaymentConflict() throws Exception {
        doThrow(new IllegalStateException("승인된 결제만 취소할 수 있습니다."))
                .when(paymentService).cancelPayment(anyLong(), anyString());

        mockMvc.perform(post("/payments/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"사용자 요청 취소\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
    }

    private Payment createReadyPayment() {
        LocalDateTime now = LocalDateTime.now();
        Event event = new Event("테스트 공연", "설명", "테스트홀", now, now);
        Schedule schedule = new Schedule(event, now.plusDays(1), now.plusDays(1).plusHours(2), now);
        Seat seat = new Seat(schedule, "A-1", "VIP", 100000, now);
        seat.hold();
        User user = new User("ready@test.com", "pw", "유저", now);
        Reservation reservation = new Reservation(user, seat, now);
        Payment payment = new Payment(reservation, 100000, now, "order-1", "KRW");

        org.springframework.test.util.ReflectionTestUtils.setField(payment, "id", 1L);
        return payment;
    }

    private Payment createApprovedPayment() {
        Payment payment = createReadyPayment();
        payment.approve("pay-key", "CARD", "DONE");
        payment.getReservation().confirm();
        payment.getReservation().getSeat().book();
        return payment;
    }
}
