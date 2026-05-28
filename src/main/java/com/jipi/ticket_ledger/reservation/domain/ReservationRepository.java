package com.jipi.ticket_ledger.reservation.domain;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    @Query("""
            select r
            from Reservation r
            join fetch r.reservationGroup rg
            join fetch r.seat s
            join fetch s.schedule sc
            join fetch sc.event
            where rg.user.id = :userId
              and rg.status in :statusList
            """)
    List<Reservation> findByReservationGroupUserIdAndReservationGroupStatusIn(
            @Param("userId") Long userId,
            @Param("statusList") List<ReservationGroupStatus> statusList,
            Sort sort
    );

    List<Reservation> findByReservationGroupId(Long reservationGroupId);
}
