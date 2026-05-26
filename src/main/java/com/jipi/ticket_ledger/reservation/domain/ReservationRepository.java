package com.jipi.ticket_ledger.reservation.domain;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByReservationGroupUserIdAndReservationGroupStatusIn(
            Long userId,
            List<ReservationGroupStatus> statusList,
            Sort sort
    );

    List<Reservation> findByReservationGroupId(Long reservationGroupId);
}
