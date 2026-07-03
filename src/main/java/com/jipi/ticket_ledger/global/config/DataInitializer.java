package com.jipi.ticket_ledger.global.config;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {
    private static final String PERFORMANCE_USER_EMAIL = "test1@email.com";
    private static final int MYPAGE_PERFORMANCE_GROUP_COUNT = 100;
    private static final int MYPAGE_PERFORMANCE_SEATS_PER_GROUP = 2;

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationGroupRepository reservationGroupRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (eventRepository.count() > 0 || scheduleRepository.count() > 0 || seatRepository.count() > 0) {
            return;
        }

        Instant now = Instant.now();
        // 데모 시각은 기동 시점 기준 상대 분배(R__demo_schedule_times.sql / V9 마이그레이션과 동일 offset). KST 자정 기준 + 자연 시각.
        // 빈 DB의 데모 시각은 여기서 채우고, 이미 시드된 DB는 R__가 재적용한다. 두 곳 offset은 항상 함께 맞춘다.
        // 회차는 공연별 '연속 런'(인접 날짜/같은 날 다른 시각), 공연들끼리는 시작 시기를 다양하게 둔다.
        LocalDateTime base = LocalDateTime.now(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay();

        // 예매 오픈: 절대시각(now 기준). 5개는 이미 오픈(과거), 2개는 오픈 예정(미래).
        Event phantom = createEvent(
                "오페라의 유령",
                "파리 오페라하우스를 배경으로 한 클래식 뮤지컬",
                "블루스퀘어 신한카드홀",
                now.minus(Duration.ofDays(25)),
                now
        );
        Event lesMiserables = createEvent(
                "레미제라블",
                "혁명과 구원의 감정을 밀도 있게 다루는 대형 뮤지컬",
                "샤롯데씨어터",
                now.minus(Duration.ofDays(12)),
                now
        );
        Event wicked = createEvent(
                "위키드",
                "초록 마녀와 마법 세계를 중심으로 한 판타지 뮤지컬",
                "예술의전당 오페라극장",
                now.minus(Duration.ofDays(8)),
                now
        );
        Event chicago = createEvent(
                "시카고",
                "재즈와 쇼맨십이 강한 스테디셀러 뮤지컬",
                "디큐브 링크아트센터",
                now.minus(Duration.ofDays(6)),
                now
        );
        Event matahari = createEvent(
                "마타하리",
                "전쟁과 무대 사이를 오가는 비극적 인물을 다룬 창작 뮤지컬",
                "세종문화회관 대극장",
                now.minus(Duration.ofDays(4)),
                now
        );
        Event hadestown = createEvent(
                "하데스타운",
                "신화를 현대적으로 해석한 음악 중심의 뮤지컬",
                "충무아트센터 대극장",
                now.plus(Duration.ofDays(2)),
                now
        );
        Event kinkyBoots = createEvent(
                "킹키부츠",
                "에너지와 퍼포먼스가 강한 팝 스타일 뮤지컬",
                "LG아트센터 서울",
                now.plus(Duration.ofDays(5)),
                now
        );

        // 공연 회차: 공연별 연속 런. 오페라는 이미 시작한 런(첫 회차 과거 → 필터 시연).
        createSchedulesWithSeats(phantom, now, List.of(
                base.plusDays(-1).plusHours(19),
                base.plusDays(1).plusHours(19),
                base.plusDays(2).plusHours(14)
        ));
        createSchedulesWithSeats(lesMiserables, now, List.of(
                base.plusDays(3).plusHours(19),
                base.plusDays(4).plusHours(15),
                base.plusDays(4).plusHours(19)
        ));
        createSchedulesWithSeats(wicked, now, List.of(
                base.plusDays(6).plusHours(20),
                base.plusDays(7).plusHours(19)
        ));
        createSchedulesWithSeats(chicago, now, List.of(
                base.plusDays(10).plusHours(15),
                base.plusDays(10).plusHours(19)
        ));
        createSchedulesWithSeats(matahari, now, List.of(
                base.plusDays(15).plusHours(19),
                base.plusDays(16).plusHours(19)
        ));
        createSchedulesWithSeats(hadestown, now, List.of(
                base.plusDays(25).plusHours(19),
                base.plusDays(26).plusHours(14)
        ));
        createSchedulesWithSeats(kinkyBoots, now, List.of(
                base.plusDays(40).plusHours(19),
                base.plusDays(41).plusHours(15)
        ));

        createReservationAndPayments(now);
        createMyPagePerformanceSeed(now);
    }

    private Event createEvent(String title, String description, String venue, Instant bookingOpenAt, Instant now) {
        return eventRepository.save(new Event(
                title,
                description,
                venue,
                bookingOpenAt,
                now
        ));
    }

    private void createSchedulesWithSeats(Event event, Instant now, List<LocalDateTime> startTimes) {
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

    private void saveSeats(Schedule schedule, Instant now) {
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

    private void createReservationAndPayments(Instant now) {
        List<User> users = createSeedUsers(now, 100);

        List<Seat> allSeats = seatRepository.findAll();
        if (allSeats.isEmpty()) {
            throw new IllegalStateException("좌석 데이터가 없습니다.");
        }

        List<GroupSeed> confirmedSeeds = buildSeeds(ReservationGroupStatus.CONFIRMED, 30, 30);
        List<GroupSeed> canceledSeeds = buildSeeds(ReservationGroupStatus.CANCELED, 10, 10);
        List<GroupSeed> expiredSeeds = buildSeeds(ReservationGroupStatus.EXPIRED, 10, 10);

        List<Seat> confirmedSeatPool = new ArrayList<>(allSeats);
        int[] confirmedCursor = {0};
        Set<Long> bookedSeatIds = new HashSet<>();
        int orderSequence = 1;
        int[] userCursor = {0};

        for (GroupSeed seed : confirmedSeeds) {
            User user = nextUser(users, userCursor);
            orderSequence = createGroupWithStatus(user, seed, confirmedSeatPool, confirmedCursor, bookedSeatIds, now, orderSequence);
        }

        List<Seat> reusableSeatPool = new ArrayList<>();
        for (Seat seat : allSeats) {
            if (!bookedSeatIds.contains(seat.getId())) {
                reusableSeatPool.add(seat);
            }
        }
        int[] reusableCursor = {0};

        for (GroupSeed seed : canceledSeeds) {
            User user = nextUser(users, userCursor);
            orderSequence = createGroupWithStatus(user, seed, reusableSeatPool, reusableCursor, null, now, orderSequence);
        }
        for (GroupSeed seed : expiredSeeds) {
            User user = nextUser(users, userCursor);
            orderSequence = createGroupWithStatus(user, seed, reusableSeatPool, reusableCursor, null, now, orderSequence);
        }
    }

    private void createMyPagePerformanceSeed(Instant now) {
        if (!isDevProfile()) {
            return;
        }

        User user = userRepository.findByEmail(PERFORMANCE_USER_EMAIL)
                .orElseThrow(() -> new IllegalStateException("마이페이지 성능 테스트 사용자 데이터가 없습니다."));

        int existingConfirmedGroups = (int) reservationGroupRepository.findAll().stream()
                .filter(group -> group.getUser().getId().equals(user.getId()))
                .filter(group -> group.getStatus() == ReservationGroupStatus.CONFIRMED)
                .count();
        int groupsToCreate = Math.max(MYPAGE_PERFORMANCE_GROUP_COUNT - existingConfirmedGroups, 0);
        if (groupsToCreate == 0) {
            return;
        }

        Event event = createEvent(
                "[성능테스트] 마이페이지 이력",
                "마이페이지 조회 성능 측정을 위한 개발 환경 전용 데이터",
                "Performance Test Hall",
                Instant.parse("2026-05-01T01:00:00Z"),
                now
        );
        Schedule schedule = scheduleRepository.save(new Schedule(
                event,
                LocalDateTime.of(2026, 12, 31, 19, 0),
                LocalDateTime.of(2026, 12, 31, 21, 30),
                now
        ));

        for (int i = 1; i <= groupsToCreate; i++) {
            int sequence = existingConfirmedGroups + i;
            ReservationGroup group = reservationGroupRepository.save(new ReservationGroup(user, now, now.plus(Duration.ofMinutes(10))));
            List<Seat> seats = createPerformanceSeats(schedule, sequence, now);

            int amount = 0;
            List<Reservation> reservations = new ArrayList<>();
            for (Seat seat : seats) {
                seat.hold();
                seat.book();
                Reservation reservation = new Reservation(user, seat, group, now, now.plus(Duration.ofMinutes(10)));
                reservation.confirm();
                reservations.add(reservation);
                amount += seat.getPrice();
            }
            reservationRepository.saveAll(reservations);

            Payment payment = paymentRepository.save(new Payment(group, amount, now, "perf-mypage-order-" + sequence));
            payment.confirming();
            payment.approve("perf-mypage-paykey-" + sequence, "CARD", "DONE");
            group.confirm();
        }
    }

    private List<Seat> createPerformanceSeats(Schedule schedule, int groupSequence, Instant now) {
        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= MYPAGE_PERFORMANCE_SEATS_PER_GROUP; i++) {
            seats.add(new Seat(schedule, "P-" + groupSequence + "-" + i, "R", 1000, now));
        }
        return seatRepository.saveAll(seats);
    }

    private boolean isDevProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }

    private List<User> createSeedUsers(Instant now, int count) {
        String encodedPassword = passwordEncoder.encode("a123456789");
        List<User> users = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            users.add(new User(
                    "test" + i + "@email.com",
                    encodedPassword,
                    "user" + i,
                    now
            ));
        }
        return userRepository.saveAll(users);
    }

    private User nextUser(List<User> users, int[] cursor) {
        User user = users.get(cursor[0] % users.size());
        cursor[0]++;
        return user;
    }

    private List<GroupSeed> buildSeeds(ReservationGroupStatus status, int twoSeatCount, int oneSeatCount) {
        List<GroupSeed> seeds = new ArrayList<>();
        for (int i = 0; i < twoSeatCount; i++) {
            seeds.add(new GroupSeed(status, 2));
        }
        for (int i = 0; i < oneSeatCount; i++) {
            seeds.add(new GroupSeed(status, 1));
        }
        return seeds;
    }

    private int createGroupWithStatus(
            User user,
            GroupSeed seed,
            List<Seat> seatPool,
            int[] cursor,
            Set<Long> bookedSeatIds,
            Instant now,
            int orderSequence
    ) {
        Instant expiresAt = seed.status == ReservationGroupStatus.EXPIRED
                ? now.minus(Duration.ofMinutes(1))
                : now.plus(Duration.ofMinutes(10));

        ReservationGroup group = reservationGroupRepository.save(new ReservationGroup(user, now, expiresAt));
        List<Seat> seatsForGroup = new ArrayList<>();
        for (int i = 0; i < seed.seatCount; i++) {
            seatsForGroup.add(nextSeat(seatPool, cursor, bookedSeatIds));
        }

        int amount = 0;
        List<Reservation> reservations = new ArrayList<>();
        for (Seat seat : seatsForGroup) {
            seat.hold();
            Reservation reservation = new Reservation(user, seat, group, now, expiresAt);
            reservations.add(reservation);
            amount += seat.getPrice();
        }
        reservationRepository.saveAll(reservations);

        String orderId = "order-" + orderSequence++;
        Payment payment = paymentRepository.save(new Payment(group, amount, now, orderId));

        if (seed.status == ReservationGroupStatus.CONFIRMED) {
            for (Seat seat : seatsForGroup) {
                seat.book();
                if (bookedSeatIds != null) {
                    bookedSeatIds.add(seat.getId());
                }
            }
            payment.confirming();
            payment.approve("paykey-" + orderId, "CARD", "DONE");
            group.confirm();
            for (Reservation reservation : reservations) {
                reservation.confirm();
            }
        } else if (seed.status == ReservationGroupStatus.CANCELED) {
            for (Seat seat : seatsForGroup) {
                seat.book();
            }
            payment.confirming();
            payment.approve("paykey-" + orderId, "CARD", "DONE");
            group.confirm();
            for (Reservation reservation : reservations) {
                reservation.confirm();
            }

            payment.cancel(now);
            for (Reservation reservation : reservations) {
                reservation.cancel();
            }
            group.cancel();
            for (Seat seat : seatsForGroup) {
                seat.releaseBooked();
            }
        } else {
            payment.fail();
            for (Reservation reservation : reservations) {
                reservation.expire();
            }
            group.expire();
            for (Seat seat : seatsForGroup) {
                seat.release();
            }
        }

        return orderSequence;
    }

    private Seat nextSeat(List<Seat> seatPool, int[] cursor, Set<Long> bookedSeatIds) {
        int attempts = 0;
        while (attempts < seatPool.size()) {
            Seat seat = seatPool.get(cursor[0] % seatPool.size());
            cursor[0]++;
            attempts++;
            if (bookedSeatIds == null || !bookedSeatIds.contains(seat.getId())) {
                return seat;
            }
        }
        throw new IllegalStateException("좌석이 부족합니다.");
    }

    private static class GroupSeed {
        private final ReservationGroupStatus status;
        private final int seatCount;

        private GroupSeed(ReservationGroupStatus status, int seatCount) {
            this.status = status;
            this.seatCount = seatCount;
        }
    }
}
