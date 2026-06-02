package com.jipi.ticket_ledger.reservation.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationGroupRepository extends JpaRepository<ReservationGroup, Long> {
    List<ReservationGroup> findByExpiresAtLessThanEqual(LocalDateTime time);

    @Query("""
            select rg.id
            from ReservationGroup rg
            where rg.status = com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus.PENDING
              and rg.expiresAt <= :time
            """)
    List<Long> findExpiredPendingIds(@Param("time") LocalDateTime time);

    @Query("""
            select distinct r.reservationGroup.id
            from Reservation r
            where r.seat.schedule.id = :scheduleId
              and r.reservationGroup.status = com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus.PENDING
              and r.reservationGroup.expiresAt <= :time
            """)
    List<Long> findExpiredPendingIdsByScheduleId(
            @Param("scheduleId") Long scheduleId,
            @Param("time") LocalDateTime time
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rg from ReservationGroup rg where rg.id = :reservationGroupId")
    Optional<ReservationGroup> findByIdForUpdate(@Param("reservationGroupId") Long reservationGroupId);
}
