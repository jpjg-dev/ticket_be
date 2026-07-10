package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.global.exception.ForbiddenAccessException;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentAmount;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossConfirmResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.payment.application.recovery.PaymentRecoveryService;
import com.jipi.ticket_ledger.payment.presentation.PayMentController;
import com.jipi.ticket_ledger.payment.presentation.dto.ConfirmPaymentRequest;
import com.jipi.ticket_ledger.payment.presentation.dto.ConfirmPaymentResponse;
import com.jipi.ticket_ledger.reservation.application.ReservationExpirationService;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "logging.level.org.hibernate.orm.jdbc.bind=OFF",
        "logging.level.org.hibernate.type.descriptor.sql=OFF"
})
class PaymentServiceIntegrationTest extends PostgresTestContainerSupport {

    @MockitoBean
    private TossPaymentClient tossPaymentClient;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRecoveryService paymentRecoveryService;

    @Autowired
    private PayMentController payMentController;

    @Autowired
    private ReservationExpirationService reservationExpirationService;

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

    private final Set<Long> paymentIds = new LinkedHashSet<>();
    private final Set<Long> savedReservationIds = new LinkedHashSet<>();
    private final Set<Long> reservationGroupIds = new LinkedHashSet<>();
    private final Set<Long> seatIds = new LinkedHashSet<>();
    private final Set<Long> scheduleIds = new LinkedHashSet<>();
    private final Set<Long> eventIds = new LinkedHashSet<>();
    private final Set<Long> userIds = new LinkedHashSet<>();

    @AfterEach
    void cleanup() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            paymentIds.forEach(paymentRepository::deleteById);
            savedReservationIds.forEach(reservationRepository::deleteById);
            reservationGroupIds.forEach(reservationGroupRepository::deleteById);
            seatIds.forEach(seatRepository::deleteById);
            scheduleIds.forEach(scheduleRepository::deleteById);
            eventIds.forEach(eventRepository::deleteById);
            userIds.forEach(userRepository::deleteById);
        });

        paymentIds.clear();
        savedReservationIds.clear();
        reservationGroupIds.clear();
        seatIds.clear();
        scheduleIds.clear();
        eventIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("readyPayment: 실제 DB에서 READY 결제를 생성하고 중복 요청은 기존 결제를 재사용한다")
    void readyPaymentCreateAndReuse() {
        Fixture fixture = createPendingReservationFixture(false);

        Payment first = paymentService.readyPayment(fixture.reservationGroupId);
        Payment second = paymentService.readyPayment(fixture.reservationGroupId);

        paymentIds.add(first.getId());

        assertEquals(first.getId(), second.getId());
        assertEquals(PaymentStatus.READY, first.getStatus());
        assertEquals(fixture.price, first.getAmount());
        assertNotNull(first.getOrderId());

        long paymentCount = paymentRepository.findAll().stream()
                .filter(p -> p.getReservationGroup().getId().equals(fixture.reservationGroupId))
                .count();
        assertEquals(1L, paymentCount);
    }

    @Test
    @DisplayName("readyPayment: 만료된 예약이면 예외가 발생하고 트랜잭션 롤백으로 기존 상태가 유지된다")
    void readyPaymentExpiredReservation() {
        Fixture fixture = createPendingReservationFixture(true);

        assertThrows(IllegalStateException.class,
                () -> paymentService.readyPayment(fixture.reservationGroupId));

        Reservation updatedReservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat updatedSeat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(ReservationStatus.PENDING, updatedReservation.getStatus());
        assertEquals(SeatStatus.HELD, updatedSeat.getStatus());
        assertTrue(paymentRepository.findByReservationGroupId(fixture.reservationGroupId).isEmpty());
    }

    @Test
    @DisplayName("confirmPayment: 실제 DB에서 결제 승인 시 Payment/Reservation/Seat 상태가 함께 확정된다")
    void confirmPaymentSuccess() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
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

        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
        assertEquals("pay-key-success", approved.getPaymentKey());
        assertEquals("DONE", approved.getPgStatus());
        assertEquals("CARD", approved.getMethod());

        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, seat.getStatus());
    }

    @Test
    @DisplayName("confirmPayment: 동일 orderId 승인 동시 요청은 중복 결제를 만들지 않고 확정 상태로 수렴한다")
    void confirmPaymentDuplicateRequestConvergesWithoutDuplicatePayment() throws Exception {
        Fixture fixture = createPendingReservationFixture(false, 2);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch confirmReachedPg = new CountDownLatch(1);
        CountDownLatch releasePg = new CountDownLatch(1);

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    confirmReachedPg.countDown();
                    if (!releasePg.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("PG 응답 대기 제어에 실패했습니다.");
                    }
                    return new TossConfirmResponse(
                            "pay-key-duplicate-confirm",
                            ready.getOrderId(),
                            "DONE",
                            "CARD",
                            totalAmountWithVat,
                            "KRW"
                    );
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Payment> first = executor.submit(() -> {
                startLatch.await();
                return paymentService.confirmPayment("pay-key-duplicate-confirm", ready.getOrderId(), totalAmountWithVat);
            });
            Future<Payment> second = executor.submit(() -> {
                startLatch.await();
                return paymentService.confirmPayment("pay-key-duplicate-confirm", ready.getOrderId(), totalAmountWithVat);
            });

            startLatch.countDown();
            assertTrue(confirmReachedPg.await(10, TimeUnit.SECONDS), "첫 승인 요청이 PG 호출 지점까지 진입해야 합니다.");
            releasePg.countDown();

            assertEquals(PaymentStatus.APPROVED, first.get(10, TimeUnit.SECONDS).getStatus());
            assertEquals(PaymentStatus.APPROVED, second.get(10, TimeUnit.SECONDS).getStatus());
        } finally {
            startLatch.countDown();
            releasePg.countDown();
            executor.shutdownNow();
        }

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(fixture.reservationGroupId);
        List<Seat> seats = seatRepository.findAllById(fixture.seatIds);
        long paymentCount = paymentRepository.findAll().stream()
                .filter(candidate -> candidate.getReservationGroup().getId().equals(fixture.reservationGroupId))
                .count();
        long approvedPaymentCount = paymentRepository.findAll().stream()
                .filter(candidate -> candidate.getReservationGroup().getId().equals(fixture.reservationGroupId))
                .filter(candidate -> candidate.getStatus() == PaymentStatus.APPROVED)
                .count();

        verify(tossPaymentClient, times(2))
                .confirm("pay-key-duplicate-confirm", ready.getOrderId(), totalAmountWithVat, "confirm:" + ready.getOrderId());
        assertEquals(1L, paymentCount);
        assertEquals(1L, approvedPaymentCount);
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CONFIRMED, group.getStatus());
        assertTrue(reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.CONFIRMED));
        assertTrue(seats.stream().allMatch(seat -> seat.getStatus() == SeatStatus.BOOKED));
    }

    @Test
    @DisplayName("confirmPayment: 승인과 만료가 경합하면 먼저 락을 획득한 승인 상태만 남는다")
    void confirmPaymentWinsAgainstConcurrentExpiration() throws Exception {
        Fixture fixture = createPendingReservationFixture(false);
        ReservationGroup reservationGroup = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        Instant expiresAt = Instant.now().plusSeconds(2);
        ReflectionTestUtils.setField(reservationGroup, "expiresAt", expiresAt);
        reservationGroupRepository.saveAndFlush(reservationGroup);

        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());
        CountDownLatch confirmReachedPg = new CountDownLatch(1);
        CountDownLatch releasePg = new CountDownLatch(1);

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    confirmReachedPg.countDown();
                    if (!releasePg.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("PG 응답 대기 제어에 실패했습니다.");
                    }
                    return new TossConfirmResponse(
                            "pay-key-concurrent-confirm",
                            ready.getOrderId(),
                            "DONE",
                            "CARD",
                            totalAmountWithVat,
                            "KRW"
                    );
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Payment> confirmFuture = executor.submit(
                    () -> paymentService.confirmPayment("pay-key-concurrent-confirm", ready.getOrderId(), totalAmountWithVat)
            );
            assertTrue(confirmReachedPg.await(10, TimeUnit.SECONDS), "승인 요청이 PG 호출 지점까지 진입해야 합니다.");

            long millisUntilExpired = Duration.between(Instant.now(), expiresAt).toMillis();
            if (millisUntilExpired > 0) {
                Thread.sleep(millisUntilExpired + 100);
            }

            Future<Integer> expirationFuture = executor.submit(
                    () -> reservationExpirationService.expireByScheduleId(fixture.scheduleId)
            );

            assertEquals(0, expirationFuture.get(10, TimeUnit.SECONDS));

            releasePg.countDown();

            assertEquals(PaymentStatus.APPROVED, confirmFuture.get(10, TimeUnit.SECONDS).getStatus());
        } finally {
            releasePg.countDown();
            executor.shutdownNow();
        }

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(fixture.reservationGroupId);
        List<Seat> seats = seatRepository.findAllById(fixture.seatIds);

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CONFIRMED, group.getStatus());
        assertTrue(reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.CONFIRMED));
        assertTrue(seats.stream().allMatch(seat -> seat.getStatus() == SeatStatus.BOOKED));
    }

    @Test
    @DisplayName("confirmPayment: 이미 만료된 결제에 승인과 만료가 경합하면 만료 상태만 남는다")
    void expirationWinsAgainstConcurrentConfirmPayment() throws Exception {
        Fixture fixture = createPendingReservationFixture(true);
        Payment ready = paymentRepository.save(new Payment(
                reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow(),
                fixture.price,
                LocalDateTime.now(),
                "expired-concurrent-order-" + System.nanoTime()
        ));
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());
        CountDownLatch startLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Payment> confirmFuture = executor.submit(() -> {
                startLatch.await();
                return paymentService.confirmPayment("pay-key-expired-concurrent", ready.getOrderId(), totalAmountWithVat);
            });
            Future<Integer> expirationFuture = executor.submit(() -> {
                startLatch.await();
                return reservationExpirationService.expireByScheduleId(fixture.scheduleId);
            });

            startLatch.countDown();

            Exception confirmationFailure = assertThrows(
                    Exception.class,
                    () -> confirmFuture.get(10, TimeUnit.SECONDS)
            );
            assertInstanceOf(IllegalStateException.class, confirmationFailure.getCause());
            assertEquals(1, expirationFuture.get(10, TimeUnit.SECONDS));
        } finally {
            startLatch.countDown();
            executor.shutdownNow();
        }

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(fixture.reservationGroupId);
        List<Seat> seats = seatRepository.findAllById(fixture.seatIds);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationGroupStatus.EXPIRED, group.getStatus());
        assertTrue(reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.EXPIRED));
        assertTrue(seats.stream().allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE));
        verifyNoInteractions(tossPaymentClient);
    }

    @Test
    @DisplayName("confirmPayment: PG 응답 타임아웃이어도 조회 결과가 DONE이면 실제 DB 상태를 승인으로 확정한다")
    void confirmPaymentReconcileAfterTimeout() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
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

        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
        assertEquals("pay-key-reconcile", approved.getPaymentKey());
        assertEquals("DONE", approved.getPgStatus());
        assertEquals("CARD", approved.getMethod());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, seat.getStatus());
    }

    @Test
    @DisplayName("recoverConfirmingPayments: 오래된 CONFIRMING 결제는 PG DONE 조회 결과로 승인 상태에 수렴한다")
    void recoverConfirmingPaymentDone() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
            payment.confirming();
            ReflectionTestUtils.setField(payment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });

        when(tossPaymentClient.getPaymentByOrderId(ready.getOrderId()))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-recovered",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        int recoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(java.time.Duration.ZERO, Integer.MAX_VALUE);

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(1, recoveredCount);
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals("pay-key-recovered", payment.getPaymentKey());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, seat.getStatus());
    }

    @Test
    @DisplayName("recoverConfirmingPayments: 설정된 batch-size 만큼만 오래된 CONFIRMING 결제를 보정한다")
    void recoverConfirmingPaymentsUsesConfiguredBatchSize() {
        Fixture firstFixture = createPendingReservationFixture(false);
        Payment firstReady = paymentService.readyPayment(firstFixture.reservationGroupId);
        paymentIds.add(firstReady.getId());
        int firstTotalAmountWithVat = amountWithVat(firstReady.getAmount());

        Fixture secondFixture = createPendingReservationFixture(false);
        Payment secondReady = paymentService.readyPayment(secondFixture.reservationGroupId);
        paymentIds.add(secondReady.getId());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Payment firstPayment = paymentRepository.findById(firstReady.getId()).orElseThrow();
            firstPayment.confirming();
            ReflectionTestUtils.setField(firstPayment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));

            Payment secondPayment = paymentRepository.findById(secondReady.getId()).orElseThrow();
            secondPayment.confirming();
            ReflectionTestUtils.setField(secondPayment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });

        when(tossPaymentClient.getPaymentByOrderId(firstReady.getOrderId()))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-batch-first",
                        firstReady.getOrderId(),
                        "DONE",
                        "CARD",
                        firstTotalAmountWithVat,
                        "KRW"
                ));

        int recoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(Duration.ZERO, 1);

        Payment firstPayment = paymentRepository.findById(firstReady.getId()).orElseThrow();
        Payment secondPayment = paymentRepository.findById(secondReady.getId()).orElseThrow();

        assertEquals(1, recoveredCount);
        assertEquals(PaymentStatus.APPROVED, firstPayment.getStatus());
        assertEquals(PaymentStatus.CONFIRMING, secondPayment.getStatus());
        verify(tossPaymentClient, times(1)).getPaymentByOrderId(firstReady.getOrderId());
        verify(tossPaymentClient, never()).getPaymentByOrderId(secondReady.getOrderId());
    }

    @Test
    @DisplayName("recoverConfirmingPayments: PG DONE이지만 예약이 만료돼 좌석이 유효하지 않으면 환불 후 FAILED로 정리하고 좌석을 푼다")
    void recoverConfirmingPaymentDoneButExpiredRefunds() {
        Fixture fixture = createPendingReservationFixture(true); // 만료된 예약: group expiresAt 과거, 좌석은 아직 HELD
        Payment ready = paymentRepository.save(new Payment(
                reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow(),
                fixture.price,
                LocalDateTime.now(),
                "expired-confirming-order-" + System.nanoTime()
        ));
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
            payment.confirming();
            ReflectionTestUtils.setField(payment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });

        when(tossPaymentClient.getPaymentByOrderId(ready.getOrderId()))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-refunded",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));
        when(tossPaymentClient.cancel("pay-key-refunded", "SEAT_UNAVAILABLE", "KRW", "cancel:" + ready.getId()))
                .thenReturn(new TossCancelResponse("pay-key-refunded", "CANCELED", totalAmountWithVat, "KRW"));

        int recoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(java.time.Duration.ZERO, Integer.MAX_VALUE);

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(1, recoveredCount);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationGroupStatus.EXPIRED, group.getStatus());
        assertEquals(ReservationStatus.EXPIRED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, seat.getStatus());
        verify(tossPaymentClient, times(1))
                .cancel("pay-key-refunded", "SEAT_UNAVAILABLE", "KRW", "cancel:" + ready.getId());
    }

    @Test
    @DisplayName("recoverConfirmingPayments: 좌석 유실 환불이 타임아웃되면 CONFIRMING을 유지하고 다음 주기에 재시도한다")
    void recoverConfirmingPaymentDoneButRefundTimeoutKeepsConfirmingForRetry() {
        Fixture fixture = createPendingReservationFixture(true);
        Payment ready = paymentRepository.save(new Payment(
                reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow(),
                fixture.price,
                LocalDateTime.now(),
                "expired-confirming-refund-timeout-order-" + System.nanoTime()
        ));
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
            payment.confirming();
            ReflectionTestUtils.setField(payment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });

        TossPaymentLookupResponse lookupResponse = new TossPaymentLookupResponse(
                "pay-key-refund-timeout",
                ready.getOrderId(),
                "DONE",
                "CARD",
                totalAmountWithVat,
                "KRW"
        );
        when(tossPaymentClient.getPaymentByOrderId(ready.getOrderId()))
                .thenReturn(lookupResponse, lookupResponse);
        when(tossPaymentClient.cancel("pay-key-refund-timeout", "SEAT_UNAVAILABLE", "KRW", "cancel:" + ready.getId()))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(new TossCancelResponse("pay-key-refund-timeout", "CANCELED", totalAmountWithVat, "KRW"));

        int firstRecoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(java.time.Duration.ZERO, Integer.MAX_VALUE);

        Payment stillConfirming = paymentRepository.findById(ready.getId()).orElseThrow();
        ReservationGroup stillPendingGroup = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        Reservation stillPendingReservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat stillHeldSeat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(0, firstRecoveredCount);
        assertEquals(PaymentStatus.CONFIRMING, stillConfirming.getStatus());
        assertEquals(ReservationGroupStatus.PENDING, stillPendingGroup.getStatus());
        assertEquals(ReservationStatus.PENDING, stillPendingReservation.getStatus());
        assertEquals(SeatStatus.HELD, stillHeldSeat.getStatus());

        int secondRecoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(java.time.Duration.ZERO, Integer.MAX_VALUE);

        Payment failed = paymentRepository.findById(ready.getId()).orElseThrow();
        ReservationGroup expiredGroup = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        Reservation expiredReservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat releasedSeat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(1, secondRecoveredCount);
        assertEquals(PaymentStatus.FAILED, failed.getStatus());
        assertEquals(ReservationGroupStatus.EXPIRED, expiredGroup.getStatus());
        assertEquals(ReservationStatus.EXPIRED, expiredReservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, releasedSeat.getStatus());
        verify(tossPaymentClient, times(2))
                .cancel("pay-key-refund-timeout", "SEAT_UNAVAILABLE", "KRW", "cancel:" + ready.getId());
    }

    @Test
    @DisplayName("recoverConfirmingPayments: 한 건이 예외로 실패해도 배치가 멈추지 않고 나머지를 계속 보정한다")
    void recoverConfirmingPaymentsIsolatesFailingItem() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 독성 결제(A): 재조회가 항상 예외를 던져 보정에 실패한다 (예: 예상 못 한 NPE)
        Fixture fixtureA = createPendingReservationFixture(false);
        Payment poison = paymentRepository.save(new Payment(
                reservationGroupRepository.findById(fixtureA.reservationGroupId).orElseThrow(),
                fixtureA.price,
                LocalDateTime.now(),
                "poison-order-" + System.nanoTime()
        ));
        paymentIds.add(poison.getId());
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(poison.getId()).orElseThrow();
            payment.confirming();
            ReflectionTestUtils.setField(payment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });
        when(tossPaymentClient.getPaymentByOrderId(poison.getOrderId()))
                .thenThrow(new RuntimeException("simulated NPE during reconcile"));

        // 정상 결제(B): DONE + 좌석 유효 → 정상적으로 보정되어야 한다
        Fixture fixtureB = createPendingReservationFixture(false);
        Payment healthy = paymentService.readyPayment(fixtureB.reservationGroupId);
        paymentIds.add(healthy.getId());
        int totalAmountWithVat = amountWithVat(healthy.getAmount());
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(healthy.getId()).orElseThrow();
            payment.confirming();
            ReflectionTestUtils.setField(payment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });
        when(tossPaymentClient.getPaymentByOrderId(healthy.getOrderId()))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-healthy",
                        healthy.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        int recoveredCount = assertDoesNotThrow(() ->
                paymentRecoveryService.reconcileStaleConfirmingPayments(java.time.Duration.ZERO, Integer.MAX_VALUE));

        Payment poisonAfter = paymentRepository.findById(poison.getId()).orElseThrow();
        Payment healthyAfter = paymentRepository.findById(healthy.getId()).orElseThrow();

        assertEquals(1, recoveredCount);                                  // 정상 건만 복구
        assertEquals(PaymentStatus.CONFIRMING, poisonAfter.getStatus());  // 독성 건은 격리되어 그대로 남음
        assertEquals(PaymentStatus.APPROVED, healthyAfter.getStatus());   // 뒤 건이 막히지 않고 보정됨
    }

    @Test
    @DisplayName("recoverConfirmingPayments: PG DONE이지만 금액이 불일치하면 환불 후 FAILED로 정리하고 좌석을 푼다")
    void recoverConfirmingPaymentDoneButAmountMismatchRefunds() {
        Fixture fixture = createPendingReservationFixture(false); // 유효한 예약, 좌석은 HELD
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
            payment.confirming();
            ReflectionTestUtils.setField(payment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });

        when(tossPaymentClient.getPaymentByOrderId(ready.getOrderId()))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-amount-mismatch",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat + 1, // 우리 기대 금액과 불일치
                        "KRW"
                ));
        when(tossPaymentClient.cancel("pay-key-amount-mismatch", "PG_DATA_MISMATCH", "KRW", "cancel:" + ready.getId()))
                .thenReturn(new TossCancelResponse("pay-key-amount-mismatch", "CANCELED", totalAmountWithVat + 1, "KRW"));

        int recoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(java.time.Duration.ZERO, Integer.MAX_VALUE);

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(1, recoveredCount);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationGroupStatus.EXPIRED, group.getStatus());
        assertEquals(ReservationStatus.EXPIRED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, seat.getStatus());
        verify(tossPaymentClient, times(1))
                .cancel("pay-key-amount-mismatch", "PG_DATA_MISMATCH", "KRW", "cancel:" + ready.getId());
    }

    @Test
    @DisplayName("recoverConfirmingPayments: PG 조회 orderId가 불일치하면 자동 처리하지 않고 CONFIRMING을 유지한다")
    void recoverConfirmingPaymentOrderIdMismatchKeepsConfirming() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
            payment.confirming();
            ReflectionTestUtils.setField(payment, "confirmingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });

        when(tossPaymentClient.getPaymentByOrderId(ready.getOrderId()))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-order-mismatch",
                        "different-order-id", // 우리 orderId와 불일치 → 우리 결제건 여부 의심
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        int recoveredCount = paymentRecoveryService.reconcileStaleConfirmingPayments(java.time.Duration.ZERO, Integer.MAX_VALUE);

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(0, recoveredCount);
        assertEquals(PaymentStatus.CONFIRMING, payment.getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, seat.getStatus());
        verify(tossPaymentClient, never()).cancel(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("컨트롤러 confirm: 서비스 재조회까지 실패해 CONFIRMING으로 남아도 동기 재조회가 DONE이면 APPROVED를 반환한다")
    void confirmControllerReconcilesConfirmingToApproved() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-ctrl-reconcile"))
                .thenThrow(new ResourceAccessException("timeout")); // 서비스 1차 재조회도 실패 → CONFIRMING 잔존
        when(tossPaymentClient.getPaymentByOrderId(ready.getOrderId()))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-ctrl-reconcile",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        // 실제 HTTP 요청의 OSIV 세션을 흉내내기 위해 트랜잭션 경계 안에서 컨트롤러를 호출한다(응답의 lazy 연관 로딩).
        ConfirmPaymentResponse response = new TransactionTemplate(transactionManager).execute(status ->
                payMentController.confirmPayment(
                        new ConfirmPaymentRequest("pay-key-ctrl-reconcile", ready.getOrderId(), totalAmountWithVat)));

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.APPROVED, response.paymentStatus());
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(SeatStatus.BOOKED, seat.getStatus());
    }

    @Test
    @DisplayName("컨트롤러 confirm: PG가 끝까지 응답하지 않으면 500 대신 CONFIRMING 상태를 반환하고 스케줄러에 위임한다")
    void confirmControllerReturnsConfirmingWhenPgStillDown() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-ctrl-down"))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByOrderId(ready.getOrderId()))
                .thenThrow(new ResourceAccessException("timeout")); // 컨트롤러 동기 재조회도 실패

        ConfirmPaymentResponse response = new TransactionTemplate(transactionManager).execute(status ->
                payMentController.confirmPayment(
                        new ConfirmPaymentRequest("pay-key-ctrl-down", ready.getOrderId(), totalAmountWithVat)));

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.CONFIRMING, response.paymentStatus());
        assertEquals(PaymentStatus.CONFIRMING, payment.getStatus());
        assertEquals(SeatStatus.HELD, seat.getStatus());
    }

    @Test
    @DisplayName("컨트롤러 confirm: 만료 등 정상 비즈니스 거절(결제가 CONFIRMING이 아님)은 동기 재조회로 삼키지 않고 예외를 그대로 전파한다")
    void confirmControllerPropagatesBusinessRejection() {
        Fixture fixture = createPendingReservationFixture(true);
        Payment ready = paymentRepository.save(new Payment(
                reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow(),
                fixture.price,
                LocalDateTime.now(),
                "ctrl-expired-order-" + System.nanoTime()
        ));
        paymentIds.add(ready.getId());

        assertThrows(IllegalStateException.class, () -> payMentController.confirmPayment(
                new ConfirmPaymentRequest("pay-key-ctrl-expired", ready.getOrderId(), amountWithVat(fixture.price))));

        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        assertEquals(PaymentStatus.READY, payment.getStatus());
        verifyNoInteractions(tossPaymentClient);
    }

    @Test
    @DisplayName("confirmPayment: 만료된 예약이면 예외가 발생하고 트랜잭션 롤백으로 READY/PENDING/HELD가 유지된다")
    void confirmPaymentExpiredReservation() {
        Fixture fixture = createPendingReservationFixture(true);

        Payment payment = paymentRepository.save(new Payment(
                reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow(),
                fixture.price,
                LocalDateTime.now(),
                "expired-order-" + System.nanoTime()
        ));
        paymentIds.add(payment.getId());

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-expired", payment.getOrderId(), amountWithVat(fixture.price)));

        Payment failed = paymentRepository.findById(payment.getId()).orElseThrow();
        Reservation expiredReservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat releasedSeat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.READY, failed.getStatus());
        assertEquals(ReservationStatus.PENDING, expiredReservation.getStatus());
        assertEquals(SeatStatus.HELD, releasedSeat.getStatus());
    }

    @Test
    @DisplayName("confirmPayment: 결제 금액이 다르면 예외가 발생하고 트랜잭션 롤백으로 READY 상태가 유지된다")
    void confirmPaymentAmountMismatch() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-mismatch", ready.getOrderId(), amountWithVat(ready.getAmount()) + 1));

        Payment failed = paymentRepository.findById(ready.getId()).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.READY, failed.getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, seat.getStatus());
    }

    @Test
    @DisplayName("confirmPayment: PG 승인 응답 금액이 다르면 예외가 발생하고 CONFIRMING/PENDING/HELD가 유지된다")
    void confirmPaymentRejectsPgAmountMismatch() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-pg-amount-mismatch",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat + 1,
                        "KRW"
                ));

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-pg-amount-mismatch", ready.getOrderId(), totalAmountWithVat));

        assertPaymentApprovalRejected(ready, fixture);
    }

    @Test
    @DisplayName("confirmPayment: PG 승인 응답 통화가 다르면 예외가 발생하고 CONFIRMING/PENDING/HELD가 유지된다")
    void confirmPaymentRejectsPgCurrencyMismatch() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-pg-currency-mismatch",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "USD"
                ));

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-pg-currency-mismatch", ready.getOrderId(), totalAmountWithVat));

        assertPaymentApprovalRejected(ready, fixture);
    }

    @Test
    @DisplayName("confirmPayment: PG 승인 응답 결제키가 다르면 예외가 발생하고 CONFIRMING/PENDING/HELD가 유지된다")
    void confirmPaymentRejectsPgPaymentKeyMismatch() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "different-pay-key",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-pg-key-mismatch", ready.getOrderId(), totalAmountWithVat));

        assertPaymentApprovalRejected(ready, fixture);
    }

    @Test
    @DisplayName("confirmPayment: PG 승인 응답 주문번호가 다르면 예외가 발생하고 CONFIRMING/PENDING/HELD가 유지된다")
    void confirmPaymentRejectsPgOrderIdMismatch() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-pg-order-mismatch",
                        "different-order-id",
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-pg-order-mismatch", ready.getOrderId(), totalAmountWithVat));

        assertPaymentApprovalRejected(ready, fixture);
    }

    @Test
    @DisplayName("confirmPayment: PG 승인 응답 상태가 DONE이 아니면 예외가 발생하고 CONFIRMING/PENDING/HELD가 유지된다")
    void confirmPaymentRejectsPgStatusMismatch() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-pg-status-mismatch",
                        ready.getOrderId(),
                        "WAITING_FOR_DEPOSIT",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-pg-status-mismatch", ready.getOrderId(), totalAmountWithVat));

        assertPaymentApprovalRejected(ready, fixture);
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
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
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

        paymentService.cancelPayment(approved.getId(), "사용자 요청", fixture.userId);

        Payment canceled = paymentRepository.findById(approved.getId()).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.CANCELED, canceled.getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, seat.getStatus());
    }

    @Test
    @DisplayName("cancelPayment: 동일 paymentId 취소 동시 요청은 같은 멱등키로 PG를 재호출해도 취소 상태로 수렴한다")
    void cancelPaymentDuplicateRequestConvergesWithSameIdempotencyKey() throws Exception {
        Fixture fixture = createPendingReservationFixture(false, 2);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-duplicate-cancel",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));
        Payment approved = paymentService.confirmPayment("pay-key-duplicate-cancel", ready.getOrderId(), totalAmountWithVat);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch cancelReachedPg = new CountDownLatch(1);
        CountDownLatch releasePg = new CountDownLatch(1);
        when(tossPaymentClient.cancel("pay-key-duplicate-cancel", "사용자 요청", "KRW", "cancel:" + approved.getId()))
                .thenAnswer(invocation -> {
                    cancelReachedPg.countDown();
                    if (!releasePg.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("PG 응답 대기 제어에 실패했습니다.");
                    }
                    return new TossCancelResponse(
                            "pay-key-duplicate-cancel",
                            "CANCELED",
                            totalAmountWithVat,
                            "KRW"
                    );
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                startLatch.await();
                paymentService.cancelPayment(approved.getId(), "사용자 요청", fixture.userId);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                startLatch.await();
                paymentService.cancelPayment(approved.getId(), "사용자 요청", fixture.userId);
                return null;
            });

            startLatch.countDown();
            assertTrue(cancelReachedPg.await(10, TimeUnit.SECONDS), "첫 취소 요청이 PG 호출 지점까지 진입해야 합니다.");
            releasePg.countDown();

            assertNull(first.get(10, TimeUnit.SECONDS));
            assertNull(second.get(10, TimeUnit.SECONDS));
        } finally {
            startLatch.countDown();
            releasePg.countDown();
            executor.shutdownNow();
        }

        Payment payment = paymentRepository.findById(approved.getId()).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.reservationGroupId).orElseThrow();
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(fixture.reservationGroupId);
        List<Seat> seats = seatRepository.findAllById(fixture.seatIds);

        // 락을 PG 호출 밖으로 뺀 durable 마커 설계에선 두 요청이 같은 멱등키로 PG 취소를 재호출할 수 있고
        // (Toss 가 멱등키로 dedupe), 두 번째 applyDecision 은 CANCELING 이 아니므로 멱등 no-op 으로 수렴한다.
        verify(tossPaymentClient, times(2))
                .cancel("pay-key-duplicate-cancel", "사용자 요청", "KRW", "cancel:" + approved.getId());
        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CANCELED, group.getStatus());
        assertTrue(reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.CANCELED));
        assertTrue(seats.stream().allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE));
    }

    @Test
    @DisplayName("cancelPayment: 취소 중 같은 회차 만료가 실행되어도 확정된 group은 만료 후보가 아니므로 데드락 없이 빠진다")
    void cancelPaymentDoesNotDeadlockWithConcurrentExpiration() throws Exception {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-cancel-expire");
        int totalAmountWithVat = amountWithVat(fixture.fixture.price);
        CountDownLatch cancelReachedPg = new CountDownLatch(1);
        CountDownLatch releasePg = new CountDownLatch(1);

        when(tossPaymentClient.cancel("pay-key-cancel-expire", "사용자 요청", "KRW", "cancel:" + fixture.paymentId))
                .thenAnswer(invocation -> {
                    cancelReachedPg.countDown();
                    if (!releasePg.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("PG 응답 대기 제어에 실패했습니다.");
                    }
                    return new TossCancelResponse(
                            "pay-key-cancel-expire",
                            "CANCELED",
                            totalAmountWithVat,
                            "KRW"
                    );
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancelFuture = executor.submit(() -> {
                paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId);
                return null;
            });
            assertTrue(cancelReachedPg.await(10, TimeUnit.SECONDS), "취소 요청이 PG 호출 지점까지 진입해야 합니다.");

            Future<Integer> expirationFuture = executor.submit(
                    () -> reservationExpirationService.expireByScheduleId(fixture.fixture.scheduleId)
            );

            assertEquals(0, expirationFuture.get(10, TimeUnit.SECONDS));
            releasePg.countDown();
            assertNull(cancelFuture.get(10, TimeUnit.SECONDS));
        } finally {
            releasePg.countDown();
            executor.shutdownNow();
        }

        Payment payment = paymentRepository.findById(fixture.paymentId).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.fixture.reservationGroupId).orElseThrow();
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(fixture.fixture.reservationGroupId);
        List<Seat> seats = seatRepository.findAllById(fixture.fixture.seatIds);

        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CANCELED, group.getStatus());
        assertTrue(reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.CANCELED));
        assertTrue(seats.stream().allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE));
    }

    @Test
    @DisplayName("cancelPayment: READY 결제는 취소할 수 없고 PG를 호출하지 않는다")
    void cancelPaymentRejectsReadyPayment() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());

        assertThrows(IllegalStateException.class,
                () -> paymentService.cancelPayment(ready.getId(), "사용자 요청", fixture.userId));

        assertEquals(PaymentStatus.READY, paymentRepository.findById(ready.getId()).orElseThrow().getStatus());
        verifyNoInteractions(tossPaymentClient);
    }

    @Test
    @DisplayName("cancelPayment: paymentKey가 없는 APPROVED 결제는 취소할 수 없고 PG를 호출하지 않는다")
    void cancelPaymentRejectsApprovedPaymentWithoutPaymentKey() {
        Fixture fixture = createPendingReservationFixture(false);
        Payment approvedWithoutPaymentKey = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(approvedWithoutPaymentKey.getId());
        ReflectionTestUtils.setField(approvedWithoutPaymentKey, "status", PaymentStatus.APPROVED);
        paymentRepository.save(approvedWithoutPaymentKey);

        assertThrows(IllegalStateException.class,
                () -> paymentService.cancelPayment(approvedWithoutPaymentKey.getId(), "사용자 요청", fixture.userId));

        assertEquals(PaymentStatus.APPROVED,
                paymentRepository.findById(approvedWithoutPaymentKey.getId()).orElseThrow().getStatus());
        verifyNoInteractions(tossPaymentClient);
    }

    @Test
    @DisplayName("cancelPayment: PG 취소 응답 결제키가 다르면(HOLD_MANUAL) 예외 없이 CANCELING durable 로 남는다")
    void cancelPaymentPgPaymentKeyMismatchHoldsCanceling() {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-cancel-key-mismatch");

        when(tossPaymentClient.cancel("pay-key-cancel-key-mismatch", "사용자 요청", "KRW", "cancel:" + fixture.paymentId))
                .thenReturn(new TossCancelResponse("different-pay-key", "CANCELED", 110000, "KRW"));

        paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId);

        assertPaymentHeldCanceling(fixture);
    }

    @Test
    @DisplayName("cancelPayment: PG 취소 응답 통화가 다르면(HOLD_MANUAL) 예외 없이 CANCELING durable 로 남는다")
    void cancelPaymentPgCurrencyMismatchHoldsCanceling() {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-cancel-currency-mismatch");

        when(tossPaymentClient.cancel("pay-key-cancel-currency-mismatch", "사용자 요청", "KRW", "cancel:" + fixture.paymentId))
                .thenReturn(new TossCancelResponse("pay-key-cancel-currency-mismatch", "CANCELED", 110000, "USD"));

        paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId);

        assertPaymentHeldCanceling(fixture);
    }

    @Test
    @DisplayName("cancelPayment: PG 가 아직 승인(DONE) 상태면(CANCEL_AGAIN) 예외 없이 CANCELING durable 로 남는다")
    void cancelPaymentPgStillDoneHoldsCanceling() {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-cancel-status-mismatch");

        when(tossPaymentClient.cancel("pay-key-cancel-status-mismatch", "사용자 요청", "KRW", "cancel:" + fixture.paymentId))
                .thenReturn(new TossCancelResponse("pay-key-cancel-status-mismatch", "DONE", 110000, "KRW"));

        paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId);

        assertPaymentHeldCanceling(fixture);
    }

    @Test
    @DisplayName("cancelPayment: 소유자가 아니면 예외가 발생하고 PG를 호출하지 않으며 APPROVED가 유지된다")
    void cancelPaymentRejectsNonOwner() {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-cancel-non-owner");

        assertThrows(ForbiddenAccessException.class,
                () -> paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId + 999));

        assertPaymentCancellationRejected(fixture);
        verify(tossPaymentClient, never()).cancel(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("cancelPayment: PG 취소·조회가 모두 timeout 이면 예외 없이 CANCELING durable 로 남는다(Phase 3 스케줄러가 수렴)")
    void cancelPaymentTimeoutLeavesCancelingDurable() {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-cancel-timeout");

        when(tossPaymentClient.cancel("pay-key-cancel-timeout", "사용자 요청", "KRW", "cancel:" + fixture.paymentId))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-cancel-timeout"))
                .thenThrow(new ResourceAccessException("timeout"));

        paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId);

        assertPaymentHeldCanceling(fixture);
    }

    @Test
    @DisplayName("만료 스케줄러: CANCELING(group CONFIRMED) 결제는 만료 후보가 아니므로 건드리지 않는다")
    void expirationDoesNotTouchCancelingPayment() {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-cancel-expire-skip");

        when(tossPaymentClient.cancel("pay-key-cancel-expire-skip", "사용자 요청", "KRW", "cancel:" + fixture.paymentId))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-cancel-expire-skip"))
                .thenThrow(new ResourceAccessException("timeout"));

        // CANCELING durable 로 만든다(group 은 CONFIRMED 유지).
        paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId);
        assertEquals(PaymentStatus.CANCELING, paymentRepository.findById(fixture.paymentId).orElseThrow().getStatus());

        int expiredCount = reservationExpirationService.expireByScheduleId(fixture.fixture.scheduleId);

        assertEquals(0, expiredCount);
        assertPaymentHeldCanceling(fixture);
    }

    @Test
    @DisplayName("recoverCanceling 자가치유: cancel timeout 으로 CANCELING durable 잔존 → 보정 조회 CANCELED 면 CANCELED 로 수렴한다")
    void recoverCancelingSelfHealsToCanceled() {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-recover-canceled");

        // 동기 취소: PG 취소 timeout + 1차 조회도 timeout → CANCELING durable 로 남긴다.
        when(tossPaymentClient.cancel("pay-key-recover-canceled", "사용자 요청", "KRW", "cancel:" + fixture.paymentId))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-recover-canceled"))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-recover-canceled", "order-recover", "CANCELED", "CARD", 110000, "KRW"));

        paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId);
        assertEquals(PaymentStatus.CANCELING, paymentRepository.findById(fixture.paymentId).orElseThrow().getStatus());

        backdateCancelingAt(fixture.paymentId);

        int recovered = paymentRecoveryService.reconcileStaleCancelingPayments(Duration.ZERO, Integer.MAX_VALUE);

        Payment payment = paymentRepository.findById(fixture.paymentId).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.fixture.reservationGroupId).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.fixture.seatId).orElseThrow();

        assertEquals(1, recovered);
        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CANCELED, group.getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, seat.getStatus());
        // 보정은 재취소 없이 조회만으로 확정한다(이미 CANCELED).
        verify(tossPaymentClient, never())
                .cancel("pay-key-recover-canceled", "CANCEL_RECOVERY", "KRW", "cancel:" + fixture.paymentId);
    }

    @Test
    @DisplayName("recoverCanceling 자가치유: stuck CANCELING + PG 가 아직 DONE → 같은 멱등키로 재취소해 CANCELED 로 수렴한다")
    void recoverCancelingReCancelsWhenPgStillDone() {
        ApprovedPaymentFixture fixture = createApprovedPaymentFixture("pay-key-recover-recancel");

        // 동기 취소: PG 취소 timeout + 1차 조회도 timeout → CANCELING durable. 이후 조회는 아직 DONE(취소가 안 먹은 상태).
        when(tossPaymentClient.cancel("pay-key-recover-recancel", "사용자 요청", "KRW", "cancel:" + fixture.paymentId))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-recover-recancel"))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(new TossPaymentLookupResponse(
                        "pay-key-recover-recancel", "order-recover", "DONE", "CARD", 110000, "KRW"));

        paymentService.cancelPayment(fixture.paymentId, "사용자 요청", fixture.fixture.userId);
        assertEquals(PaymentStatus.CANCELING, paymentRepository.findById(fixture.paymentId).orElseThrow().getStatus());

        backdateCancelingAt(fixture.paymentId);

        // 보정 재취소: 같은 멱등키(cancel:{id}) + 보정 사유(CANCEL_RECOVERY) → CANCELED 응답 → FINALIZE.
        when(tossPaymentClient.cancel("pay-key-recover-recancel", "CANCEL_RECOVERY", "KRW", "cancel:" + fixture.paymentId))
                .thenReturn(new TossCancelResponse("pay-key-recover-recancel", "CANCELED", 110000, "KRW"));

        int recovered = paymentRecoveryService.reconcileStaleCancelingPayments(Duration.ZERO, Integer.MAX_VALUE);

        Payment payment = paymentRepository.findById(fixture.paymentId).orElseThrow();
        ReservationGroup group = reservationGroupRepository.findById(fixture.fixture.reservationGroupId).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.fixture.seatId).orElseThrow();

        assertEquals(1, recovered);
        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CANCELED, group.getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, seat.getStatus());
        verify(tossPaymentClient)
                .cancel("pay-key-recover-recancel", "CANCEL_RECOVERY", "KRW", "cancel:" + fixture.paymentId);
    }

    private void backdateCancelingAt(Long paymentId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            ReflectionTestUtils.setField(payment, "cancelingAt", Instant.now().minus(Duration.ofMinutes(10)));
        });
    }

    private Fixture createPendingReservationFixture(boolean expiredReservation) {
        return createPendingReservationFixture(expiredReservation, 1);
    }

    @Test
    @DisplayName("group 결제 승인/취소 시 묶음 안의 모든 예약과 좌석 상태가 함께 전이된다")
    void confirmAndCancelPaymentForReservationGroup() {
        Fixture fixture = createPendingReservationFixture(false, 2);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        "pay-key-group",
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));

        Payment approved = paymentService.confirmPayment("pay-key-group", ready.getOrderId(), totalAmountWithVat);

        List<Reservation> confirmedReservations = reservationRepository.findByReservationGroupId(fixture.reservationGroupId);
        List<Seat> bookedSeats = seatRepository.findAllById(fixture.seatIds);

        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
        assertEquals(fixture.price, approved.getAmount());
        assertEquals(2, confirmedReservations.size());
        assertTrue(confirmedReservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.CONFIRMED));
        assertTrue(bookedSeats.stream().allMatch(seat -> seat.getStatus() == SeatStatus.BOOKED));

        when(tossPaymentClient.cancel("pay-key-group", "사용자 요청", "KRW", "cancel:" + approved.getId()))
                .thenReturn(new com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse(
                        "pay-key-group",
                        "CANCELED",
                        totalAmountWithVat,
                        "KRW"
                ));

        paymentService.cancelPayment(approved.getId(), "사용자 요청", fixture.userId);

        Payment canceled = paymentRepository.findById(approved.getId()).orElseThrow();
        List<Reservation> canceledReservations = reservationRepository.findByReservationGroupId(fixture.reservationGroupId);
        List<Seat> releasedSeats = seatRepository.findAllById(fixture.seatIds);

        assertEquals(PaymentStatus.CANCELED, canceled.getStatus());
        assertTrue(canceledReservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.CANCELED));
        assertTrue(releasedSeats.stream().allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE));
    }

    private Fixture createPendingReservationFixture(boolean expiredReservation, int seatCount) {
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

        LocalDateTime expiresAt = expiredReservation ? now.minusMinutes(1) : now.plusMinutes(5);
        ReservationGroup reservationGroup = reservationGroupRepository.save(new ReservationGroup(user, now, expiresAt));
        reservationGroupIds.add(reservationGroup.getId());

        java.util.List<Long> fixturesavedReservationIds = new java.util.ArrayList<>();
        java.util.List<Long> fixtureSeatIds = new java.util.ArrayList<>();
        int totalPrice = 0;
        Long firstReservationId = null;
        Long firstSeatId = null;

        for (int i = 1; i <= seatCount; i++) {
            Seat seat = seatRepository.save(new Seat(
                    schedule,
                    "A-" + i + "-" + runId,
                    "VIP",
                    100000,
                    now
            ));
            seat.hold();
            seat = seatRepository.save(seat);
            seatIds.add(seat.getId());
            fixtureSeatIds.add(seat.getId());

            Reservation reservation = reservationRepository.save(new Reservation(user, seat, reservationGroup, now, expiresAt));
            savedReservationIds.add(reservation.getId());
            fixturesavedReservationIds.add(reservation.getId());
            totalPrice += seat.getPrice();
            if (firstReservationId == null) {
                firstReservationId = reservation.getId();
                firstSeatId = seat.getId();
            }
        }

        return new Fixture(reservationGroup.getId(), schedule.getId(), firstReservationId, firstSeatId, totalPrice, fixturesavedReservationIds, fixtureSeatIds, user.getId());
    }

    private int amountWithVat(int amount) {
        return PaymentAmount.fromSeatTotalAmount(amount).totalAmount();
    }

    private ApprovedPaymentFixture createApprovedPaymentFixture(String paymentKey) {
        Fixture fixture = createPendingReservationFixture(false);
        Payment ready = paymentService.readyPayment(fixture.reservationGroupId);
        paymentIds.add(ready.getId());
        int totalAmountWithVat = amountWithVat(ready.getAmount());

        when(tossPaymentClient.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossConfirmResponse(
                        paymentKey,
                        ready.getOrderId(),
                        "DONE",
                        "CARD",
                        totalAmountWithVat,
                        "KRW"
                ));
        paymentService.confirmPayment(paymentKey, ready.getOrderId(), totalAmountWithVat);
        return new ApprovedPaymentFixture(ready.getId(), fixture);
    }

    private void assertPaymentApprovalRejected(Payment ready, Fixture fixture) {
        Payment payment = paymentRepository.findById(ready.getId()).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.CONFIRMING, payment.getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, seat.getStatus());
    }

    private void assertPaymentCancellationRejected(ApprovedPaymentFixture fixture) {
        Payment payment = paymentRepository.findById(fixture.paymentId).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, seat.getStatus());
    }

    // 마킹(APPROVED->CANCELING) 이후 PG 취소가 확정되지 않은 경우: CANCELING durable 로 남고
    // 예매/좌석은 아직 확정 상태를 유지한다(APPROVED 복귀 없음, Phase 3 스케줄러가 이후 수렴).
    private void assertPaymentHeldCanceling(ApprovedPaymentFixture fixture) {
        Payment payment = paymentRepository.findById(fixture.paymentId).orElseThrow();
        Reservation reservation = reservationRepository.findById(fixture.fixture.firstReservationId).orElseThrow();
        Seat seat = seatRepository.findById(fixture.fixture.seatId).orElseThrow();

        assertEquals(PaymentStatus.CANCELING, payment.getStatus());
        assertEquals(ReservationGroupStatus.CONFIRMED,
                reservationGroupRepository.findById(fixture.fixture.reservationGroupId).orElseThrow().getStatus());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, seat.getStatus());
    }

    private record ApprovedPaymentFixture(Long paymentId, Fixture fixture) {
    }

    private record Fixture(
            Long reservationGroupId,
            Long scheduleId,
            Long firstReservationId,
            Long seatId,
            Integer price,
            List<Long> savedReservationIds,
            List<Long> seatIds,
            Long userId
    ) {
    }
}
