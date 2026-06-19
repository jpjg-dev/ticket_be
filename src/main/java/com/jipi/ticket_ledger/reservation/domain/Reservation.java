package com.jipi.ticket_ledger.reservation.domain;

import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_group_id")
    private ReservationGroup reservationGroup;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private Instant reservedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant canceledAt;

    public Reservation(User user, Seat seat, ReservationGroup reservationGroup, Instant now, Instant expiresAt) {
        this.user = user;
        this.seat = seat;
        this.reservationGroup = reservationGroup;
        this.status = ReservationStatus.PENDING;
        this.reservedAt = now;
        this.expiresAt = expiresAt;
    }

    public Reservation(User user, Seat seat, ReservationGroup reservationGroup, LocalDateTime now, LocalDateTime expiresAt) {
        this(
                user,
                seat,
                reservationGroup,
                now.atZone(ZoneId.systemDefault()).toInstant(),
                expiresAt.atZone(ZoneId.systemDefault()).toInstant()
        );
    }

    public Reservation(User user, Seat seat, ReservationGroup reservationGroup, LocalDateTime now, Instant expiresAt) {
        this(user, seat, reservationGroup, now.atZone(ZoneId.systemDefault()).toInstant(), expiresAt);
    }

    public void confirm() {
        if (this.status != ReservationStatus.PENDING) throw new IllegalStateException("진행 중이거나 예매가 확정된 상태입니다.");
        this.status = ReservationStatus.CONFIRMED;
    }

    public void cancel() {
        if (this.status != ReservationStatus.PENDING && this.status != ReservationStatus.CONFIRMED) throw new IllegalStateException("진행 중이거나 확정된 예매만 취소할 수 있습니다.");
        this.status = ReservationStatus.CANCELED;
        this.canceledAt = Instant.now();
    }

    public void expire() {
        if (this.status != ReservationStatus.PENDING) throw new IllegalStateException("대기 상태의 예매만 만료 처리할 수 있습니다.");
        this.status = ReservationStatus.EXPIRED;
    }

    public boolean isPending() {
        return this.status == ReservationStatus.PENDING;
    }

    //만료여부 확인
    public boolean isExpiredAt(Instant now) {
        return !this.expiresAt.isAfter(now);
    }
}
