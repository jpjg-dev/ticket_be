package com.jipi.ticket_ledger.reservation.domain;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReservationTest {
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    @Test
    @DisplayName("cancel: 전달받은 시각으로 예매를 취소한다")
    void cancelRecordsProvidedInstant() {
        Reservation reservation = reservation();

        reservation.cancel(NOW);

        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(NOW, reservation.getCanceledAt());
    }

    @Test
    @DisplayName("cancel: null 시각이면 예매 상태를 변경하지 않는다")
    void cancelRejectsNullWithoutMutation() {
        Reservation reservation = reservation();

        assertThrows(NullPointerException.class, () -> reservation.cancel(null));

        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertNull(reservation.getCanceledAt());
    }

    private Reservation reservation() {
        User user = new User("user@test.com", "password", "테스터", NOW);
        Event event = new Event("공연", "설명", "장소", NOW, NOW);
        Schedule schedule = new Schedule(
                event,
                LocalDateTime.of(2026, 9, 10, 19, 0),
                LocalDateTime.of(2026, 9, 10, 21, 0),
                NOW
        );
        Seat seat = new Seat(schedule, "A-1", "VIP", 10000, NOW);
        ReservationGroup group = new ReservationGroup(user, NOW, NOW.plusSeconds(300));
        return new Reservation(user, seat, group, NOW, NOW.plusSeconds(300));
    }
}
