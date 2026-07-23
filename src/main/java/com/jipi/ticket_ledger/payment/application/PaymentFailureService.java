package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentFailureService {

    private final PaymentQueryService paymentQueryService;
    private final Clock clock;

    @Transactional
    public void failPayment(Long paymentId) {
        Payment payment = paymentQueryService.getPayment(paymentId);
        List<Reservation> reservations = paymentQueryService.getReservations(payment);
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
}
