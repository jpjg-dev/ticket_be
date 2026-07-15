package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.global.exception.ForbiddenAccessException;
import com.jipi.ticket_ledger.payment.application.model.PaymentStatusResult;
import com.jipi.ticket_ledger.payment.application.model.ReadyPaymentResult;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentAmount;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PaymentViewQueryService {

    private final PaymentQueryService paymentQueryService;

    @Transactional(readOnly = true)
    public ReadyPaymentResult getReadyPayment(Long paymentId) {
        Payment payment = paymentQueryService.getPayment(paymentId);
        List<Reservation> reservations = paymentQueryService.getReservations(payment);
        Reservation first = reservations.getFirst();
        PaymentAmount amount = payment.paymentAmount();
        String orderName = first.getSeat().getSchedule().getEvent().getTitle()
                + " " + first.getSeat().getSeatNumber()
                + (reservations.size() > 1 ? " 외 " + (reservations.size() - 1) + "석" : "");

        return new ReadyPaymentResult(
                payment.getId(), payment.getOrderId(), amount.totalAmount(), amount.seatTotalAmount(),
                amount.vatAmount(), orderName, payment.getCurrency()
        );
    }

    @Transactional(readOnly = true)
    public PaymentStatusResult getPaymentStatus(Long paymentId) {
        Payment payment = paymentQueryService.getPayment(paymentId);
        return toPaymentStatusResult(payment);
    }

    @Transactional(readOnly = true)
    public PaymentStatusResult getPaymentStatus(Long paymentId, Long requesterUserId) {
        Payment payment = paymentQueryService.getPayment(paymentId);
        if (!Objects.equals(payment.getReservationGroup().getUser().getId(), requesterUserId)) {
            throw new ForbiddenAccessException("잘못된 접근 입니다.");
        }
        return toPaymentStatusResult(payment);
    }

    private PaymentStatusResult toPaymentStatusResult(Payment payment) {
        Reservation reservation = paymentQueryService.getReservations(payment).getFirst();
        return new PaymentStatusResult(
                payment.getId(), payment.getOrderId(), payment.getStatus(),
                reservation.getStatus(), reservation.getSeat().getStatus()
        );
    }
}
