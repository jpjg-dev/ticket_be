package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {
    private static final long RESERVATION_HOLD_MINUTES = 10L;

    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;

    public Long createReservation(CreateReservationCommand command) {
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

    // 예약 취소
    public void cancelReservation(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new EntityNotFoundException("예약을 찾을 수 없습니다."));

        reservation.cancel();
        reservation.getSeat().release();
    }

    // 예약 만료
    public void expireReservations(){
        LocalDateTime now = LocalDateTime.now();
        List<Reservation> expiredReservations =
                reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, now);
        for(Reservation reservation : expiredReservations){
            reservation.expire();
            reservation.getSeat().release();
        }
    }
}
