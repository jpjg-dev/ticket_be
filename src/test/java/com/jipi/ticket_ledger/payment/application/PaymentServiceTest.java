package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TossPaymentClient tossPaymentClient;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    @DisplayName("approvePayment: READY 결제를 승인하면 예약 확정/좌석 확정까지 반영된다")
    void approvePaymentSuccess() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation, 10000, LocalDateTime.now(), "order-1");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        paymentService.approvePayment(1L);

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("approvePayment: READY 상태가 아니면 예외가 발생한다")
    void approvePaymentWhenNotReady() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation, 10000, LocalDateTime.now(), "order-2");
        payment.approve("pay-key-1", "CARD", "DONE");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () -> paymentService.approvePayment(1L));
    }

    @Test
    @DisplayName("approvePayment: 예약이 PENDING이 아니면 예외가 발생한다")
    void approvePaymentWhenReservationNotPending() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        reservation.confirm();
        Payment payment = new Payment(reservation, 10000, LocalDateTime.now(), "order-3");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () -> paymentService.approvePayment(1L));
    }

    @Test
    @DisplayName("failPayment: 만료 전 실패면 결제만 FAILED로 변경되고 예약/좌석은 유지된다")
    void failPaymentSuccessNotExpired() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation, 10000, LocalDateTime.now(), "order-4");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        paymentService.failPayment(1L);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("failPayment: 만료 후 실패면 예약 만료 및 좌석 복구가 수행된다")
    void failPaymentWhenExpired() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().minusMinutes(1));
        Payment payment = new Payment(reservation, 10000, LocalDateTime.now(), "order-5");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        paymentService.failPayment(1L);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationStatus.EXPIRED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("failPayment: READY 상태가 아니면 예외가 발생한다")
    void failPaymentWhenNotReady() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation, 10000, LocalDateTime.now(), "order-6");
        payment.approve("pay-key-2", "CARD", "DONE");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () -> paymentService.failPayment(1L));
    }

    @Test
    @DisplayName("cancelPayment: APPROVED 결제를 취소하면 예약/좌석이 함께 복구된다")
    void cancelPaymentSuccess() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation, 10000, LocalDateTime.now(), "order-7");

        payment.approve("pay-key-3", "CARD", "DONE");
        reservation.confirm();
        reservation.getSeat().book();

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        doReturn(new TossCancelResponse("pay-key-3", "CANCELED", "10000", "KRW"))
                .when(tossPaymentClient)
                .cancel("pay-key-3", "사용자 요청", "KRW");

        paymentService.cancelPayment(1L, "사용자 요청");

        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("cancelPayment: APPROVED 상태가 아니면 예외가 발생한다")
    void cancelPaymentWhenNotApproved() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation, 10000, LocalDateTime.now(), "order-8");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () -> paymentService.cancelPayment(1L, "사용자 요청"));
    }

    @Test
    @DisplayName("결제를 찾을 수 없으면 EntityNotFoundException이 발생한다")
    void paymentNotFound() {
        when(paymentRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> paymentService.approvePayment(404L));
    }

    private Reservation createPendingReservationWithHeldSeat(LocalDateTime expiresAt) {
        Seat seat = createSeat();
        seat.hold();

        Reservation reservation = new Reservation(createUser(), seat, LocalDateTime.now());
        ReflectionTestUtils.setField(reservation, "expiresAt", expiresAt);
        return reservation;
    }

    private Seat createSeat() {
        Event event = new Event("공연", "설명", "장소", LocalDateTime.now(), LocalDateTime.now());
        Schedule schedule = new Schedule(event, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2), LocalDateTime.now());
        return new Seat(schedule, "A-1", "VIP", 100000, LocalDateTime.now());
    }

    private User createUser() {
        return new User("user@test.com", "password", "테스터", LocalDateTime.now());
    }
}

