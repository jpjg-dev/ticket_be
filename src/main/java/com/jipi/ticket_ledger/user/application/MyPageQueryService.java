package com.jipi.ticket_ledger.user.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.user.application.model.ResponseMyPageDTO;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MyPageQueryService {

    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final MyPageResponseMapper responseMapper;

    @Transactional(readOnly = true)
    public ResponseMyPageDTO getUserInfo(Long userId, Long principalUserId) {
        if (!userRepository.existsById(userId)) {
            log.warn("event={} requestedUserId={} principalUserId={} reason={}",
                    LogEvents.USER_MYPAGE_REJECT, userId, principalUserId, "USER_NOT_FOUND");
            throw new EntityNotFoundException("일치하는 사용자가 없습니다.");
        }
        if (!userId.equals(principalUserId)) {
            log.warn("event={} requestedUserId={} principalUserId={} reason={}",
                    LogEvents.USER_MYPAGE_REJECT, userId, principalUserId, "FORBIDDEN_MYPAGE_ACCESS");
            throw new IllegalStateException("잘못된 접근 입니다.");
        }

        List<Reservation> reservations = reservationRepository.findByReservationGroupUserIdAndReservationGroupStatusIn(
                userId,
                List.of(ReservationGroupStatus.CONFIRMED, ReservationGroupStatus.CANCELED),
                Sort.by(Sort.Direction.DESC, "id")
        );
        List<Payment> payments = paymentRepository.findByReservationGroupUserIdAndStatusIn(
                userId,
                List.of(PaymentStatus.APPROVED, PaymentStatus.CANCELING, PaymentStatus.CANCELED),
                Sort.by(Sort.Direction.DESC, "requestedAt")
        );
        return responseMapper.toResponse(reservations, payments);
    }
}
