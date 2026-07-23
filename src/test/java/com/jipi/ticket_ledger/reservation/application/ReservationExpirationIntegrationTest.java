package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "reservation.expire-scheduler.batch-size=1000",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class ReservationExpirationIntegrationTest extends PostgresTestContainerSupport {

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private ReservationGroupRepository reservationGroupRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixtureIds fixtureIds;

    @AfterEach
    void cleanUp() {
        if (fixtureIds == null) {
            return;
        }
        jdbcTemplate.update("delete from reservations where reservation_group_id in (?, ?, ?)", fixtureIds.groupIds().toArray());
        jdbcTemplate.update("delete from reservation_groups where id in (?, ?, ?)", fixtureIds.groupIds().toArray());
        jdbcTemplate.update("delete from seats where id in (?, ?, ?)", fixtureIds.seatIds().toArray());
        jdbcTemplate.update("delete from schedules where id = ?", fixtureIds.scheduleId());
        jdbcTemplate.update("delete from events where id = ?", fixtureIds.eventId());
        jdbcTemplate.update("delete from users where id = ?", fixtureIds.userId());
    }

    @Test
    @DisplayName("expireAll: 중간 그룹이 롤백되어도 다음 그룹은 독립 트랜잭션으로 만료된다")
    void isolatesFailedGroupAndContinuesBatch() {
        fixtureIds = createFixtures();

        expirationService.expireAll();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            ReservationGroup first = reservationGroupRepository.findById(fixtureIds.groupIds().get(0)).orElseThrow();
            ReservationGroup failed = reservationGroupRepository.findById(fixtureIds.groupIds().get(1)).orElseThrow();
            ReservationGroup third = reservationGroupRepository.findById(fixtureIds.groupIds().get(2)).orElseThrow();

            assertEquals(ReservationGroupStatus.EXPIRED, first.getStatus());
            assertEquals(ReservationGroupStatus.PENDING, failed.getStatus());
            assertEquals(ReservationGroupStatus.EXPIRED, third.getStatus());
            assertEquals(SeatStatus.AVAILABLE, seatRepository.findById(fixtureIds.seatIds().get(0)).orElseThrow().getStatus());
            assertEquals(SeatStatus.BOOKED, seatRepository.findById(fixtureIds.seatIds().get(1)).orElseThrow().getStatus());
            assertEquals(SeatStatus.AVAILABLE, seatRepository.findById(fixtureIds.seatIds().get(2)).orElseThrow().getStatus());
        });
    }

    private FixtureIds createFixtures() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            Instant now = Instant.now();
            User user = userRepository.save(new User(
                    "expiration-isolation-" + System.nanoTime() + "@test.com", "password", "만료 격리", now));
            Event event = eventRepository.save(new Event("만료 격리 공연", "통합 테스트", "테스트홀", now, now));
            Schedule schedule = scheduleRepository.save(new Schedule(
                    event, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2), now));

            List<Long> groupIds = new ArrayList<>();
            List<Long> seatIds = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                ReservationGroup group = reservationGroupRepository.save(
                        new ReservationGroup(user, now.minusSeconds(120), now.minusSeconds(60)));
                Seat seat = new Seat(schedule, "ISO-" + System.nanoTime(), "VIP", 100000, now);
                seat.hold();
                if (index == 1) {
                    seat.book();
                }
                seatRepository.save(seat);
                reservationRepository.save(new Reservation(user, seat, group, now.minusSeconds(120), now.minusSeconds(60)));
                groupIds.add(group.getId());
                seatIds.add(seat.getId());
            }
            return new FixtureIds(user.getId(), event.getId(), schedule.getId(), groupIds, seatIds);
        });
    }

    private record FixtureIds(
            Long userId,
            Long eventId,
            Long scheduleId,
            List<Long> groupIds,
            List<Long> seatIds
    ) {
    }
}
