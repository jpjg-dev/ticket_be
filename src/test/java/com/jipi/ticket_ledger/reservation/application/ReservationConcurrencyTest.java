package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationConcurrencyTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("동일 seatId로 20명이 동시에 예약 요청하면 1건만 성공해야 한다")
    void createReservationConcurrentSingleSeat() throws InterruptedException {
        int users = 20;
        long seatId = 1L;

        Seat sharedSeat = createSeat();
        Map<Long, User> userMap = createUsers(users);

        AtomicLong reservationIdGenerator = new AtomicLong(0L);
        List<Reservation> savedReservations = new ArrayList<>();

        when(userRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            return Optional.ofNullable(userMap.get(userId));
        });
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(sharedSeat));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservation, "id", reservationIdGenerator.incrementAndGet());
            synchronized (savedReservations) {
                savedReservations.add(reservation);
            }
            return reservation;
        });

        ExecutorService executorService = Executors.newFixedThreadPool(users);
        CountDownLatch readyLatch = new CountDownLatch(users);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(users);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (long userId = 1; userId <= users; userId++) {
            long currentUserId = userId;
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    reservationService.createReservation(new CreateReservationCommand(currentUserId, seatId));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

        executorService.shutdown();

        String resultSummary = "결과 - success=" + successCount.get()
                + ", fail=" + failCount.get()
                + ", reservations=" + savedReservations.size()
                + ", seatStatus=" + sharedSeat.getStatus();

        System.out.println(resultSummary);

        // 성공 건수 1건 초과 시 실패
        if (successCount.get() > 1) {
            throw new AssertionError("성공 건수가 1건을 초과하면 중복 예약입니다. " + resultSummary);
        }
        if (savedReservations.size() != 1) {
            throw new AssertionError("동일 좌석으로 Reservation은 1건만 생성되어야 합니다. " + resultSummary);
        }
        if (sharedSeat.getStatus() != SeatStatus.HELD) {
            throw new AssertionError("좌석 최종 상태는 HELD여야 합니다. " + resultSummary);
        }
    }

    private Map<Long, User> createUsers(int users) {
        Map<Long, User> userMap = new ConcurrentHashMap<>();
        for (long id = 1; id <= users; id++) {
            User user = new User("user" + id + "@test.com", "password", "테스터" + id, LocalDateTime.now());
            ReflectionTestUtils.setField(user, "id", id);
            userMap.put(id, user);
        }
        return userMap;
    }

    private Seat createSeat() {
        Event event = new Event("공연", "설명", "장소", LocalDateTime.now(), LocalDateTime.now());
        Schedule schedule = new Schedule(
                event,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(2),
                LocalDateTime.now()
        );
        Seat seat = new Seat(schedule, "A-1", "VIP", 100000, LocalDateTime.now());
        ReflectionTestUtils.setField(seat, "id", 1L);
        return seat;
    }
}
