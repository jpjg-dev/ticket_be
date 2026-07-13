package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final ReservationGroupRepository reservationGroupRepository;
    private final ReservationCreationPolicy reservationCreationPolicy;
    private final Clock clock;

    @Value("${reservation.hold-duration}")
    private Duration holdDuration;

    // 공연 시작 시간(공연장 로컬 LocalDateTime)을 "지금 몇 시인가"로 비교할 때 쓰는 서비스 타임존.
    // 컨테이너 타임존에 의존하지 않도록 코드에서 명시적으로 사용한다(향후 venue별 존으로 확장).
    @Value("${app.time.service-zone:Asia/Seoul}")
    private String serviceZoneId;

    @Transactional
    public Long createReservation(Long userId, List<Long> seatIds) {
        log.info("event={} userId={} requestedSeatCount={}",
                LogEvents.RESERVATION_CREATE_START, userId, seatIds == null ? 0 : seatIds.size());
        reservationCreationPolicy.validateSeatIds(seatIds);
        // 1) 요청에 포함된 사용자와 좌석을 조회하고, 없으면 즉시 예외를 던진다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        List<Long> sortedSeatIds = seatIds.stream()
                .distinct()
                .sorted()
                .toList();

        List<Seat> seats;
        try {
            seatRepository.setSeatLockTimeoutForCurrentTransaction();
            seats = seatRepository.findAllByIdInForUpdate(sortedSeatIds);
        } catch (PessimisticLockingFailureException lockTimeout) {
            // 1초 안에 락을 못 얻음 = 다른 요청이 지금 이 좌석(들)을 선점 처리 중.
            log.warn("event={} userId={} requestedSeatIds={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, userId, sortedSeatIds, "SEAT_LOCK_TIMEOUT");
            throw new IllegalStateException("다른 사용자가 선택 중인 좌석입니다. 잠시 후 다시 시도해주세요.");
        }
        if (seats.size() != sortedSeatIds.size()) {
            log.warn("event={} userId={} requestedSeatIds={} foundSeatCount={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, userId, sortedSeatIds, seats.size(), "SEAT_NOT_FOUND");
            throw new EntityNotFoundException("좌석을 찾을 수 없습니다.");
        }
        Instant now = clock.instant();
        reservationCreationPolicy.validateSeats(
                seats,
                now,
                LocalDateTime.now(clock.withZone(ZoneId.of(serviceZoneId)))
        );
        Instant expiresAt = now.plus(holdDuration);
        ReservationGroup reservationGroup = reservationGroupRepository.save(new ReservationGroup(user, now, expiresAt));

        List<Reservation> reservations = seats.stream()
                .map(seat -> {
                    seat.hold();
                    return new Reservation(user, seat, reservationGroup, now, expiresAt);
                })
                .toList();
        reservationRepository.saveAll(reservations);

        // 3) 생성된 예매 묶음 식별자를 반환한다.
        log.info("event={} userId={} reservationGroupId={} seatCount={} expiresAt={}",
                LogEvents.RESERVATION_CREATE_SUCCESS, userId, reservationGroup.getId(), reservations.size(), reservationGroup.getExpiresAt());
        return reservationGroup.getId();
    }

}
