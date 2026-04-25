package com.jipi.ticket_ledger.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    List<Schedule> findByEventIdOrderByStartAtAsc(Long eventId);

    List<Schedule> findByEventIdInOrderByStartAtAsc(Collection<Long> eventIds);

}
