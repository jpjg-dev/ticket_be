package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
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
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReservationService {
    private static final long RESERVATION_HOLD_MINUTES = 10L;

    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final PaymentRepository paymentRepository;

    public Long createReservation(CreateReservationCommand command) {
        // 수동 요청들어올시 예약만료 검사
        expireReservations();
        // 1) 요청에 포함된 사용자와 좌석을 조회하고, 없으면 즉시 예외를 던진다.
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        Seat seat = seatRepository.findByIdForUpdate(command.seatId())
                .orElseThrow(() -> new EntityNotFoundException("좌석을 찾을 수 없습니다."));
        // 2) 좌석 상태를 AVAILABLE -> HELD로 변경해 임시 선점한다.
        seat.hold();

        // 3) 현재 시각 기준으로 예약을 생성하고 저장한다.
        LocalDateTime now = LocalDateTime.now();

        // 4) 생성된 예약 식별자를 반환한다.
        return reservationRepository.save(new Reservation(user, seat, now)).getId();
    }

    // 예약 만료
    public int expireReservations(){
        LocalDateTime now = LocalDateTime.now();
        List<Reservation> expiredReservations =
                reservationRepository.findByStatusAndExpiresAtLessThanEqual(ReservationStatus.PENDING, now);
        int expiredCount = 0;
        for(Reservation reservation : expiredReservations){
            Payment payment = paymentRepository.findByReservationId(reservation.getId())
                    .orElse(null);

            String orderId = payment != null ? payment.getOrderId() : "N/A";
            Long paymentId = payment != null ? payment.getId() : null;
            log.info("event=RESERVATION_EXPIRE_START orderId={} paymentId={} reservationId={} reason={}",
                    orderId, paymentId, reservation.getId(), "REQUEST");

            if (payment != null && payment.getStatus() == PaymentStatus.READY) {
                payment.fail();
                log.info("event=PAYMENT_EXPIRE_SUCCESS orderId={} paymentId={} reservationId={} reason={}",
                        orderId, paymentId, reservation.getId(), "READY_TO_FAILED");
            }

            reservation.expire();
            reservation.getSeat().release();
            log.info("event=RESERVATION_EXPIRE_SUCCESS orderId={} paymentId={} reservationId={} reason={}",
                    orderId, paymentId, reservation.getId(), "EXPIRED_BY_SCHEDULER");
            expiredCount++;
        }
        return expiredCount;
    }
}
