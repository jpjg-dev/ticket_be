package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentConfirmTransactionServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final PaymentConfirmTransactionService transactionService =
            new PaymentConfirmTransactionService(
                    paymentRepository, reservationRepository, new PaymentConfirmValidator(), Clock.systemDefaultZone());

    @Test
    @DisplayName("markConfirming: READY 결제를 CONFIRMING으로 바꾸고 PG 호출에 필요한 값만 반환한다")
    void markConfirmingReadyPayment() {
        ReservationGroup group = createReservationGroup(LocalDateTime.now().plusMinutes(5));
        Reservation reservation = createPendingReservationWithHeldSeat(group, LocalDateTime.now().plusMinutes(5));
        Payment payment = new Payment(group, 10000, LocalDateTime.now(), "order-marker", "KRW");

        when(paymentRepository.findByOrderIdForUpdate("order-marker")).thenReturn(Optional.of(payment));
        when(reservationRepository.findByReservationGroupId(group.getId())).thenReturn(List.of(reservation));

        ConfirmingPayment marked = transactionService.markConfirming("pay-key", "order-marker", 11000);

        assertEquals(PaymentStatus.CONFIRMING, payment.getStatus());
        assertEquals("order-marker", marked.orderId());
        assertEquals(11000, marked.totalAmountWithVat());
        assertEquals("KRW", marked.currency());
        assertFalse(marked.alreadyApproved());
    }

    private ReservationGroup createReservationGroup(LocalDateTime expiresAt) {
        User user = new User("user@test.com", "password", "name", LocalDateTime.now());
        return new ReservationGroup(user, LocalDateTime.now(), expiresAt);
    }

    private Reservation createPendingReservationWithHeldSeat(ReservationGroup group, LocalDateTime expiresAt) {
        Event event = new Event("공연", "설명", "장소", LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        Schedule schedule = new Schedule(
                event,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(2),
                LocalDateTime.now()
        );
        Seat seat = new Seat(schedule, "A-1", "VIP", 10000, LocalDateTime.now());
        seat.hold();
        Reservation reservation = new Reservation(group.getUser(), seat, group, LocalDateTime.now(), expiresAt);

        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, seat.getStatus());
        return reservation;
    }
}
