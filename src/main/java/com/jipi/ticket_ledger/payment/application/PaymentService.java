package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.payment.application.cancel.PaymentCancelService;
import com.jipi.ticket_ledger.payment.application.confirm.PaymentConfirmService;
import com.jipi.ticket_ledger.payment.application.model.PaymentStatusResult;
import com.jipi.ticket_ledger.payment.application.model.ReadyPaymentResult;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentPreparationService paymentPreparationService;
    private final PaymentQueryService paymentQueryService;
    private final PaymentFailureService paymentFailureService;
    private final PaymentViewQueryService paymentViewQueryService;
    private final PaymentConfirmService paymentConfirmService;
    private final PaymentCancelService paymentCancelService;

    public Payment readyPayment(Long reservationGroupId) {
        return paymentPreparationService.readyPayment(reservationGroupId);
    }

    public ReadyPaymentResult readyPaymentResult(Long reservationGroupId, Long requesterUserId) {
        Payment payment = paymentPreparationService.readyPayment(reservationGroupId, requesterUserId);
        return paymentViewQueryService.getReadyPayment(payment.getId());
    }

    public List<Reservation> getReservationsForPayment(Payment payment) {
        return paymentQueryService.getReservations(payment);
    }

    public Payment getPaymentStatus(Long paymentId) {
        return paymentQueryService.getPayment(paymentId);
    }

    public PaymentStatusResult getPaymentStatusResult(Long paymentId, Long requesterUserId) {
        return paymentViewQueryService.getPaymentStatus(paymentId, requesterUserId);
    }

    // 내부 결제 전 검증로직
    public Payment confirmPayment(String paymentKey, String orderId, Integer amount) {
        return paymentConfirmService.confirm(paymentKey, orderId, amount);
    }

    public PaymentStatusResult confirmPaymentResult(String paymentKey, String orderId, Integer amount) {
        Payment payment = paymentConfirmService.confirm(paymentKey, orderId, amount);
        return paymentViewQueryService.getPaymentStatus(payment.getId());
    }

    public PaymentStatusResult paymentStatusResult(Long paymentId) {
        return paymentViewQueryService.getPaymentStatus(paymentId);
    }

    public void failPayment(Long paymentId) {
        paymentFailureService.failPayment(paymentId);
    }

    // 결제취소 — CANCELING durable 회색지대를 경유하는 취소 오케스트레이션에 위임한다.
    // 반환: 확정 시 CANCELED, PG 미확정 시 CANCELING(호출자가 "취소 처리 중"을 구분할 수 있게).
    public PaymentStatus cancelPayment(Long paymentId, String cancelReason, Long requesterUserId) {
        return paymentCancelService.cancel(paymentId, cancelReason, requesterUserId);
    }

}
