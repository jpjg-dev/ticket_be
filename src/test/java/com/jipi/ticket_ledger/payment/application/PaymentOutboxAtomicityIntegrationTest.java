package com.jipi.ticket_ledger.payment.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.payment.application.event.PaymentEvent;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventOutbox;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGateway;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentAmount;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossConfirmResponse;
import com.jipi.ticket_ledger.payment.infrastructure.outbox.PaymentOutboxEvent;
import com.jipi.ticket_ledger.payment.infrastructure.outbox.PaymentOutboxEventRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class PaymentOutboxAtomicityIntegrationTest extends PostgresTestContainerSupport {

    @MockitoBean
    private PaymentGateway paymentGateway;

    @Autowired
    private PaymentEventOutbox paymentEventOutbox;

    @Autowired
    private PaymentOutboxEventRepository paymentOutboxEventRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationGroupRepository reservationGroupRepository;

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

    private final List<Long> paymentIds = new ArrayList<>();
    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> reservationGroupIds = new ArrayList<>();
    private final List<Long> seatIds = new ArrayList<>();
    private final List<Long> scheduleIds = new ArrayList<>();
    private final List<Long> eventIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            paymentOutboxEventRepository.deleteAllInBatch();
            paymentIds.forEach(paymentRepository::deleteById);
            reservationIds.forEach(reservationRepository::deleteById);
            reservationGroupIds.forEach(reservationGroupRepository::deleteById);
            seatIds.forEach(seatRepository::deleteById);
            scheduleIds.forEach(scheduleRepository::deleteById);
            eventIds.forEach(eventRepository::deleteById);
            userIds.forEach(userRepository::deleteById);
        });
    }

    @Test
    @DisplayName("승인 Outbox 저장 실패 시 APPROVED 전이도 롤백하고 CONFIRMING을 유지한다")
    void approvalRollsBackWhenOutboxAppendFails() {
        Fixture fixture = createPendingFixture();
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId());
        paymentIds.add(ready.getId());
        int totalAmount = PaymentAmount.fromSeatTotalAmount(ready.getAmount()).totalAmount();
        insertConflictingOutbox(ready, "PaymentApproved");

        when(paymentGateway.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-outbox-approval",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmount,
                        "KRW"
                ));
        assertThrows(
                RuntimeException.class,
                () -> paymentService.confirmPayment("pay-key-outbox-approval", ready.getOrderId(), totalAmount)
        );

        assertEquals(PaymentStatus.CONFIRMING, paymentRepository.findById(ready.getId()).orElseThrow().getStatus());
        assertEquals(
                ReservationGroupStatus.PENDING,
                reservationGroupRepository.findById(fixture.reservationGroupId()).orElseThrow().getStatus()
        );
        assertEquals(
                ReservationStatus.PENDING,
                reservationRepository.findById(fixture.reservationId()).orElseThrow().getStatus()
        );
        assertEquals(SeatStatus.HELD, seatRepository.findById(fixture.seatId()).orElseThrow().getStatus());
        assertEquals(1, paymentOutboxEventRepository.findAllByPaymentIdOrderByIdAsc(ready.getId()).size());
    }

    @Test
    @DisplayName("취소 Outbox 저장 실패 시 CANCELED 전이도 롤백하고 CANCELING을 유지한다")
    void cancellationRollsBackWhenOutboxAppendFails() {
        Fixture fixture = createPendingFixture();
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId());
        paymentIds.add(ready.getId());
        int totalAmount = PaymentAmount.fromSeatTotalAmount(ready.getAmount()).totalAmount();

        when(paymentGateway.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-outbox-cancel",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmount,
                        "KRW"
                ));
        paymentService.confirmPayment("pay-key-outbox-cancel", ready.getOrderId(), totalAmount);
        insertConflictingOutbox(ready, "PaymentCanceled");

        when(paymentGateway.cancel(
                "pay-key-outbox-cancel",
                "사용자 요청",
                "KRW",
                "cancel:" + ready.getId()
        )).thenReturn(new TossCancelResponse(
                "pay-key-outbox-cancel",
                "CANCELED",
                totalAmount,
                "KRW"
        ));

        assertThrows(
                RuntimeException.class,
                () -> paymentService.cancelPayment(ready.getId(), "사용자 요청", fixture.userId())
        );

        assertEquals(PaymentStatus.CANCELING, paymentRepository.findById(ready.getId()).orElseThrow().getStatus());
        assertEquals(
                ReservationGroupStatus.CONFIRMED,
                reservationGroupRepository.findById(fixture.reservationGroupId()).orElseThrow().getStatus()
        );
        assertEquals(
                ReservationStatus.CONFIRMED,
                reservationRepository.findById(fixture.reservationId()).orElseThrow().getStatus()
        );
        assertEquals(SeatStatus.BOOKED, seatRepository.findById(fixture.seatId()).orElseThrow().getStatus());
        assertEquals(2, paymentOutboxEventRepository.findAllByPaymentIdOrderByIdAsc(ready.getId()).size());
    }

    @Test
    @DisplayName("Outbox append는 기존 도메인 트랜잭션 밖에서 단독 커밋할 수 없다")
    void appendRequiresExistingTransaction() {
        PaymentEvent event = new PaymentEvent(
                UUID.randomUUID(),
                "PaymentApproved",
                1,
                1L,
                "order-1",
                1L,
                1L,
                11000,
                "KRW",
                Instant.now(),
                com.jipi.ticket_ledger.payment.application.event.PaymentEventSource.NORMAL
        );

        assertThrows(IllegalTransactionStateException.class, () -> paymentEventOutbox.append(event));
    }

    private Fixture createPendingFixture() {
        LocalDateTime now = LocalDateTime.now();
        String runId = String.valueOf(System.nanoTime());

        User user = userRepository.save(new User(
                "outbox-" + runId + "@test.com",
                "password",
                "Outbox Tester",
                now
        ));
        userIds.add(user.getId());

        Event event = eventRepository.save(new Event("Outbox Event " + runId, "test", "test", now, now));
        eventIds.add(event.getId());

        Schedule schedule = scheduleRepository.save(new Schedule(
                event,
                now.plusDays(1),
                now.plusDays(1).plusHours(2),
                now
        ));
        scheduleIds.add(schedule.getId());

        Seat seat = new Seat(schedule, "A-" + runId, "VIP", 100000, now);
        seat.hold();
        seat = seatRepository.save(seat);
        seatIds.add(seat.getId());

        ReservationGroup group = reservationGroupRepository.save(
                new ReservationGroup(user, now, now.plusMinutes(5))
        );
        reservationGroupIds.add(group.getId());

        Reservation reservation = reservationRepository.save(
                new Reservation(user, seat, group, now, now.plusMinutes(5))
        );
        reservationIds.add(reservation.getId());

        return new Fixture(group.getId(), reservation.getId(), seat.getId(), user.getId());
    }

    private void insertConflictingOutbox(Payment payment, String eventType) {
        Instant now = Instant.now();
        paymentOutboxEventRepository.saveAndFlush(PaymentOutboxEvent.pending(
                UUID.randomUUID(),
                payment.getId(),
                eventType,
                1,
                JsonNodeFactory.instance.objectNode().put("testConflict", true),
                now,
                now
        ));
    }

    private record Fixture(Long reservationGroupId, Long reservationId, Long seatId, Long userId) {
    }
}
