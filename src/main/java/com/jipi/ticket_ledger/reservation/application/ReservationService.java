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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReservationService {
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final ReservationGroupRepository reservationGroupRepository;
    private final Clock clock;

    @Value("${reservation.hold-duration}")
    private Duration holdDuration;

    // 공연 시작 시간(공연장 로컬 LocalDateTime)을 "지금 몇 시인가"로 비교할 때 쓰는 서비스 타임존.
    // 컨테이너 타임존에 의존하지 않도록 코드에서 명시적으로 사용한다(향후 venue별 존으로 확장).
    @Value("${app.time.service-zone:Asia/Seoul}")
    private String serviceZoneId;

    public Long createReservation(Long userId, List<Long> seatIds) {
        log.info("event={} userId={} requestedSeatCount={}",
                LogEvents.RESERVATION_CREATE_START, userId, seatIds == null ? 0 : seatIds.size());
        validateSeatIds(seatIds);
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
        validateSameSchedule(seats);

        // 2) 좌석 상태를 AVAILABLE -> HELD로 변경해 임시 선점한다.
        Instant now = clock.instant();
        // 예매 오픈은 절대 시점(Instant)으로, 공연 시작은 공연장 로컬(LocalDateTime + 서비스 타임존)으로 검증한다.
        validateBookingOpen(seats, now);
        validateScheduleNotStarted(seats, LocalDateTime.now(clock.withZone(ZoneId.of(serviceZoneId))));
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

    private void validateSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            log.warn("event={} reason={}", LogEvents.RESERVATION_CREATE_REJECT, "EMPTY_SEAT_IDS");
            throw new IllegalArgumentException("최소 1개의 좌석을 선택해야 합니다.");
        }
        if (seatIds.size() > ReservationGroup.MAX_SEAT_COUNT) {
            log.warn("event={} requestedSeatCount={} maxSeatCount={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, seatIds.size(), ReservationGroup.MAX_SEAT_COUNT, "SEAT_LIMIT_EXCEEDED");
            throw new IllegalArgumentException("좌석은 최대 2개까지 예매할 수 있습니다.");
        }
        if (seatIds.stream().anyMatch(seatId -> seatId == null || seatId <= 0)) {
            log.warn("event={} requestedSeatIds={} reason={}", LogEvents.RESERVATION_CREATE_REJECT, seatIds, "INVALID_SEAT_ID");
            throw new IllegalArgumentException("좌석 ID가 올바르지 않습니다.");
        }
        // LinkedHashSet: 중복 제거 + 입력 순서 유지(에러 메시지/검증 흐름에서 사용자 입력 순서를 유지하기 위함)
        Set<Long> uniqueSeatIds = new LinkedHashSet<>(seatIds);
        if (uniqueSeatIds.size() != seatIds.size()) {
            log.warn("event={} requestedSeatIds={} reason={}", LogEvents.RESERVATION_CREATE_REJECT, seatIds, "DUPLICATE_SEAT_ID");
            throw new IllegalArgumentException("중복된 좌석은 예매할 수 없습니다.");
        }
    }

    private void validateSameSchedule(List<Seat> seats) {
        long scheduleCount = seats.stream()
                .map(seat -> seat.getSchedule().getId())
                .distinct()
                .count();
        if (scheduleCount != 1) {
            log.warn("event={} scheduleCount={} reason={}", LogEvents.RESERVATION_CREATE_REJECT, scheduleCount, "DIFFERENT_SCHEDULE");
            throw new IllegalArgumentException("같은 회차의 좌석만 함께 예매할 수 있습니다.");
        }
    }

    private void validateBookingOpen(List<Seat> seats, Instant now) {
        Instant bookingOpenAt = seats.get(0).getSchedule().getEvent().getBookingOpenAt();
        if (bookingOpenAt.isAfter(now)) {
            log.warn("event={} bookingOpenAt={} now={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, bookingOpenAt, now, "BOOKING_NOT_OPEN");
            throw new IllegalStateException("예매 오픈 전 공연은 예약할 수 없습니다.");
        }
    }

    private void validateScheduleNotStarted(List<Seat> seats, LocalDateTime now) {
        LocalDateTime startAt = seats.get(0).getSchedule().getStartAt();
        if (!startAt.isAfter(now)) {
            log.warn("event={} startAt={} now={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, startAt, now, "SCHEDULE_ALREADY_STARTED");
            throw new IllegalStateException("시작된(또는 종료된) 회차는 예약할 수 없습니다.");
        }
    }
}
