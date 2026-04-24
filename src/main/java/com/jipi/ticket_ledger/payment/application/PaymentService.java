package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossConfirmResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final TossPaymentClient tossPaymentClient;

    //결제 대기
    public Payment readyPayment(Long reservationId) {
        Reservation reservation = getReservation(reservationId);

        validateReadyPayment(reservation);

        Payment existingPayment = paymentRepository.findByReservationId(reservationId)
                .orElse(null);
        if (existingPayment != null) {
            return existingPayment;
        }

        try {
            return paymentRepository.save(
                    new Payment(
                            reservation,
                            reservation.getSeat().getPrice(),
                            LocalDateTime.now(),
                            createOrderId(reservationId),
                            "KRW"
                    )
            );
        } catch (DataIntegrityViolationException e) {
            return paymentRepository.findByReservationId(reservationId)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public Payment getPaymentStatus(Long paymentId) {
        return getPayment(paymentId);
    }

    // 내부 결제 전 검증로직
    public Payment confirmPayment(String paymentKey, String orderId, Integer amount) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));

        Reservation reservation = payment.getReservation();
        Seat seat = reservation.getSeat();
        log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CONFIRM_START, orderId, payment.getId(), reservation.getId(), "REQUEST", maskPaymentKey(paymentKey));

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} pgStatus={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_SUCCESS, orderId, payment.getId(), reservation.getId(), payment.getPgStatus(), payment.getPgStatus(), maskPaymentKey(payment.getPaymentKey()));
            return payment;
        }

        if (payment.getStatus() != PaymentStatus.READY) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_REJECT, orderId, payment.getId(), reservation.getId(), "INVALID_PAYMENT_STATUS", maskPaymentKey(paymentKey));
            throw new IllegalStateException("결제 대기 상태에서만 승인할 수 있습니다.");
        }

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} pgStatus={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_SUCCESS, orderId, payment.getId(), reservation.getId(), payment.getPgStatus(), payment.getPgStatus(), maskPaymentKey(payment.getPaymentKey()));
            return payment;
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_REJECT, orderId, payment.getId(), reservation.getId(), "INVALID_RESERVATION_STATUS", maskPaymentKey(paymentKey));
            throw new IllegalStateException("결제 대기 중인 예약만 승인할 수 있습니다.");
        }

        if (reservation.isExpiredAt(LocalDateTime.now())) {
            payment.fail();
            reservation.expire();
            seat.release();
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_REJECT, orderId, payment.getId(), reservation.getId(), "RESERVATION_EXPIRED", maskPaymentKey(paymentKey));

            throw new IllegalStateException("예약 시간이 만료되어 결제를 승인할 수 없습니다.");
        }

        Integer totalAmountWithVat = calculateTotalAmountWithVat(payment.getAmount());

        if (!totalAmountWithVat.equals(amount)) {
            payment.fail();
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_REJECT, orderId, payment.getId(), reservation.getId(), "AMOUNT_MISMATCH", maskPaymentKey(paymentKey));

            throw new IllegalStateException("결제 금액이 일치하지 않습니다.");
        }

        // 여기까지 통과한 뒤에만 외부 PG 승인 API를 호출해야 한다.
        TossConfirmResponse tossResponse;
        try {
            tossResponse = tossPaymentClient.confirm(
                    paymentKey,
                    orderId,
                    totalAmountWithVat,
                    createConfirmIdempotencyKey(orderId)
            );
        } catch (RestClientException confirmException) {
            TossPaymentLookupResponse lookupResponse = confirmPaymentStatus(paymentKey, orderId);
            if (isApprovedStatus(lookupResponse.status())
                    && orderId.equals(lookupResponse.orderId())
                    && totalAmountWithVat.equals(lookupResponse.totalAmount())
                    && payment.getCurrency().equals(lookupResponse.currency())) {
                applyApproval(payment, reservation, seat, lookupResponse.paymentKey(), lookupResponse.method(), lookupResponse.status());
                log.info("event={} orderId={} paymentId={} reservationId={} reason={} pgStatus={} paymentKeyMasked={}",
                        LogEvents.PAYMENT_CONFIRM_SUCCESS, orderId, payment.getId(), reservation.getId(), lookupResponse.status(), lookupResponse.status(), maskPaymentKey(payment.getPaymentKey()));
                return payment;
            }
            throw confirmException;
        }

        if (!totalAmountWithVat.equals(tossResponse.totalAmount())) {
            payment.fail();
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_REJECT, orderId, payment.getId(), reservation.getId(), "PG_AMOUNT_MISMATCH", maskPaymentKey(paymentKey));
            throw new IllegalStateException("PG 승인 금액이 일치하지 않습니다.");
        }

        if (!payment.getCurrency().equals(tossResponse.currency())) {
            payment.fail();
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_REJECT, orderId, payment.getId(), reservation.getId(), "PG_CURRENCY_MISMATCH", maskPaymentKey(paymentKey));
            throw new IllegalStateException("PG 통화 코드가 일치하지 않습니다.");
        }
        //결제 승인
        applyApproval(payment, reservation, seat, tossResponse.paymentKey(), tossResponse.method(), tossResponse.status());
        log.info("event={} orderId={} paymentId={} reservationId={} reason={} pgStatus={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CONFIRM_SUCCESS, orderId, payment.getId(), reservation.getId(), tossResponse.status(), tossResponse.status(), maskPaymentKey(payment.getPaymentKey()));

        return payment;
    }

    // 결제실패
    public void failPayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        Reservation reservation = payment.getReservation();
        Seat seat = reservation.getSeat();
        log.info("event={} orderId={} paymentId={} reservationId={} reason={}",
                LogEvents.PAYMENT_FAIL_START, payment.getOrderId(), payment.getId(), reservation.getId(), "REQUEST");

        payment.fail();

        if (!reservation.isExpiredAt(LocalDateTime.now())) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={}",
                    LogEvents.PAYMENT_FAIL_SUCCESS, payment.getOrderId(), payment.getId(), reservation.getId(), "FAIL_ONLY");
            return;
        }

        reservation.expire();
        seat.release();
        log.info("event={} orderId={} paymentId={} reservationId={} reason={}",
                LogEvents.PAYMENT_FAIL_SUCCESS, payment.getOrderId(), payment.getId(), reservation.getId(), "FAIL_AND_EXPIRE");
    }

    // 결제취소
    public void cancelPayment(Long paymentId, String cancelReason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));

        Reservation reservation = payment.getReservation();
        Seat seat = reservation.getSeat();
        log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CANCEL_START, payment.getOrderId(), payment.getId(), reservation.getId(), cancelReason, maskPaymentKey(payment.getPaymentKey()));

        if (payment.getStatus() == PaymentStatus.CANCELED) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} pgStatus={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_SUCCESS, payment.getOrderId(), payment.getId(), reservation.getId(), payment.getPgStatus(), payment.getPgStatus(), maskPaymentKey(payment.getPaymentKey()));
            return;
        }

        if (payment.getStatus() != PaymentStatus.APPROVED) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservation.getId(), "INVALID_PAYMENT_STATUS", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("승인된 결제만 취소할 수 있습니다.");
        }

        if (payment.getPaymentKey() == null || payment.getPaymentKey().isBlank()) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservation.getId(), "MISSING_PAYMENT_KEY", maskPaymentKey(payment.getPaymentKey()));
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
            TossPaymentLookupResponse lookupResponse = tossPaymentClient.getPaymentByPaymentKey(payment.getPaymentKey());
            if (isCanceledStatus(lookupResponse.status())
                    && payment.getCurrency().equals(lookupResponse.currency())
                    && payment.getPaymentKey().equals(lookupResponse.paymentKey())) {
                applyCancellation(payment, reservation, seat);
                log.info("event={} orderId={} paymentId={} reservationId={} reason={} pgStatus={} paymentKeyMasked={}",
                        LogEvents.PAYMENT_CANCEL_SUCCESS, payment.getOrderId(), payment.getId(), reservation.getId(), lookupResponse.status(), lookupResponse.status(), maskPaymentKey(payment.getPaymentKey()));
                return;
            }
            throw cancelException;
        }

        if (!payment.getPaymentKey().equals(tossResponse.paymentKey())) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservation.getId(), "PG_PAYMENT_KEY_MISMATCH", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("PG 취소 응답 결제키가 일치하지 않습니다.");
        }

        if (!payment.getCurrency().equals(tossResponse.currency())) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservation.getId(), "PG_CURRENCY_MISMATCH", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("PG 취소 응답 통화가 일치하지 않습니다.");
        }

        // status 값은 PG 응답 포맷에 맞춰 조정 가능
        if (!"CANCELED".equalsIgnoreCase(tossResponse.status())
                && !"PARTIAL_CANCELED".equalsIgnoreCase(tossResponse.status())) {
            log.info("event={} orderId={} paymentId={} reservationId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservation.getId(), "PG_CANCEL_STATUS_INVALID", maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("PG 취소 상태가 유효하지 않습니다.");
        }

        applyCancellation(payment, reservation, seat);
        log.info("event={} orderId={} paymentId={} reservationId={} reason={} pgStatus={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CANCEL_SUCCESS, payment.getOrderId(), payment.getId(), reservation.getId(), tossResponse.status(), tossResponse.status(), maskPaymentKey(payment.getPaymentKey()));
    }

    // helper
    private Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));
    }

    private Reservation getReservation(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new EntityNotFoundException("예약을 찾을 수 없습니다."));
    }

    private void validateReadyPayment(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new IllegalStateException("결제 대기 중인 예약만 결제를 시작할 수 있습니다.");
        }

        if (reservation.isExpiredAt(LocalDateTime.now())) {
            reservation.expire();
            reservation.getSeat().release();
            throw new IllegalStateException("예약 시간이 만료되어 결제를 시작할 수 없습니다.");
        }
    }

    private String createOrderId(Long reservationId) {
        return "reservation-" + reservationId + "-" + java.util.UUID.randomUUID();
    }

    private TossPaymentLookupResponse confirmPaymentStatus(String paymentKey, String orderId) {
        if (paymentKey != null && !paymentKey.isBlank()) {
            return tossPaymentClient.getPaymentByPaymentKey(paymentKey);
        }
        return tossPaymentClient.getPaymentByOrderId(orderId);
    }

    private String createConfirmIdempotencyKey(String orderId) {
        return "confirm:" + orderId;
    }

    private String createCancelIdempotencyKey(Long paymentId) {
        return "cancel:" + paymentId;
    }

    private Integer calculateTotalAmountWithVat(Integer baseAmount) {
        int vat = (int) Math.round(baseAmount * 0.1d);
        return baseAmount + vat;
    }

    private boolean isApprovedStatus(String status) {
        return "DONE".equalsIgnoreCase(status);
    }

    private boolean isCanceledStatus(String status) {
        return "CANCELED".equalsIgnoreCase(status) || "PARTIAL_CANCELED".equalsIgnoreCase(status);
    }

    private void applyApproval(Payment payment, Reservation reservation, Seat seat, String paymentKey, String method, String pgStatus) {
        payment.approve(paymentKey, method, pgStatus);
        reservation.confirm();
        seat.book();
    }

    private void applyCancellation(Payment payment, Reservation reservation, Seat seat) {
        payment.cancel(LocalDateTime.now());
        reservation.cancel();
        seat.releaseBooked();
    }

    private String maskPaymentKey(String paymentKey) {
        if (paymentKey == null || paymentKey.isBlank()) {
            return "N/A";
        }
        int visiblePrefix = Math.min(6, paymentKey.length());
        return paymentKey.substring(0, visiblePrefix) + "*".repeat(Math.max(0, paymentKey.length() - visiblePrefix));
    }
}
