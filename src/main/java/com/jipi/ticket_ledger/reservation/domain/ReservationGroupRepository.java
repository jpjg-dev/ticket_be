package com.jipi.ticket_ledger.reservation.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationGroupRepository extends JpaRepository<ReservationGroup, Long> {
    List<ReservationGroup> findByExpiresAtLessThanEqual(LocalDateTime time);

    @Query("""
            select distinct r.reservationGroup
            from Reservation r
            where r.seat.schedule.id = :scheduleId
              and r.reservationGroup.expiresAt <= :time
            """)
    List<ReservationGroup> findExpiredByScheduleId(
            @Param("scheduleId") Long scheduleId,
            @Param("time") LocalDateTime time
    );
}
