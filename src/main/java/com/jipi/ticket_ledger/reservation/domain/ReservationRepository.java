package com.jipi.ticket_ledger.reservation.domain;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    //ID 기반 예약상태별 조회
    List<Reservation> findByUserIdAndStatusIn(Long userId, List<ReservationStatus> statusList, Sort sort);

    List<Reservation> findByReservationGroupId(Long reservationGroupId);
}
