package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "logging.level.org.hibernate.orm.jdbc.bind=OFF",
        "logging.level.org.hibernate.type.descriptor.sql=OFF"
})
@Slf4j
class ReservationConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationGroupRepository reservationGroupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("겹치는 좌석 묶음 동시 요청 시 하나의 ReservationGroup만 전체 성공한다")
    void createReservationConcurrentOverlappedSeatGroups() throws InterruptedException {
        LocalDateTime now = LocalDateTime.now();
        String runId = String.valueOf(System.nanoTime());

        Event event = eventRepository.save(new Event(
                "다중 좌석 동시성 공연-" + runId,
                "겹치는 좌석 묶음 동시성 테스트",
                "테스트홀",
                now,
                now
        ));
        Schedule schedule = scheduleRepository.save(new Schedule(
                event,
                now.plusDays(1),
                now.plusDays(1).plusHours(2),
                now
        ));

        Seat seatA1 = seatRepository.save(new Seat(schedule, "A-1-" + runId, "VIP", 100000, now));
        Seat seatA2 = seatRepository.save(new Seat(schedule, "A-2-" + runId, "VIP", 100000, now));
        Seat seatA3 = seatRepository.save(new Seat(schedule, "A-3-" + runId, "VIP", 100000, now));
        List<Long> seatIds = List.of(seatA1.getId(), seatA2.getId(), seatA3.getId());

        List<User> users = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            users.add(userRepository.save(new User(
                    "group-concurrency-" + index + "-" + runId + "@test.com",
                    "password",
                    "테스터" + index,
                    now
            )));
        }
        List<Long> userIds = new ArrayList<>();
        for (User user : users) {
            userIds.add(user.getId());
        }

        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CountDownLatch readyLatch = new CountDownLatch(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(10);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<Runnable> requests = new ArrayList<>();
        boolean useFirstPair = true;
        for (User user : users) {
            List<Long> requestSeatIds = useFirstPair
                    ? List.of(seatA1.getId(), seatA2.getId())
                    : List.of(seatA2.getId(), seatA3.getId());
            requests.add(() -> reservationService.createReservation(user.getId(), requestSeatIds));
            useFirstPair = !useFirstPair;
        }

        for (Runnable request : requests) {
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    request.run();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS));
        startLatch.countDown();
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS));
        executorService.shutdown();

        List<Reservation> savedReservations = reservationRepository.findAll().stream()
                .filter(reservation -> seatIds.contains(reservation.getSeat().getId()))
                .toList();
        long savedGroupCount = savedReservations.stream()
                .map(reservation -> reservation.getReservationGroup().getId())
                .distinct()
                .count();
        long heldSeatCount = seatRepository.findAllById(seatIds).stream()
                .filter(seat -> seat.getStatus() == SeatStatus.HELD)
                .count();

        String resultSummary = "결과 - success=" + successCount.get()
                + ", fail=" + failCount.get()
                + ", reservations=" + savedReservations.size()
                + ", groups=" + savedGroupCount
                + ", heldSeats=" + heldSeatCount;

        log.info("\n ================== " + resultSummary + " ==================");

        assertEquals(1, successCount.get(), "겹치는 좌석 묶음은 한 요청만 성공해야 합니다. " + resultSummary);
        assertEquals(9, failCount.get(), "겹치는 좌석 묶음 중 나머지 요청은 실패해야 합니다. " + resultSummary);
        assertEquals(2, savedReservations.size(), "성공한 group의 좌석 2개만 reservation으로 저장되어야 합니다. " + resultSummary);
        assertEquals(1, savedGroupCount, "저장된 reservation은 하나의 group에만 속해야 합니다. " + resultSummary);
        assertEquals(2, heldSeatCount, "성공한 group의 좌석 2개만 HELD 상태여야 합니다. " + resultSummary);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            savedReservations.stream()
                    .map(Reservation::getId)
                    .forEach(reservationRepository::deleteById);
            reservationGroupRepository.findAll().stream()
                    .filter(group -> userIds.contains(group.getUser().getId()))
                    .map(ReservationGroup::getId)
                    .forEach(reservationGroupRepository::deleteById);
            seatIds.forEach(seatRepository::deleteById);
            scheduleRepository.deleteById(schedule.getId());
            eventRepository.deleteById(event.getId());
            userIds.forEach(userRepository::deleteById);
        });
    }
}
