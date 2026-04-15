package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossConfirmResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final TossPaymentClient tossPaymentClient;

    //결제 대기
    public Payment readyPayment(Long reservationId) {
        Reservation reservation = getReservation(reservationId);

        validateReadyPayment(reservation);

        return paymentRepository.findByReservationId(reservationId)
                .orElseGet(() -> paymentRepository.save(
                        new Payment(
                                reservation,
                                reservation.getSeat().getPrice(),
                                LocalDateTime.now(),
                                createOrderId(reservationId)
                        )
                ));
    }
    // 내부 결제 전 검증로직
    public Payment confirmPayment(String paymentKey, String orderId, Integer amount) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));

        Reservation reservation = payment.getReservation();
        Seat seat = reservation.getSeat();

        if (payment.getStatus() != PaymentStatus.READY) {
            throw new IllegalStateException("결제 대기 상태에서만 승인할 수 있습니다.");
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new IllegalStateException("결제 대기 중인 예약만 승인할 수 있습니다.");
        }

        if (reservation.isExpiredAt(LocalDateTime.now())) {
            payment.fail();
            reservation.expire();
            seat.release();

            throw new IllegalStateException("예약 시간이 만료되어 결제를 승인할 수 없습니다.");
        }

        if (!payment.getAmount().equals(amount)) {
            payment.fail();

            throw new IllegalStateException("결제 금액이 일치하지 않습니다.");
        }

        // TODO: Toss Payments 승인 API 호출
        // 여기까지 통과한 뒤에만 외부 PG 승인 API를 호출해야 한다.
        TossConfirmResponse tossResponse = tossPaymentClient.confirm(
                paymentKey,
                orderId,
                payment.getAmount()
        );

        payment.approve(
                tossResponse.paymentKey(),
                tossResponse.method(),
                tossResponse.status()
        );

        reservation.confirm();
        seat.book();

        return payment;
    }
    // 결제 승인
    public void approvePayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        Reservation reservation = payment.getReservation();
        Seat seat = reservation.getSeat();

        payment.approve(null, null, null);
        reservation.confirm();
        seat.book();
    }

    // 결제실패
    public void failPayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        Reservation reservation = payment.getReservation();
        Seat seat = reservation.getSeat();

        payment.fail();

        if (!reservation.isExpiredAt(LocalDateTime.now())) {
            return;
        }

        reservation.expire();
        seat.release();
    }

    // 결제취소
    public void cancelPayment(Long paymentId) {
        Payment payment = getPayment(paymentId);

        Reservation reservation = payment.getReservation();
        Seat seat = reservation.getSeat();

        payment.cancel(LocalDateTime.now());
        reservation.cancel();
        seat.releaseBooked();
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
}
