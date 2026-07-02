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

    interface SeatSummary {
        Long getId();

        String getSeatNumber();

        String getGrade();

        Integer getPrice();

        SeatStatus getStatus();
    }

    interface SeatStatusCount {
        Long getScheduleId();

        SeatStatus getStatus();

        long getCount();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id in :seatIds order by s.id asc")
    List<Seat> findAllByIdInForUpdate(@Param("seatIds") Collection<Long> seatIds);

    @Query("""
            select s.id as id,
                   s.seatNumber as seatNumber,
                   s.grade as grade,
                   s.price as price,
                   s.status as status
            from Seat s
            where s.schedule.id = :scheduleId
            order by s.id asc
            """)
    List<SeatSummary> findSeatSummariesByScheduleId(@Param("scheduleId") Long scheduleId);

    @Query("""
            select s.schedule.id as scheduleId, s.status as status, count(s) as count
            from Seat s
            where s.schedule.id in :scheduleIds
            group by s.schedule.id, s.status
            """)
    List<SeatStatusCount> countStatusesByScheduleIds(@Param("scheduleIds") Collection<Long> scheduleIds);

}
