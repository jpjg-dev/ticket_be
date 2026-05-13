package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.global.exception.GlobalExceptionHandler;
import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.application.PaymentService;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("결제 준비 성공 시 200과 결제 응답을 반환한다")
    void readyPaymentSuccess() throws Exception {
        PaymentFixture fixture = createReadyPayment();
        Payment payment = fixture.payment();
        when(paymentService.readyPayment(1L)).thenReturn(payment);
        when(paymentService.getReservationsForPayment(payment)).thenReturn(fixture.reservations());

        mockMvc.perform(post("/api/v1/payments/ready")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationGroupId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(1L))
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.amount").value(110000))
                .andExpect(jsonPath("$.currency").value("KRW"));
    }

    @Test
    @DisplayName("결제 승인 확인 성공 시 200을 반환한다")
    void confirmPaymentSuccess() throws Exception {
        PaymentFixture fixture = createApprovedPayment();
        Payment approvedPayment = fixture.payment();
        when(paymentService.confirmPayment(anyString(), anyString(), anyInt())).thenReturn(approvedPayment);
        when(paymentService.getReservationsForPayment(approvedPayment)).thenReturn(fixture.reservations());

        mockMvc.perform(post("/api/v1/payments/confirm")
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
    @DisplayName("결제 상태 조회 성공 시 200과 현재 상태를 반환한다")
    void getPaymentStatusSuccess() throws Exception {
        PaymentFixture fixture = createApprovedPayment();
        Payment approvedPayment = fixture.payment();
        when(paymentService.getPaymentStatus(1L)).thenReturn(approvedPayment);
        when(paymentService.getReservationsForPayment(approvedPayment)).thenReturn(fixture.reservations());

        mockMvc.perform(get("/api/v1/payments/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reservationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatStatus").value("BOOKED"));
    }

    @Test
    @DisplayName("결제 취소 성공 시 200을 반환한다")
    void cancelPaymentSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/payments/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"사용자 요청 취소\""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("잘못된 상태 전이는 409를 반환한다")
    void cancelPaymentConflict() throws Exception {
        doThrow(new IllegalStateException("승인된 결제만 취소할 수 있습니다."))
                .when(paymentService).cancelPayment(anyLong(), anyString());

        mockMvc.perform(post("/api/v1/payments/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"사용자 요청 취소\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
    }

    @Test
    @DisplayName("결제 실패 리다이렉트 기록 성공 시 200을 반환한다")
    void failRedirectRecordSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/payments/fail-redirect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "order-1",
                                  "code": "PAY_PROCESS_ABORTED",
                                  "message": "결제가 실패했습니다."
                                }
                                """))
                .andExpect(status().isOk());
    }


    private PaymentFixture createReadyPayment() {
        LocalDateTime now = LocalDateTime.now();
        Event event = new Event("테스트 공연", "설명", "테스트홀", now, now);
        Schedule schedule = new Schedule(event, now.plusDays(1), now.plusDays(1).plusHours(2), now);
        Seat seat = new Seat(schedule, "A-1", "VIP", 100000, now);
        seat.hold();
        User user = new User("ready@test.com", "pw", "유저", now);
        ReservationGroup reservationGroup = new ReservationGroup(user, now);
        org.springframework.test.util.ReflectionTestUtils.setField(reservationGroup, "id", 1L);
        Reservation reservation = new Reservation(user, seat, reservationGroup, now);
        Payment payment = new Payment(reservationGroup, 100000, now, "order-1", "KRW");

        org.springframework.test.util.ReflectionTestUtils.setField(payment, "id", 1L);
        return new PaymentFixture(payment, List.of(reservation));
    }

    private PaymentFixture createApprovedPayment() {
        PaymentFixture fixture = createReadyPayment();
        Payment payment = fixture.payment();
        payment.approve("pay-key", "CARD", "DONE");
        fixture.reservations().forEach(reservation -> {
            reservation.confirm();
            reservation.getSeat().book();
        });
        return fixture;
    }

    private record PaymentFixture(Payment payment, List<Reservation> reservations) {
    }
}
