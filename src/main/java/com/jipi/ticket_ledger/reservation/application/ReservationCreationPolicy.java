package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.seat.domain.Seat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class ReservationCreationPolicy {

    public void validateSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            log.warn("event={} reason={}", LogEvents.RESERVATION_CREATE_REJECT, "EMPTY_SEAT_IDS");
            throw new IllegalArgumentException("최소 1개의 좌석을 선택해야 합니다.");
        }
        if (seatIds.size() > ReservationGroup.MAX_SEAT_COUNT) {
            log.warn("event={} requestedSeatCount={} maxSeatCount={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, seatIds.size(), ReservationGroup.MAX_SEAT_COUNT,
                    "SEAT_LIMIT_EXCEEDED");
            throw new IllegalArgumentException("좌석은 최대 2개까지 예매할 수 있습니다.");
        }
        if (seatIds.stream().anyMatch(seatId -> seatId == null || seatId <= 0)) {
            log.warn("event={} requestedSeatIds={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, seatIds, "INVALID_SEAT_ID");
            throw new IllegalArgumentException("좌석 ID가 올바르지 않습니다.");
        }
        Set<Long> uniqueSeatIds = new LinkedHashSet<>(seatIds);
        if (uniqueSeatIds.size() != seatIds.size()) {
            log.warn("event={} requestedSeatIds={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, seatIds, "DUPLICATE_SEAT_ID");
            throw new IllegalArgumentException("중복된 좌석은 예매할 수 없습니다.");
        }
    }

    public void validateSeats(List<Seat> seats, Instant now, LocalDateTime serviceNow) {
        validateSameSchedule(seats);
        validateBookingOpen(seats, now);
        validateScheduleNotStarted(seats, serviceNow);
    }

    public void validateSameSchedule(List<Seat> seats) {
        long scheduleCount = seats.stream().map(seat -> seat.getSchedule().getId()).distinct().count();
        if (scheduleCount != 1) {
            log.warn("event={} scheduleCount={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, scheduleCount, "DIFFERENT_SCHEDULE");
            throw new IllegalArgumentException("같은 회차의 좌석만 함께 예매할 수 있습니다.");
        }
    }

    public void validateBookingOpen(List<Seat> seats, Instant now) {
        Instant bookingOpenAt = seats.getFirst().getSchedule().getEvent().getBookingOpenAt();
        if (bookingOpenAt.isAfter(now)) {
            log.warn("event={} bookingOpenAt={} now={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, bookingOpenAt, now, "BOOKING_NOT_OPEN");
            throw new IllegalStateException("예매 오픈 전 공연은 예약할 수 없습니다.");
        }
    }

    public void validateScheduleNotStarted(List<Seat> seats, LocalDateTime now) {
        LocalDateTime startAt = seats.getFirst().getSchedule().getStartAt();
        if (!startAt.isAfter(now)) {
            log.warn("event={} startAt={} now={} reason={}",
                    LogEvents.RESERVATION_CREATE_REJECT, startAt, now, "SCHEDULE_ALREADY_STARTED");
            throw new IllegalStateException("시작된(또는 종료된) 회차는 예약할 수 없습니다.");
        }
    }
}
