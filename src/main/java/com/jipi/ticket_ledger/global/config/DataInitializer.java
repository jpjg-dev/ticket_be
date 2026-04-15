package com.jipi.ticket_ledger.global.config;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (eventRepository.count() > 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        User user = userRepository.save(new User(
                "test-user@ticketledger.com",
                "password",
                "테스트사용자",
                now
        ));

        Event phantom = eventRepository.save(new Event(
                "오페라의 유령",
                "TicketLedger 결제 흐름 테스트용 공연",
                "블루스퀘어 신한카드홀",
                now.minusDays(1),
                now
        ));

        Event lesMiserables = eventRepository.save(new Event(
                "레미제라블",
                "좌석 선점과 결제 승인 흐름 테스트용 공연",
                "샤롯데씨어터",
                now.minusDays(1),
                now
        ));

        Schedule phantomEvening = scheduleRepository.save(new Schedule(
                phantom,
                now.plusDays(7).withHour(19).withMinute(30).withSecond(0).withNano(0),
                now.plusDays(7).withHour(22).withMinute(0).withSecond(0).withNano(0),
                now
        ));

        Schedule phantomWeekend = scheduleRepository.save(new Schedule(
                phantom,
                now.plusDays(8).withHour(14).withMinute(0).withSecond(0).withNano(0),
                now.plusDays(8).withHour(16).withMinute(30).withSecond(0).withNano(0),
                now
        ));

        Schedule lesMiserablesEvening = scheduleRepository.save(new Schedule(
                lesMiserables,
                now.plusDays(10).withHour(19).withMinute(0).withSecond(0).withNano(0),
                now.plusDays(10).withHour(22).withMinute(0).withSecond(0).withNano(0),
                now
        ));

        saveSeats(phantomEvening, now);
        saveSeats(phantomWeekend, now);
        saveSeats(lesMiserablesEvening, now);
    }

    private void saveSeats(Schedule schedule, LocalDateTime now) {
        seatRepository.saveAll(List.of(
                new Seat(schedule, "A-1", "VIP", 3, now),
                new Seat(schedule, "A-2", "VIP", 11, now),
                new Seat(schedule, "A-3", "VIP", 11, now),
                new Seat(schedule, "B-1", "R", 11, now),
                new Seat(schedule, "B-2", "R", 11, now),
                new Seat(schedule, "B-3", "R", 11, now),
                new Seat(schedule, "C-1", "S", 1, now),
                new Seat(schedule, "C-2", "S", 1, now)
        ));
    }
}
