package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.global.exception.ForbiddenAccessException;
import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.application.cancel.PaymentCancelService;
import com.jipi.ticket_ledger.payment.application.cancel.PaymentCancelTransactionService;
import com.jipi.ticket_ledger.payment.application.confirm.PaymentConfirmService;
import com.jipi.ticket_ledger.payment.application.confirm.PaymentConfirmTransactionService;
import com.jipi.ticket_ledger.payment.application.confirm.PaymentConfirmValidator;
import com.jipi.ticket_ledger.payment.application.observability.PaymentRecoveryMetrics;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossConfirmResponse;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGateway;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayCircuitState;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayTemporarilyUnavailableException;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentGatewayCircuitState paymentGatewayCircuitState;

    @Mock
    private PaymentGatewayCircuitState.ConfirmCallPermit confirmCallPermit;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationGroupRepository reservationGroupRepository;

    private PaymentService paymentService;

    private static final Long OWNER_ID = 100L;

    @BeforeEach
    void setUpPaymentService() {
        Clock clock = Clock.systemDefaultZone();
        PaymentConfirmTransactionService transactionService =
                new PaymentConfirmTransactionService(
                        paymentRepository, reservationRepository, new PaymentConfirmValidator(),
                        clock);
        lenient().when(paymentGatewayCircuitState.acquireConfirmPermit()).thenReturn(confirmCallPermit);
        lenient().when(confirmCallPermit.execute(any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(0);
            return action.get();
        });
        PaymentConfirmService confirmService =
                new PaymentConfirmService(paymentGateway, paymentGatewayCircuitState, transactionService);
        PaymentCancelTransactionService cancelTransactionService =
                new PaymentCancelTransactionService(paymentRepository, reservationRepository, clock);
        PaymentRecoveryMetrics recoveryMetrics =
                new PaymentRecoveryMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        PaymentCancelService cancelService =
                new PaymentCancelService(paymentGateway, cancelTransactionService, recoveryMetrics);
        PaymentQueryService queryService = new PaymentQueryService(paymentRepository, reservationRepository);
        PaymentPreparationService preparationService = new PaymentPreparationService(
                paymentRepository,
                reservationGroupRepository,
                queryService,
                reservationGroupId -> "reservation-group-" + reservationGroupId + "-test",
                clock
        );
        PaymentFailureService failureService = new PaymentFailureService(queryService, clock);
        PaymentViewQueryService viewQueryService = new PaymentViewQueryService(queryService);
        paymentService = new PaymentService(
                preparationService,
                queryService,
                failureService,
                viewQueryService,
                confirmService,
                cancelService
        );
    }

    @Test
    @DisplayName("readyPayment: 기존 READY 결제가 있으면 재사용한다")
    void readyPaymentReuseExisting() {
        ReservationGroup reservationGroup = createReservationGroup(LocalDateTime.now().plusMinutes(10));
        Reservation reservation = createPendingReservationWithHeldSeat(reservationGroup, LocalDateTime.now().plusMinutes(10));
        Payment existingPayment = new Payment(reservationGroup, 10000, LocalDateTime.now(), "order-ready-1", "KRW");

        when(reservationGroupRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(reservationGroup));
        when(reservationRepository.findByReservationGroupId(1L)).thenReturn(List.of(reservation));
        when(paymentRepository.findByReservationGroupIdForUpdate(1L)).thenReturn(Optional.of(existingPayment));

        Payment readyPayment = paymentService.readyPayment(1L);

        assertSame(existingPayment, readyPayment);
        InOrder lockOrder = inOrder(paymentRepository, reservationGroupRepository);
        lockOrder.verify(paymentRepository).findByReservationGroupIdForUpdate(1L);
        lockOrder.verify(reservationGroupRepository).findByIdForUpdate(1L);
        verify(paymentRepository, never()).save(org.mockito.ArgumentMatchers.any(Payment.class));
    }

    @Test
    @DisplayName("readyPayment: 저장 중 유니크 충돌이 나면 기존 결제를 다시 조회해 반환한다")
    void readyPaymentReuseExistingAfterConstraintViolation() {
        ReservationGroup reservationGroup = createReservationGroup(LocalDateTime.now().plusMinutes(10));
        Reservation reservation = createPendingReservationWithHeldSeat(reservationGroup, LocalDateTime.now().plusMinutes(10));
        Payment existingPayment = new Payment(reservationGroup, 10000, LocalDateTime.now(), "order-ready-2", "KRW");

        when(reservationGroupRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(reservationGroup));
        when(reservationRepository.findByReservationGroupId(1L)).thenReturn(List.of(reservation));
        when(paymentRepository.findByReservationGroupIdForUpdate(1L)).thenReturn(Optional.empty());
        when(paymentRepository.findByReservationGroupId(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingPayment));
        when(paymentRepository.save(org.mockito.ArgumentMatchers.any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        Payment readyPayment = paymentService.readyPayment(1L);

        assertSame(existingPayment, readyPayment);
    }

    @Test
    @DisplayName("confirmPayment: READY/PENDING/HELD 상태에서 승인 성공 시 상태가 확정된다")
    void confirmPaymentSuccess(CapturedOutput output) {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-confirm-1", "KRW");

        when(paymentRepository.findByOrderIdForUpdate("order-confirm-1")).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);
        when(paymentGateway.confirm("pay-key-1", "order-confirm-1", 11000, "confirm:order-confirm-1"))
                .thenReturn(new TossConfirmResponse("pay-key-1", "order-confirm-1", "DONE", "CARD", 11000, "KRW"));

        Payment confirmed = paymentService.confirmPayment("pay-key-1", "order-confirm-1", 11000);

        assertEquals(PaymentStatus.APPROVED, confirmed.getStatus());
        assertEquals(ReservationGroupStatus.CONFIRMED, reservation.getReservationGroup().getStatus());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, reservation.getSeat().getStatus());
        assertEquals("pay-key-1", confirmed.getPaymentKey());
        assertEquals("CARD", confirmed.getMethod());
        assertEquals("DONE", confirmed.getPgStatus());
        assertTrue(output.getOut().contains("event=" + LogEvents.PAYMENT_CONFIRM_START));
        assertTrue(output.getOut().contains("event=" + LogEvents.PAYMENT_CONFIRM_SUCCESS));
    }

    @Test
    @DisplayName("Payment: READY 결제는 CONFIRMING을 거친 뒤 APPROVED로 전이된다")
    void paymentApproveRequiresConfirmingState() {
        ReservationGroup reservationGroup = createReservationGroup(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservationGroup, 10000, LocalDateTime.now(), "order-confirming-guard", "KRW");

        assertThrows(IllegalStateException.class,
                () -> payment.approve("pay-key-direct", "CARD", "DONE"));

        payment.confirming();
        assertEquals(PaymentStatus.CONFIRMING, payment.getStatus());
        assertNotNull(payment.getConfirmingAt());

        payment.approve("pay-key-confirming", "CARD", "DONE");

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals("pay-key-confirming", payment.getPaymentKey());
    }

    @Test
    @DisplayName("confirmPayment: amount가 다르면 예외가 발생하고 READY 상태가 유지된다")
    void confirmPaymentAmountMismatch() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-confirm-2", "KRW");

        when(paymentRepository.findByOrderIdForUpdate("order-confirm-2")).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-2", "order-confirm-2", 10000));

        assertEquals(PaymentStatus.READY, payment.getStatus());
        assertEquals(ReservationGroupStatus.PENDING, reservation.getReservationGroup().getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("confirmPayment: 이미 APPROVED면 기존 결제를 그대로 반환하고 PG를 다시 호출하지 않는다")
    void confirmPaymentAlreadyApproved() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-confirm-3", "KRW");
        payment.confirming();
        payment.approve("pay-key-3", "CARD", "DONE");
        reservation.getReservationGroup().confirm();
        reservation.confirm();
        reservation.getSeat().book();

        when(paymentRepository.findByOrderIdForUpdate("order-confirm-3")).thenReturn(Optional.of(payment));
        when(paymentRepository.findByOrderId("order-confirm-3")).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);

        Payment confirmed = paymentService.confirmPayment("pay-key-3", "order-confirm-3", 11000);

        assertSame(payment, confirmed);
        verify(paymentGateway, never()).confirm(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("confirmPayment: FAILED 결제는 승인할 수 없다")
    void confirmPaymentRejectsFailedPayment() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-confirm-failed", "KRW");
        payment.fail();

        when(paymentRepository.findByOrderIdForUpdate("order-confirm-failed")).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-failed", "order-confirm-failed", 11000));

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        verify(paymentGateway, never()).confirm(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("confirmPayment: PENDING이 아닌 예약이 있으면 승인할 수 없다")
    void confirmPaymentRejectsNonPendingReservation() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-confirm-invalid-reservation", "KRW");
        reservation.cancel();

        when(paymentRepository.findByOrderIdForUpdate("order-confirm-invalid-reservation")).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment("pay-key-invalid-reservation", "order-confirm-invalid-reservation", 11000));

        assertEquals(PaymentStatus.READY, payment.getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        verify(paymentGateway, never()).confirm(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("confirmPayment: PG confirm 응답을 못 받아도 조회 결과가 DONE이면 승인 상태를 확정한다")
    void confirmPaymentReconcileAfterPgTimeout() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-confirm-4", "KRW");

        when(paymentRepository.findByOrderIdForUpdate("order-confirm-4")).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);
        when(paymentGateway.confirm("pay-key-4", "order-confirm-4", 11000, "confirm:order-confirm-4"))
                .thenThrow(new PaymentGatewayException("timeout"));
        when(paymentGateway.getPaymentByPaymentKey("pay-key-4"))
                .thenReturn(new com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse(
                        "pay-key-4",
                        "order-confirm-4",
                        "DONE",
                        "CARD",
                        11000,
                        "KRW"
                ));

        Payment confirmed = paymentService.confirmPayment("pay-key-4", "order-confirm-4", 11000);

        assertEquals(PaymentStatus.APPROVED, confirmed.getStatus());
        assertEquals(ReservationGroupStatus.CONFIRMED, reservation.getReservationGroup().getStatus());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, reservation.getSeat().getStatus());
        assertEquals("pay-key-4", confirmed.getPaymentKey());
    }

    @Test
    @DisplayName("confirmPayment: confirm breaker가 OPEN이면 DB 상태를 바꾸기 전에 거절한다")
    void confirmPaymentRejectsReadyAdmissionWhenCircuitAlreadyOpen() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-open-admission", "KRW");

        when(paymentGatewayCircuitState.acquireConfirmPermit())
                .thenThrow(new PaymentGatewayTemporarilyUnavailableException(
                        "외부 결제 서비스를 일시적으로 사용할 수 없습니다.", 30, null));

        assertThrows(PaymentGatewayTemporarilyUnavailableException.class,
                () -> paymentService.confirmPayment("pay-key-open", "order-open-admission", 11000));

        assertEquals(PaymentStatus.READY, payment.getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, reservation.getSeat().getStatus());
        verify(paymentRepository, never()).findByOrderIdForUpdate(anyString());
        verify(paymentGateway, never()).confirm(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("confirmPayment: permit 획득 후 PG 결과가 불명이면 CONFIRMING 복구 상태를 보존한다")
    void confirmPaymentPreservesConfirmingWhenPgResultIsUnresolved() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-open-race", "KRW");

        when(paymentRepository.findByOrderIdForUpdate("order-open-race")).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);
        when(paymentGateway.confirm("pay-key-race", "order-open-race", 11000, "confirm:order-open-race"))
                .thenThrow(new PaymentGatewayException("PG result unresolved"));
        when(paymentGateway.getPaymentByPaymentKey("pay-key-race"))
                .thenThrow(new PaymentGatewayException("lookup unresolved"));

        assertThrows(PaymentGatewayException.class,
                () -> paymentService.confirmPayment("pay-key-race", "order-open-race", 11000));

        assertEquals(PaymentStatus.CONFIRMING, payment.getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("failPayment: 만료 전 실패면 결제만 FAILED로 변경되고 예약/좌석은 유지된다")
    void failPaymentSuccessNotExpired() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-4");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);

        paymentService.failPayment(1L);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationGroupStatus.PENDING, reservation.getReservationGroup().getStatus());
        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("failPayment: 만료 후 실패면 예약 만료 및 좌석 복구가 수행된다")
    void failPaymentWhenExpired() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().minusMinutes(1));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-5");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);

        paymentService.failPayment(1L);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationGroupStatus.EXPIRED, reservation.getReservationGroup().getStatus());
        assertEquals(ReservationStatus.EXPIRED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("failPayment: READY 상태가 아니면 예외가 발생한다")
    void failPaymentWhenNotReady() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-6");
        payment.confirming();
        payment.approve("pay-key-2", "CARD", "DONE");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);

        assertThrows(IllegalStateException.class, () -> paymentService.failPayment(1L));
    }

    @Test
    @DisplayName("cancelPayment: APPROVED 결제를 취소하면 예약/좌석이 함께 복구된다")
    void cancelPaymentSuccess(CapturedOutput output) {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-7");
        ReflectionTestUtils.setField(payment, "id", 1L);

        payment.confirming();
        payment.approve("pay-key-3", "CARD", "DONE");
        reservation.getReservationGroup().confirm();
        reservation.confirm();
        reservation.getSeat().book();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);
        doReturn(new TossCancelResponse("pay-key-3", "CANCELED", 10000, "KRW"))
                .when(paymentGateway)
                .cancel("pay-key-3", "사용자 요청", "KRW", "cancel:1");

        paymentService.cancelPayment(1L, "사용자 요청", OWNER_ID);

        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CANCELED, reservation.getReservationGroup().getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
        assertTrue(output.getOut().contains("event=" + LogEvents.PAYMENT_CANCEL_START));
        assertTrue(output.getOut().contains("event=" + LogEvents.PAYMENT_CANCEL_SUCCESS));
    }

    @Test
    @DisplayName("cancelPayment: 소유자가 아니면 예외가 발생하고 PG를 호출하지 않는다")
    void cancelPaymentRejectsNonOwner() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-owner");
        ReflectionTestUtils.setField(payment, "id", 1L);
        payment.confirming();
        payment.approve("pay-key-owner", "CARD", "DONE");
        reservation.getReservationGroup().confirm();
        reservation.confirm();
        reservation.getSeat().book();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));

        assertThrows(ForbiddenAccessException.class,
                () -> paymentService.cancelPayment(1L, "사용자 요청", 999L));

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        verify(paymentGateway, never()).cancel(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("cancelPayment: APPROVED 상태가 아니면 예외가 발생한다")
    void cancelPaymentWhenNotApproved() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-8");
        ReflectionTestUtils.setField(payment, "id", 1L);

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () -> paymentService.cancelPayment(1L, "사용자 요청", OWNER_ID));
    }

    @Test
    @DisplayName("cancelPayment: 이미 CANCELED면 기존 상태를 유지하고 PG를 다시 호출하지 않는다")
    void cancelPaymentAlreadyCanceled() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-9");
        ReflectionTestUtils.setField(payment, "id", 1L);
        payment.confirming();
        payment.approve("pay-key-9", "CARD", "DONE");
        reservation.getReservationGroup().confirm();
        reservation.confirm();
        reservation.getSeat().book();
        payment.startCanceling(java.time.Instant.now());
        payment.cancel(LocalDateTime.now());
        reservation.cancel();
        reservation.getSeat().releaseBooked();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));

        paymentService.cancelPayment(1L, "사용자 요청", OWNER_ID);

        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        verify(paymentGateway, never()).cancel(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("cancelPayment: PG cancel 응답을 못 받아도 조회 결과가 CANCELED면 취소 상태를 확정한다")
    void cancelPaymentReconcileAfterPgTimeout() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-10");
        ReflectionTestUtils.setField(payment, "id", 1L);
        payment.confirming();
        payment.approve("pay-key-10", "CARD", "DONE");
        reservation.getReservationGroup().confirm();
        reservation.confirm();
        reservation.getSeat().book();

        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        stubReservationsForPayment(payment, reservation);
        when(paymentGateway.cancel("pay-key-10", "사용자 요청", "KRW", "cancel:1"))
                .thenThrow(new PaymentGatewayException("timeout"));
        when(paymentGateway.getPaymentByPaymentKey("pay-key-10"))
                .thenReturn(new com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse(
                        "pay-key-10",
                        "order-10",
                        "CANCELED",
                        "CARD",
                        11000,
                        "KRW"
                ));

        paymentService.cancelPayment(1L, "사용자 요청", OWNER_ID);

        assertEquals(PaymentStatus.CANCELED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CANCELED, reservation.getReservationGroup().getStatus());
        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("결제를 찾을 수 없으면 EntityNotFoundException이 발생한다")
    void paymentNotFound() {
        when(paymentRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> paymentService.failPayment(404L));
    }

    @Test
    @DisplayName("getPaymentStatus: paymentId로 현재 결제 상태를 조회한다")
    void getPaymentStatusSuccess() {
        Reservation reservation = createPendingReservationWithHeldSeat(LocalDateTime.now().plusMinutes(10));
        Payment payment = new Payment(reservation.getReservationGroup(), 10000, LocalDateTime.now(), "order-status-1", "KRW");

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        Payment found = paymentService.getPaymentStatus(1L);

        assertSame(payment, found);
    }

    private Reservation createPendingReservationWithHeldSeat(LocalDateTime expiresAt) {
        return createPendingReservationWithHeldSeat(createReservationGroup(expiresAt), expiresAt);
    }

    private Reservation createPendingReservationWithHeldSeat(ReservationGroup reservationGroup, LocalDateTime expiresAt) {
        Seat seat = createSeat();
        seat.hold();

        return new Reservation(createUser(), seat, reservationGroup, LocalDateTime.now(), expiresAt);
    }

    private void stubReservationsForPayment(Payment payment, Reservation... reservations) {
        when(reservationRepository.findByReservationGroupId(payment.getReservationGroup().getId()))
                .thenReturn(List.of(reservations));
    }

    private ReservationGroup createReservationGroup(LocalDateTime expiresAt) {
        User owner = createUser();
        ReflectionTestUtils.setField(owner, "id", OWNER_ID);
        ReservationGroup reservationGroup = new ReservationGroup(owner, LocalDateTime.now(), expiresAt);
        ReflectionTestUtils.setField(reservationGroup, "id", 1L);
        return reservationGroup;
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

