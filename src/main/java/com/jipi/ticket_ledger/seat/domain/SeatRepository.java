package com.jipi.ticket_ledger.seat.domain;


import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    interface SeatStatusCount {
        Long getScheduleId();

        SeatStatus getStatus();

        long getCount();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id in :seatIds order by s.id asc")
    List<Seat> findAllByIdInForUpdate(@Param("seatIds") Collection<Long> seatIds);

    //특정 회차 좌석 목록
    List<Seat> findByScheduleId(Long scheduleId);

    List<Seat> findByScheduleIdIn(Collection<Long> scheduleIds);

    @Query("""
            select s.schedule.id as scheduleId, s.status as status, count(s) as count
            from Seat s
            where s.schedule.id in :scheduleIds
            group by s.schedule.id, s.status
            """)
    List<SeatStatusCount> countStatusesByScheduleIds(@Param("scheduleIds") Collection<Long> scheduleIds);

}
