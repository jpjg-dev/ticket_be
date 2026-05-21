package com.jipi.ticket_ledger.global.config;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
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

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (eventRepository.count() > 0 || scheduleRepository.count() > 0 || seatRepository.count() > 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime base = now.withMinute(0).withSecond(0).withNano(0);

        Event phantom = createEvent(
                "오페라의 유령",
                "파리 오페라하우스를 배경으로 한 클래식 뮤지컬",
                "블루스퀘어 신한카드홀",
                base.minusDays(45),
                now
        );
        Event lesMiserables = createEvent(
                "레미제라블",
                "혁명과 구원의 감정을 밀도 있게 다루는 대형 뮤지컬",
                "샤롯데씨어터",
                base.minusDays(30),
                now
        );
        Event wicked = createEvent(
                "위키드",
                "초록 마녀와 마법 세계를 중심으로 한 판타지 뮤지컬",
                "예술의전당 오페라극장",
                base.minusDays(14),
                now
        );
        Event chicago = createEvent(
                "시카고",
                "재즈와 쇼맨십이 강한 스테디셀러 뮤지컬",
                "디큐브 링크아트센터",
                base.minusDays(3),
                now
        );
        Event matahari = createEvent(
                "마타하리",
                "전쟁과 무대 사이를 오가는 비극적 인물을 다룬 창작 뮤지컬",
                "세종문화회관 대극장",
                base.minusDays(1),
                now
        );
        Event hadestown = createEvent(
                "하데스타운",
                "신화를 현대적으로 해석한 음악 중심의 뮤지컬",
                "충무아트센터 대극장",
                base.plusDays(3),
                now
        );
        Event kinkyBoots = createEvent(
                "킹키부츠",
                "에너지와 퍼포먼스가 강한 팝 스타일 뮤지컬",
                "LG아트센터 서울",
                base.plusDays(7),
                now
        );

        createSchedulesWithSeats(phantom, now, List.of(
                base.minusDays(20).withHour(19),
                base.minusDays(19).withHour(14),
                base.minusDays(18).withHour(18)
        ));
        createSchedulesWithSeats(lesMiserables, now, List.of(
                base.minusDays(12).withHour(19),
                base.minusDays(11).withHour(15),
                base.minusDays(10).withHour(19)
        ));
        createSchedulesWithSeats(wicked, now, List.of(
                base.minusDays(1).withHour(20),
                base.plusDays(1).withHour(19)
        ));
        createSchedulesWithSeats(chicago, now, List.of(
                base.minusHours(1),
                base.plusDays(2).withHour(15)
        ));
        createSchedulesWithSeats(matahari, now, List.of(
                base.plusDays(1).withHour(20),
                base.plusDays(2).withHour(14)
        ));
        createSchedulesWithSeats(hadestown, now, List.of(
                base.plusDays(5).withHour(19),
                base.plusDays(6).withHour(15)
        ));
        createSchedulesWithSeats(kinkyBoots, now, List.of(
                base.plusDays(9).withHour(19),
                base.plusDays(10).withHour(14)
        ));
    }

    private Event createEvent(String title, String description, String venue, LocalDateTime bookingOpenAt, LocalDateTime now) {
        return eventRepository.save(new Event(
                title,
                description,
                venue,
                bookingOpenAt,
                now
        ));
    }

    private void createSchedulesWithSeats(Event event, LocalDateTime now, List<LocalDateTime> startTimes) {
        for (LocalDateTime startAt : startTimes) {
            Schedule schedule = scheduleRepository.save(new Schedule(
                    event,
                    startAt,
                    startAt.plusHours(2).plusMinutes(30),
                    now
            ));

            saveSeats(schedule, now);
        }
    }

    private void saveSeats(Schedule schedule, LocalDateTime now) {
        seatRepository.saveAll(List.of(
                new Seat(schedule, "A-1", "VIP", 1000, now),
                new Seat(schedule, "A-2", "VIP", 1000, now),
                new Seat(schedule, "A-3", "VIP", 1000, now),
                new Seat(schedule, "B-1", "R", 1000, now),
                new Seat(schedule, "B-2", "R", 1000, now),
                new Seat(schedule, "B-3", "R", 1000, now),
                new Seat(schedule, "C-1", "S", 1000, now),
                new Seat(schedule, "C-2", "S", 1000, now)
        ));
    }
}
