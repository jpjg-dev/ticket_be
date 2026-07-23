package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;

    @Transactional(readOnly = true)
    public Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<Reservation> getReservations(Payment payment) {
        return getReservations(payment.getReservationGroup().getId());
    }

    @Transactional(readOnly = true)
    public List<Reservation> getReservations(Long reservationGroupId) {
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(reservationGroupId);
        if (reservations.isEmpty()) {
            throw new EntityNotFoundException("예매를 찾을 수 없습니다.");
        }
        return reservations;
    }
}
