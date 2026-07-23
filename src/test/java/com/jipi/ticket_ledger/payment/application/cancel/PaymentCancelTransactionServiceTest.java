package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.global.exception.ForbiddenAccessException;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCancelTransactionServiceTest {

    private static final Long OWNER_ID = 100L;
    private static final Long PAYMENT_ID = 1L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ReservationRepository reservationRepository;

    private PaymentCancelTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new PaymentCancelTransactionService(
                paymentRepository, reservationRepository, Clock.systemDefaultZone());
    }

    @Test
    @DisplayName("markCanceling: APPROVED 결제를 CANCELING 으로 마킹하고 스냅샷을 반환한다")
    void markCancelingFromApproved() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation);

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        CancelingPaymentSnapshot snapshot = transactionService.markCanceling(PAYMENT_ID, OWNER_ID);

        assertEquals(PaymentStatus.CANCELING, payment.getStatus());
        assertFalse(snapshot.alreadyCanceled());
        assertEquals("pay-key-1", snapshot.paymentKey());
        assertEquals(11000, snapshot.totalAmount());
        assertEquals("KRW", snapshot.currency());
        assertEquals(OWNER_ID, snapshot.ownerUserId());
    }

    @Test
    @DisplayName("markCanceling: 이미 CANCELED 면 멱등 종료 신호(alreadyCanceled)를 반환하고 상태를 유지한다")
    void markCancelingAlreadyCanceled() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation);
        payment.startCanceling(java.time.Instant.now());
        payment.cancel(java.time.Instant.now());

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        CancelingPaymentSnapshot snapshot = transactionService.markCanceling(PAYMENT_ID, OWNER_ID);

        assertTrue(snapshot.alreadyCanceled());
        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
    }

    @Test
    @DisplayName("markCanceling: 이미 CANCELING(재진입)이면 추가 마킹 없이 스냅샷을 반환한다")
    void markCancelingReentry() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation);
        payment.startCanceling(java.time.Instant.now());
        java.time.Instant cancelingAtBefore = payment.getCancelingAt();

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        CancelingPaymentSnapshot snapshot = transactionService.markCanceling(PAYMENT_ID, OWNER_ID);

        assertFalse(snapshot.alreadyCanceled());
        assertEquals(PaymentStatus.CANCELING, payment.getStatus());
        assertEquals(cancelingAtBefore, payment.getCancelingAt());
    }

    @Test
    @DisplayName("markCanceling: READY 등 취소 불가 상태면 예외가 발생한다")
    void markCancelingRejectsReady() {
        Reservation reservation = approvedReservation();
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-1", "KRW");
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        ReflectionTestUtils.setField(payment, "paymentKey", "pay-key-1"); // status 는 READY, key 는 있지만 상태로 거절

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () -> transactionService.markCanceling(PAYMENT_ID, OWNER_ID));
        assertEquals(PaymentStatus.READY, payment.getStatus());
    }

    @Test
    @DisplayName("markCanceling: paymentKey 가 없으면 예외가 발생한다")
    void markCancelingRejectsBlankPaymentKey() {
        Reservation reservation = approvedReservation();
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-1", "KRW");
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.APPROVED); // paymentKey 없는 APPROVED

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () -> transactionService.markCanceling(PAYMENT_ID, OWNER_ID));
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
    }

    @Test
    @DisplayName("markCanceling: 소유자가 아니면 예외가 발생하고 상태를 변경하지 않는다")
    void markCancelingRejectsNonOwner() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation);

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThrows(ForbiddenAccessException.class, () -> transactionService.markCanceling(PAYMENT_ID, 999L));
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
    }

    @Test
    @DisplayName("loadCancelingSnapshot: CANCELING 결제면 primitive 스냅샷을 정확히 반환한다")
    void loadCancelingSnapshotFromCanceling() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation);
        payment.startCanceling(java.time.Instant.now());

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        CancelingPaymentSnapshot snapshot = transactionService.loadCancelingSnapshot(PAYMENT_ID);

        assertEquals(PAYMENT_ID, snapshot.paymentId());
        assertEquals("order-1", snapshot.orderId());
        assertEquals(reservation.getReservationGroup().getId(), snapshot.reservationGroupId());
        assertEquals("pay-key-1", snapshot.paymentKey());
        assertEquals(11000, snapshot.totalAmount());
        assertEquals("KRW", snapshot.currency());
        assertEquals(OWNER_ID, snapshot.ownerUserId());
        assertFalse(snapshot.alreadyCanceled());
    }

    @Test
    @DisplayName("loadCancelingSnapshot: CANCELING 이 아니면(APPROVED 등) null 을 반환한다")
    void loadCancelingSnapshotReturnsNullWhenNotCanceling() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation); // APPROVED, CANCELING 아님

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertNull(transactionService.loadCancelingSnapshot(PAYMENT_ID));
    }

    @Test
    @DisplayName("applyDecision: FINALIZE 면 결제/그룹/예약/좌석을 함께 취소 확정한다")
    void applyDecisionFinalize() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation);
        payment.startCanceling(java.time.Instant.now());

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(reservationRepository.findByReservationGroupId(reservation.getReservationGroup().getId()))
                .thenReturn(List.of(reservation));

        transactionService.applyDecision(PAYMENT_ID, CancelDecision.finalizeCancel());

        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CANCELED, reservation.getReservationGroup().getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("applyDecision: 재락 시점에 CANCELING 이 아니면 no-op(멱등)")
    void applyDecisionNoopWhenNotCanceling() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation); // APPROVED, CANCELING 아님

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        transactionService.applyDecision(PAYMENT_ID, CancelDecision.finalizeCancel());

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
    }

    @Test
    @DisplayName("applyDecision: HOLD_MANUAL 이면 무쓰기(상태 유지)")
    void applyDecisionHoldManualNoWrite() {
        Reservation reservation = approvedReservation();
        Payment payment = approvedPayment(reservation);
        payment.startCanceling(java.time.Instant.now());

        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        transactionService.applyDecision(PAYMENT_ID, CancelDecision.holdManual());

        assertEquals(PaymentStatus.CANCELING, payment.getStatus());
        assertEquals(ReservationGroupStatus.CONFIRMED, reservation.getReservationGroup().getStatus());
        assertEquals(SeatStatus.BOOKED, reservation.getSeat().getStatus());
    }

    private Reservation approvedReservation() {
        User owner = new User("owner@test.com", "password", "소유자", LocalDateTime.now());
        ReflectionTestUtils.setField(owner, "id", OWNER_ID);
        ReservationGroup group = new ReservationGroup(owner, LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        ReflectionTestUtils.setField(group, "id", 10L);

        Event event = new Event("공연", "설명", "장소", LocalDateTime.now(), LocalDateTime.now());
        Schedule schedule = new Schedule(event, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2), LocalDateTime.now());
        Seat seat = new Seat(schedule, "A-1", "VIP", 100000, LocalDateTime.now());
        seat.hold();
        seat.book();

        Reservation reservation = new Reservation(owner, seat, group, LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        reservation.confirm();
        group.confirm();
        return reservation;
    }

    private Payment approvedPayment(Reservation reservation) {
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-1", "KRW");
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        payment.confirming();
        payment.approve("pay-key-1", "CARD", "DONE");
        return payment;
    }
}
