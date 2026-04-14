package com.jipi.ticket_ledger.seat.domain;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {
    //특정 회차 좌석 목록
    List<Seat> findByScheduleId(Long scheduleId);

    //특정 회차에 특정 좌석 찾기
    Optional<Seat> findByScheduleIdAndSeatNumber(Long scheduleId, String seatNumber);
}
