package com.jipi.ticket_ledger.seat.domain;


import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    // PostgreSQL은 FOR UPDATE에 초 단위 대기시간을 표현하는 SQL 문법이 없다(NOWAIT/SKIP LOCKED만 지원).
    // jakarta.persistence.lock.timeout 힌트도 PostgreSQL 방언에서는 무시된다(NO_WAIT/SKIP_LOCKED 상수만 인식).
    // 그래서 세션이 아니라 "현재 트랜잭션에만" 적용되는 SET LOCAL lock_timeout으로 대기 상한을 건다.
    @Modifying
    @Query(value = "SET LOCAL lock_timeout = '1000ms'", nativeQuery = true)
    void setSeatLockTimeoutForCurrentTransaction();

    // 인기 좌석 동시 경합 시 대기자가 스레드/커넥션을 오래 붙잡지 않도록, 위 SET LOCAL과 함께 호출해 락 대기 상한을 1초로 둔다(기본은 무한 대기).
    // 1초 안에 락을 못 얻으면 다른 요청이 선점 중인 것으로 보고 즉시 실패시킨다(ReservationService에서 처리).
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
              and s.status = com.jipi.ticket_ledger.seat.domain.SeatStatus.AVAILABLE
            order by s.id asc
            """)
    List<SeatSummary> findAvailableSeatSummariesByScheduleId(@Param("scheduleId") Long scheduleId);

    @Query("""
            select s.schedule.id as scheduleId, s.status as status, count(s) as count
            from Seat s
            where s.schedule.id in :scheduleIds
            group by s.schedule.id, s.status
            """)
    List<SeatStatusCount> countStatusesByScheduleIds(@Param("scheduleIds") Collection<Long> scheduleIds);

}
