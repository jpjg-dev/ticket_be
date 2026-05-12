package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReservationService {
    private static final long RESERVATION_HOLD_MINUTES = 10L;

    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationGroupRepository reservationGroupRepository;

    public Long createReservation(Long userId, List<Long> seatIds) {
        // 수동 요청들어올시 예약만료 검사
        expireReservations();
        validateSeatIds(seatIds);
        // 1) 요청에 포함된 사용자와 좌석을 조회하고, 없으면 즉시 예외를 던진다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        List<Long> sortedSeatIds = seatIds.stream()
                .distinct()
                .sorted()
                .toList();

        List<Seat> seats = seatRepository.findAllByIdInForUpdate(sortedSeatIds);
        if (seats.size() != sortedSeatIds.size()) {
            throw new EntityNotFoundException("좌석을 찾을 수 없습니다.");
        }
        validateSameSchedule(seats);

        // 2) 좌석 상태를 AVAILABLE -> HELD로 변경해 임시 선점한다.
        LocalDateTime now = LocalDateTime.now();
        ReservationGroup reservationGroup = reservationGroupRepository.save(new ReservationGroup(user, now));

        List<Reservation> reservations = seats.stream()
                .map(seat -> {
                    seat.hold();
                    return new Reservation(user, seat, reservationGroup, now);
                })
                .toList();
        reservationRepository.saveAll(reservations);

        // 3) 생성된 예매 묶음 식별자를 반환한다.
        return reservationGroup.getId();
    }

    private void validateSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("최소 1개의 좌석을 선택해야 합니다.");
        }
        if (seatIds.size() > ReservationGroup.MAX_SEAT_COUNT) {
            throw new IllegalArgumentException("좌석은 최대 2개까지 예매할 수 있습니다.");
        }
        if (seatIds.stream().anyMatch(seatId -> seatId == null || seatId <= 0)) {
            throw new IllegalArgumentException("좌석 ID가 올바르지 않습니다.");
        }
        // LinkedHashSet: 중복 제거 + 입력 순서 유지(에러 메시지/검증 흐름에서 사용자 입력 순서를 유지하기 위함)
        Set<Long> uniqueSeatIds = new LinkedHashSet<>(seatIds);
        if (uniqueSeatIds.size() != seatIds.size()) {
            throw new IllegalArgumentException("중복된 좌석은 예매할 수 없습니다.");
        }
    }

    private void validateSameSchedule(List<Seat> seats) {
        long scheduleCount = seats.stream()
                .map(seat -> seat.getSchedule().getId())
                .distinct()
                .count();
        if (scheduleCount != 1) {
            throw new IllegalArgumentException("같은 회차의 좌석만 함께 예매할 수 있습니다.");
        }
    }

    // 예약 만료
    public int expireReservations() {
        LocalDateTime now = LocalDateTime.now();
        List<Reservation> expiredReservations =
                reservationRepository.findByStatusAndExpiresAtLessThanEqual(ReservationStatus.PENDING, now);
        int expiredCount = 0;
        for (Reservation reservation : expiredReservations) {
            Payment payment = paymentRepository.findByReservationId(reservation.getId())
                    .orElse(null);

            String orderId = payment != null ? payment.getOrderId() : "N/A";
            Long paymentId = payment != null ? payment.getId() : null;
            log.info("event={} orderId={} paymentId={} reservationId={} reason={}",
                    LogEvents.RESERVATION_EXPIRE_START, orderId, paymentId, reservation.getId(), "REQUEST");

            if (payment != null && payment.getStatus() == PaymentStatus.READY) {
                payment.fail();
                log.info("event={} orderId={} paymentId={} reservationId={} reason={}",
                        LogEvents.PAYMENT_EXPIRE_SUCCESS, orderId, paymentId, reservation.getId(), "READY_TO_FAILED");
            }

            reservation.expire();
            reservation.getSeat().release();
            log.info("event={} orderId={} paymentId={} reservationId={} reason={}",
                    LogEvents.RESERVATION_EXPIRE_SUCCESS, orderId, paymentId, reservation.getId(), "EXPIRED_BY_SCHEDULER");
            expiredCount++;
        }
        return expiredCount;
    }
}
