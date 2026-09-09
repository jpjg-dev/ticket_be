package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventOutbox;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentConfirmTransactionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final PaymentEventOutbox paymentEventOutbox = mock(PaymentEventOutbox.class);
    private final Clock clock = spy(Clock.fixed(NOW, ZoneOffset.UTC));
    private final PaymentConfirmTransactionService transactionService =
            new PaymentConfirmTransactionService(
                    paymentRepository, reservationRepository, new PaymentConfirmValidator(),
                    paymentEventOutbox,
                    clock);

    @Test
    @DisplayName("markConfirming: READY 결제를 CONFIRMING으로 바꾸고 PG 호출에 필요한 값만 반환한다")
    void markConfirmingReadyPayment() {
        ReservationGroup group = createReservationGroup(NOW.plusSeconds(300));
        Reservation reservation = createPendingReservationWithHeldSeat(group, NOW.plusSeconds(300));
        Payment payment = new Payment(group, 10000, NOW.minusSeconds(1), "order-marker", "KRW");

        when(paymentRepository.findByOrderIdForUpdate("order-marker")).thenReturn(Optional.of(payment));
        when(reservationRepository.findByReservationGroupId(group.getId())).thenReturn(List.of(reservation));

        ConfirmingPayment marked = transactionService.markConfirming("pay-key", "order-marker", 11000);

        assertEquals(PaymentStatus.CONFIRMING, payment.getStatus());
        assertEquals(NOW, payment.getConfirmingAt());
        verify(clock).instant();
        assertEquals("order-marker", marked.orderId());
        assertEquals(11000, marked.totalAmountWithVat());
        assertEquals("KRW", marked.currency());
        assertFalse(marked.alreadyApproved());
    }

    private ReservationGroup createReservationGroup(Instant expiresAt) {
        User user = new User("user@test.com", "password", "name", LocalDateTime.now());
        return new ReservationGroup(user, NOW.minusSeconds(1), expiresAt);
    }

    private Reservation createPendingReservationWithHeldSeat(ReservationGroup group, Instant expiresAt) {
        Event event = new Event("공연", "설명", "장소", LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        Schedule schedule = new Schedule(
                event,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(2),
                LocalDateTime.now()
        );
        Seat seat = new Seat(schedule, "A-1", "VIP", 10000, LocalDateTime.now());
        seat.hold();
        Reservation reservation = new Reservation(group.getUser(), seat, group, NOW.minusSeconds(1), expiresAt);

        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, seat.getStatus());
        return reservation;
    }
}
