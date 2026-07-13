package com.jipi.ticket_ledger.seat.application;

import com.jipi.ticket_ledger.reservation.application.ReservationExpirationService;
import com.jipi.ticket_ledger.seat.application.model.SeatAvailabilityResponse;
import com.jipi.ticket_ledger.seat.application.model.SeatListResponse;
import com.jipi.ticket_ledger.seat.application.model.SeatResponse;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatQueryService {

    private final SeatRepository seatRepository;
    private final ReservationExpirationService reservationExpirationService;

    public SeatListResponse getSeats(Long scheduleId) {
        long startNanos = System.nanoTime();
        reservationExpirationService.expireByScheduleId(scheduleId);
        long afterExpirationNanos = System.nanoTime();

        SeatAvailabilityResponse availability = getScheduleAvailability(List.of(scheduleId)).stream()
                .findFirst()
                .orElse(new SeatAvailabilityResponse(scheduleId, false, 0, 0, 0));
        long afterAvailabilityNanos = System.nanoTime();

        if (availability.soldOut()) {
            logProfile(scheduleId, startNanos, afterExpirationNanos, afterAvailabilityNanos,
                    afterAvailabilityNanos, afterAvailabilityNanos, 0, true);
            return new SeatListResponse(scheduleId, true, List.of());
        }

        List<SeatRepository.SeatSummary> summaries = seatRepository.findAvailableSeatSummariesByScheduleId(scheduleId);
        long afterRepositoryNanos = System.nanoTime();
        List<SeatResponse> seats = summaries.stream()
                .map(summary -> new SeatResponse(summary.getId(), summary.getSeatNumber(), summary.getGrade(),
                        summary.getPrice(), summary.getStatus().name()))
                .toList();
        long afterMappingNanos = System.nanoTime();

        logProfile(scheduleId, startNanos, afterExpirationNanos, afterAvailabilityNanos,
                afterRepositoryNanos, afterMappingNanos, seats.size(), false);
        return new SeatListResponse(scheduleId, false, seats);
    }

    @Transactional(readOnly = true)
    public List<SeatAvailabilityResponse> getScheduleAvailability(Collection<Long> scheduleIds) {
        Set<Long> distinctScheduleIds = scheduleIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctScheduleIds.isEmpty()) {
            return List.of();
        }
        if (distinctScheduleIds.size() > 100) {
            throw new IllegalArgumentException("회차 매진 여부는 한 번에 최대 100개까지 조회할 수 있습니다.");
        }

        Map<Long, EnumMap<SeatStatus, Long>> countsByScheduleId = seatRepository
                .countStatusesByScheduleIds(distinctScheduleIds)
                .stream()
                .collect(Collectors.groupingBy(
                        SeatRepository.SeatStatusCount::getScheduleId,
                        Collectors.toMap(SeatRepository.SeatStatusCount::getStatus,
                                SeatRepository.SeatStatusCount::getCount, Long::sum,
                                () -> new EnumMap<>(SeatStatus.class))
                ));

        return distinctScheduleIds.stream()
                .map(scheduleId -> toAvailabilityResponse(scheduleId, countsByScheduleId.get(scheduleId)))
                .toList();
    }

    private SeatAvailabilityResponse toAvailabilityResponse(Long scheduleId, Map<SeatStatus, Long> counts) {
        long available = getCount(counts, SeatStatus.AVAILABLE);
        long held = getCount(counts, SeatStatus.HELD);
        long booked = getCount(counts, SeatStatus.BOOKED);
        return new SeatAvailabilityResponse(scheduleId, available == 0 && held == 0 && booked > 0,
                available, held, booked);
    }

    private long getCount(Map<SeatStatus, Long> counts, SeatStatus status) {
        return counts == null ? 0 : counts.getOrDefault(status, 0L);
    }

    private void logProfile(Long scheduleId, long start, long afterExpiration, long afterAvailability,
                            long afterRepository, long afterMapping, int seatCount, boolean soldOut) {
        log.debug("event=SEAT_LOOKUP_PROFILE scheduleId={} expirationMs={} availabilityMs={} repositoryMs={} mappingMs={} serviceTotalMs={} seatCount={} soldOut={}",
                scheduleId,
                elapsedMillis(start, afterExpiration),
                elapsedMillis(afterExpiration, afterAvailability),
                elapsedMillis(afterAvailability, afterRepository),
                elapsedMillis(afterRepository, afterMapping),
                elapsedMillis(start, afterMapping),
                seatCount,
                soldOut);
    }

    private double elapsedMillis(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000.0;
    }
}
