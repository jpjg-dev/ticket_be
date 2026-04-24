package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossConfirmResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "logging.level.org.hibernate.orm.jdbc.bind=OFF",
        "logging.level.org.hibernate.type.descriptor.sql=OFF"
})
class PaymentServiceIntegrationTest {

    @MockitoBean
    private TossPaymentClient tossPaymentClient;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Set<Long> paymentIds = new LinkedHashSet<>();
    private final Set<Long> reservationIds = new LinkedHashSet<>();
    private final Set<Long> seatIds = new LinkedHashSet<>();
    private final Set<Long> scheduleIds = new LinkedHashSet<>();
    private final Set<Long> eventIds = new LinkedHashSet<>();
    private final Set<Long> userIds = new LinkedHashSet<>();

    @AfterEach
    void cleanup() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            paymentIds.forEach(paymentRepository::deleteById);
            reservationIds.forEach(reservationRepository::deleteById);
            seatIds.forEach(seatRepository::deleteById);
            scheduleIds.forEach(scheduleRepository::deleteById);
            eventIds.forEach(eventRepository::deleteById);
            userIds.forEach(userRepository::deleteById);
        });

        paymentIds.clear();
        reservationIds.clear();
        seatIds.clear();
        scheduleIds.clear();
        eventIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("readyPayment: 실제 DB에서 READY 결제를 생성하고 중복 요청은 기존 결제를 재사용한다")
    void readyPaymentCreateAndReuse() {
        Fixture fixture = createPendingReservationFixture(false);

        Payment first = paymentService.readyPayment(fixture.reservationId);
        Payment second = paymentService.readyPayment(fixture.reservationId);

        paymentIds.add(first.getId());

        assertEquals(first.getId(), second.getId());
        assertEquals(PaymentStatus.READY, first.getStatus());
        assertEquals(fixture.price, first.getAmount());
        assertNotNull(first.getOrderId());

        long paymentCount = paymentRepository.findAll().stream()
                .filter(p -> p.getReservation().getId().equals(fixture.reservationId))
                .count();
        assertEquals(1L, paymentCount);
    }

    @Test
    @DisplayName("readyPayment: 만료된 예약이면 예외가 발생하고 트랜잭션 롤백으로 기존 상태가 유지된다")
    void readyPaymentExpiredReservation() {
        Fixture fixture = createPendingReservationFixture(true);

        assertThrows(IllegalStateException.class,
                () -> paymentService.readyPayment(fixture.reservationId));

        Reservation updatedReservation = reservationRepository.findById(fixture.reservationId).orElseThrow();
        Seat updatedSeat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(ReservationStatus.PENDING, updatedReservation.getStatus());
        assertEquals(SeatStatus.HELD, updatedSeat.getStatus());
        assertTrue(paymentRepository.findByReservationId(fixture.reservationId).isEmpty());
    }

    @Test
    @DisplayName("confirmPayment: 실제 DB에서 결제 승인 시 Payment/Reservation/Seat 상태가 함께 확정된다")
    void confirmPaymentSuccess() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-success",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        Payment approved = paymentService.confirmPayment("pay-key-success", ready.getOrderId(), totalAmountWithVat);

        Reservation reservation = reservationRepository.findById(fixture.reservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
        assertEquals("pay-key-success", approved.getPaymentKey());
        assertEquals("DONE", approved.getPgStatus());
        assertEquals("CARD", approved.getMethod());

        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, seat.getStatus());
    }

    @Test
    @DisplayName("confirmPayment: PG 응답 타임아웃이어도 조회 결과가 DONE이면 실제 DB 상태를 승인으로 확정한다")
    void confirmPaymentReconcileAfterTimeout() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-reconcile"))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-reconcile",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        Payment approved = paymentService.confirmPayment("pay-key-reconcile", ready.getOrderId(), totalAmountWithVat);

        Reservation reservation = reservationRepository.findById(fixture.reservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
        assertEquals("pay-key-reconcile", approved.getPaymentKey());
        assertEquals("DONE", approved.getPgStatus());
        assertEquals("CARD", approved.getMethod());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, seat.getStatus());
    }

    @Test
    @DisplayName("confirmPayment: 만료된 예약이면 예외가 발생하고 트랜잭션 롤백으로 READY/PENDING/HELD가 유지된다")
    void confirmPaymentExpiredReservation() {
        Fixture fixture = createPendingReservationFixture(true);

        Reservation reservation = reservationRepository.findById(fixture.reservationId).orElseThrow();
        Payment payment = paymentRepository.save(new Payment(
                reservation,
                fixture.price,
                LocalDateTime.now(),
                "expired-order-" + System.nanoTime()
        ));
        paymentIds.add(payment.getId());

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-expired", payment.getOrderId(), amountWithVat(fixture.price)));

        Payment failed = paymentRepository.findById(payment.getId()).orElseThrow();
        Reservation expiredReservation = reservationRepository.findById(fixture.reservationId).orElseThrow();
        Seat releasedSeat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.READY, failed.getStatus());
        assertEquals(ReservationStatus.PENDING, expiredReservation.getStatus());
        assertEquals(SeatStatus.HELD, releasedSeat.getStatus());
    }

    @Test
    @DisplayName("confirmPayment: 결제 금액이 다르면 예외가 발생하고 트랜잭션 롤백으로 READY 상태가 유지된다")
    void confirmPaymentAmountMismatch() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationId);
        paymentIds.add(ready.getId());

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-mismatch", ready.getOrderId(), amountWithVat(ready.getAmount()) + 1));

        Payment failed = paymentRepository.findById(ready.getId()).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.reservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.READY, failed.getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, seat.getStatus());
    }

    @Test
    @DisplayName("confirmPayment: 존재하지 않는 orderId로 승인 요청하면 EntityNotFoundException이 발생한다")
    void confirmPaymentOrderIdNotFound() {
        assertThrows(EntityNotFoundException.class,
                () -> paymentService.confirmPayment("pay-key", "not-exists-order", 1000));
    }

    @Test
    @DisplayName("cancelPayment: PG 응답 타임아웃이어도 조회 결과가 CANCELED면 실제 DB 상태를 취소로 확정한다")
    void cancelPaymentReconcileAfterTimeout() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-cancel-ready",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        Payment approved = paymentService.confirmPayment("pay-key-cancel-ready", ready.getOrderId(), totalAmountWithVat);

        when(tossPaymentClient.cancel("pay-key-cancel-ready", "사용자 요청", "KRW", "cancel:" + approved.getId()))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-cancel-ready"))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-cancel-ready",
                        approved.getOrderId(),
                        "CANCELED",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        paymentService.cancelPayment(approved.getId(), "사용자 요청");

        Payment canceled = paymentRepository.findById(approved.getId()).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.reservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.CANCELED, canceled.getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, seat.getStatus());
    }

    private Fixture createPendingReservationFixture(boolean expiredReservation) {
        LocalDateTime now = LocalDateTime.now();
        String runId = String.valueOf(System.nanoTime());

        User user = userRepository.save(new User(
                "payment-test-" + runId + "@test.com",
                "password",
                "결제테스터",
                now
        ));
        userIds.add(user.getId());

        Event event = eventRepository.save(new Event(
                "결제 테스트 공연-" + runId,
                "PaymentService 통합 테스트",
                "테스트홀",
                now,
                now
        ));
        eventIds.add(event.getId());

        Schedule schedule = scheduleRepository.save(new Schedule(
                event,
                now.plusDays(1),
                now.plusDays(1).plusHours(2),
                now
        ));
        scheduleIds.add(schedule.getId());

        Seat seat = seatRepository.save(new Seat(
                schedule,
                "A-" + runId,
                "VIP",
                100000,
                now
        ));
        seat.hold();
        seat = seatRepository.save(seat);
        seatIds.add(seat.getId());

        Reservation reservation = reservationRepository.save(new Reservation(user, seat, now));
        if (expiredReservation) {
            ReflectionTestUtils.setField(reservation, "expiresAt", now.minusMinutes(1));
            reservation = reservationRepository.save(reservation);
        }
        reservationIds.add(reservation.getId());

        return new Fixture(reservation.getId(), seat.getId(), seat.getPrice());
    }

    private int amountWithVat(int amount) {
        return amount + (int) Math.round(amount * 0.1d);
    }

    private record Fixture(Long reservationId, Long seatId, Integer price) {
    }
}
