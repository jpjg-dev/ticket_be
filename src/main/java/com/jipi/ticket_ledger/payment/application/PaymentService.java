package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.application.cancel.PaymentCancelService;
import com.jipi.ticket_ledger.payment.application.confirm.PaymentConfirmService;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationGroupRepository reservationGroupRepository;
    private final PaymentConfirmService paymentConfirmService;
    private final PaymentCancelService paymentCancelService;
    private final Clock clock;

    //결제 대기
    @Transactional
    public Payment readyPayment(Long reservationGroupId) {
        ReservationGroup reservationGroup = reservationGroupRepository.findById(reservationGroupId)
                .orElseThrow(() -> new EntityNotFoundException("예매 묶음을 찾을 수 없습니다."));
        List<Reservation> reservations = getReservationsByGroupId(reservationGroupId);

        reservationGroup.validateReadyPayment(reservations, clock.instant());

        Payment existingPayment = paymentRepository.findByReservationGroupId(reservationGroupId)
                .orElse(null);
        if (existingPayment != null) {
            return existingPayment;
        }

        Integer seatTotalAmount = reservationGroup.seatTotalAmount(reservations);
        try {
            return paymentRepository.save(
                    new Payment(
                            reservationGroup,
                            seatTotalAmount,
                            clock.instant(),
                            createOrderId(reservationGroupId),
                            "KRW"
                    )
            );
        } catch (DataIntegrityViolationException e) {
            return paymentRepository.findByReservationGroupId(reservationGroupId)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public List<Reservation> getReservationsForPayment(Payment payment) {
        return getReservationsByGroupId(payment.getReservationGroup().getId());
    }

    @Transactional(readOnly = true)
    public Payment getPaymentStatus(Long paymentId) {
        return getPayment(paymentId);
    }

    // 내부 결제 전 검증로직
    public Payment confirmPayment(String paymentKey, String orderId, Integer amount) {
        return paymentConfirmService.confirm(paymentKey, orderId, amount);
    }

    // 결제실패
    @Transactional
    public void failPayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        List<Reservation> reservations = getReservationsForPayment(payment);
        Long reservationGroupId = payment.getReservationGroup().getId();
        log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                LogEvents.PAYMENT_FAIL_START, payment.getOrderId(), payment.getId(), reservationGroupId, "REQUEST");

        payment.fail();

        if (!payment.getReservationGroup().isExpiredAt(clock.instant())) {
            log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                    LogEvents.PAYMENT_FAIL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId, "FAIL_ONLY");
            return;
        }

        payment.getReservationGroup().expireReservations(reservations);
        log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                LogEvents.PAYMENT_FAIL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId, "FAIL_AND_EXPIRE");
    }

    // 결제취소 — CANCELING durable 회색지대를 경유하는 취소 오케스트레이션에 위임한다.
    // 반환: 확정 시 CANCELED, PG 미확정 시 CANCELING(호출자가 "취소 처리 중"을 구분할 수 있게).
    public PaymentStatus cancelPayment(Long paymentId, String cancelReason, Long requesterUserId) {
        return paymentCancelService.cancel(paymentId, cancelReason, requesterUserId);
    }

    // helper
    private Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));
    }

    private List<Reservation> getReservationsByGroupId(Long reservationGroupId) {
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(reservationGroupId);
        if (reservations.isEmpty()) {
            throw new EntityNotFoundException("예매를 찾을 수 없습니다.");
        }
        return reservations;
    }

    private String createOrderId(Long reservationGroupId) {
        return "reservation-group-" + reservationGroupId + "-" + java.util.UUID.randomUUID();
    }
}
