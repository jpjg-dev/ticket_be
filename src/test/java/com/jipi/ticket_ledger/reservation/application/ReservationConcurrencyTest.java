package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
class ReservationConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

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
    @DisplayName("동일 seatId로 20명이 동시에 예약 요청하면 1건만 성공해야 한다")
    void createReservationConcurrentSingleSeat() throws InterruptedException {
        // given: 실제 DB에 테스트 데이터 생성
        int users = 20;
        LocalDateTime now = LocalDateTime.now();
        String runId = String.valueOf(System.nanoTime());

        Event event = eventRepository.save(new Event(
                "동시성 검증 공연-" + runId,
                "비관적 락 동시성 테스트",
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

        Seat seat = seatRepository.save(new Seat(schedule, "A-1-" + runId, "VIP", 100000, now));
        Long seatId = seat.getId();

        List<Long> userIds = new ArrayList<>();
        for (int i = 1; i <= users; i++) {
            User user = userRepository.save(new User(
                    "concurrency-user-" + runId + "-" + i + "@test.com",
                    "password",
                    "테스터" + i,
                    now
            ));
            userIds.add(user.getId());
        }

        // when: 동일 좌석에 동시 예약 요청
        ExecutorService executorService = Executors.newFixedThreadPool(users);
        CountDownLatch readyLatch = new CountDownLatch(users);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(users);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (Long userId : userIds) {
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    reservationService.createReservation(new CreateReservationCommand(userId, seatId));
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

        // then: 비관적 락 기준 검증
        Seat finalSeat = seatRepository.findById(seatId).orElseThrow();
        long reservationCountForSeat = reservationRepository.findAll().stream()
                .filter(r -> r.getSeat().getId().equals(seatId))
                .count();

        String resultSummary = "결과 - success=" + successCount.get()
                + ", fail=" + failCount.get()
                + ", reservations=" + reservationCountForSeat
                + ", seatStatus=" + finalSeat.getStatus();

        System.out.println(resultSummary);

        if (successCount.get() > 1) {
            throw new AssertionError("성공 건수가 1건을 초과하면 중복 예약입니다. " + resultSummary);
        }
        assertEquals(1L, reservationCountForSeat, "동일 좌석으로 Reservation은 1건만 생성되어야 합니다. " + resultSummary);
        assertEquals(SeatStatus.HELD, finalSeat.getStatus(), "좌석 최종 상태는 HELD여야 합니다. " + resultSummary);

        // 테스트에서 생성한 데이터만 정리
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            reservationRepository.findAll().stream()
                    .filter(r -> r.getSeat().getId().equals(seatId))
                    .map(Reservation::getId)
                    .forEach(reservationRepository::deleteById);
            seatRepository.deleteById(seatId);
            scheduleRepository.deleteById(schedule.getId());
            eventRepository.deleteById(event.getId());
            userIds.forEach(userRepository::deleteById);
        });
    }
}
