package com.jipi.ticket_ledger.seat.domain;


import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :seatId")
    Optional<Seat> findByIdForUpdate(@Param("seatId") Long seatId);

    //특정 회차 좌석 목록
    List<Seat> findByScheduleId(Long scheduleId);

    List<Seat> findByScheduleIdIn(Collection<Long> scheduleIds);

    //특정 회차에 특정 좌석 찾기
    Optional<Seat> findByScheduleIdAndSeatNumber(Long scheduleId, String seatNumber);
}
