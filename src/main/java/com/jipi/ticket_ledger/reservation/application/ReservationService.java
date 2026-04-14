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
        // 1) 요청에 포함된 사용자/회차/좌석을 조회하고, 없으면 즉시 예외를 던진다.
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        Schedule schedule = scheduleRepository.findById(command.scheduleId())
                .orElseThrow(() -> new EntityNotFoundException("회차를 찾을 수 없습니다."));

        Seat seat = seatRepository.findById(command.seatId())
                .orElseThrow(() -> new EntityNotFoundException("좌석을 찾을 수 없습니다."));

        // 2) 선택한 좌석이 실제로 해당 회차에 속하는지 정합성을 검증한다.
        validateSeatBelongsToSchedule(seat, schedule);

        // 3) 좌석 상태를 AVAILABLE -> HELD로 변경해 임시 선점한다.
        seat.hold();

        // 4) 예약 홀드 만료 시각을 계산한다.
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(RESERVATION_HOLD_MINUTES);

        // 5) 예약 엔티티를 생성하고 저장한다.
        Reservation reservation = new Reservation(user, seat, expiresAt);

        reservationRepository.save(reservation);

        // 6) 생성된 예약 식별자를 반환한다.
        return reservation.getId();
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

    private void validateSeatBelongsToSchedule(Seat seat, Schedule schedule) {
        if (!seat.getSchedule().getId().equals(schedule.getId())) {
            throw new IllegalArgumentException("해당 좌석은 선택한 회차의 좌석이 아닙니다.");
        }
    }

}
