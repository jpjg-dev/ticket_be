package com.jipi.ticket_ledger.reservation.domain;

import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private LocalDateTime reservedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime canceledAt;

    public Reservation(User user, Seat seat, LocalDateTime now) {
        this.user = user;
        this.seat = seat;
        this.status = ReservationStatus.PENDING;
        this.reservedAt = now;
        this.expiresAt = now.plusMinutes(5);
    }
}
