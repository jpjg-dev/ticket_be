package com.jipi.ticket_ledger.payment.application;

import com.jipi.ticket_ledger.global.exception.ForbiddenAccessException;
import com.jipi.ticket_ledger.payment.application.port.out.OrderIdGenerator;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PaymentPreparationService {

    private final PaymentRepository paymentRepository;
    private final ReservationGroupRepository reservationGroupRepository;
    private final PaymentQueryService paymentQueryService;
    private final OrderIdGenerator orderIdGenerator;
    private final Clock clock;

    @Transactional
    public Payment readyPayment(Long reservationGroupId) {
        return readyPayment(reservationGroupId, null);
    }

    @Transactional
    public Payment readyPayment(Long reservationGroupId, Long requesterUserId) {
        Payment lockedPayment = paymentRepository.findByReservationGroupIdForUpdate(reservationGroupId).orElse(null);
        ReservationGroup group = reservationGroupRepository.findByIdForUpdate(reservationGroupId)
                .orElseThrow(() -> new EntityNotFoundException("예매 묶음을 찾을 수 없습니다."));
        verifyOwner(group, requesterUserId);

        List<Reservation> reservations = paymentQueryService.getReservations(reservationGroupId);
        group.validateReadyPayment(reservations, clock.instant());

        Payment existingPayment = lockedPayment != null
                ? lockedPayment
                : paymentRepository.findByReservationGroupId(reservationGroupId).orElse(null);
        if (existingPayment != null) {
            return existingPayment;
        }

        try {
            return paymentRepository.save(new Payment(
                    group,
                    group.seatTotalAmount(reservations),
                    clock.instant(),
                    orderIdGenerator.generate(reservationGroupId),
                    "KRW"
            ));
        } catch (DataIntegrityViolationException duplicate) {
            return paymentRepository.findByReservationGroupId(reservationGroupId)
                    .orElseThrow(() -> duplicate);
        }
    }

    private void verifyOwner(ReservationGroup group, Long requesterUserId) {
        if (requesterUserId != null && !Objects.equals(group.getUser().getId(), requesterUserId)) {
            throw new ForbiddenAccessException("잘못된 접근 입니다.");
        }
    }
}
