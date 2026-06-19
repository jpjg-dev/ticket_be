package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.application.confirm.PaymentConfirmService;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationGroupRepository reservationGroupRepository;
    private final TossPaymentClient tossPaymentClient;
    private final PaymentConfirmService paymentConfirmService;

    //결제 대기
    @Transactional
    public Payment readyPayment(Long reservationGroupId) {
        ReservationGroup reservationGroup = reservationGroupRepository.findById(reservationGroupId)
                .orElseThrow(() -> new EntityNotFoundException("예매 묶음을 찾을 수 없습니다."));
        List<Reservation> reservations = getReservationsByGroupId(reservationGroupId);

        reservationGroup.validateReadyPayment(reservations, Instant.now());

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
                            Instant.now(),
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

        if (!isExpired(payment, reservations, Instant.now())) {
            log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                    LogEvents.PAYMENT_FAIL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId, "FAIL_ONLY");
            return;
        }

        payment.getReservationGroup().expireReservations(reservations);
        log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                LogEvents.PAYMENT_FAIL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId, "FAIL_AND_EXPIRE");
    }

    // 결제취소
    @Transactional
    public void cancelPayment(Long paymentId, String cancelReason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));

        List<Reservation> reservations = getReservationsForPayment(payment);
        Long reservationGroupId = payment.getReservationGroup().getId();
        log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CANCEL_START, payment.getOrderId(), payment.getId(), reservationGroupId, cancelReason, maskPaymentKey(payment.getPaymentKey()));

        if (payment.getStatus() == PaymentStatus.CANCELED) {
            log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} pgStatus={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId, payment.getPgStatus(), payment.getPgStatus(), maskPaymentKey(payment.getPaymentKey()));
            return;
        }

        if (payment.getStatus() != PaymentStatus.APPROVED) {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId, "INVALID_PAYMENT_STATUS", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("승인된 결제만 취소할 수 있습니다.");
        }

        if (payment.getPaymentKey() == null || payment.getPaymentKey().isBlank()) {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId, "MISSING_PAYMENT_KEY", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("PG 결제키가 없어 취소할 수 없습니다.");
        }

        TossCancelResponse tossResponse;
        try {
            tossResponse = tossPaymentClient.cancel(
                    payment.getPaymentKey(),
                    cancelReason,
                    payment.getCurrency(),
                    createCancelIdempotencyKey(payment.getId())
            );
        } catch (RestClientException cancelException) {
            log.error("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId, "PG_CANCEL_EXCEPTION", maskPaymentKey(payment.getPaymentKey()), cancelException);
            TossPaymentLookupResponse lookupResponse = tossPaymentClient.getPaymentByPaymentKey(payment.getPaymentKey());
            if (TossPaymentStatus.isCanceled(lookupResponse.status())
                    && payment.getCurrency().equals(lookupResponse.currency())
                    && payment.getPaymentKey().equals(lookupResponse.paymentKey())) {
                applyCancellation(payment, reservations);
                log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} pgStatus={} paymentKeyMasked={}",
                        LogEvents.PAYMENT_CANCEL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId, lookupResponse.status(), lookupResponse.status(), maskPaymentKey(payment.getPaymentKey()));
                return;
            }
            throw cancelException;
        }

        if (!payment.getPaymentKey().equals(tossResponse.paymentKey())) {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId, "PG_PAYMENT_KEY_MISMATCH", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("PG 취소 응답 결제키가 일치하지 않습니다.");
        }

        if (!payment.getCurrency().equals(tossResponse.currency())) {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId, "PG_CURRENCY_MISMATCH", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("PG 취소 응답 통화가 일치하지 않습니다.");
        }

        // status 값은 PG 응답 포맷에 맞춰 조정 가능
        if (!TossPaymentStatus.isCanceled(tossResponse.status())) {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId, "PG_CANCEL_STATUS_INVALID", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("PG 취소 상태가 유효하지 않습니다.");
        }

        applyCancellation(payment, reservations);
        log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} pgStatus={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CANCEL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId, tossResponse.status(), tossResponse.status(), maskPaymentKey(payment.getPaymentKey()));
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

    private String createCancelIdempotencyKey(Long paymentId) {
        return "cancel:" + paymentId;
    }

    private boolean isExpired(Payment payment, List<Reservation> reservations, Instant now) {
        return payment.getReservationGroup().isExpiredAt(now);
    }

    private void applyCancellation(Payment payment, List<Reservation> reservations) {
        payment.cancel(Instant.now());
        payment.getReservationGroup().cancel();
        reservations.forEach(reservation -> {
            reservation.cancel();
            reservation.getSeat().releaseBooked();
        });
    }

    private String maskPaymentKey(String paymentKey) {
        return PaymentLogFormatter.maskPaymentKey(paymentKey);
    }
}
