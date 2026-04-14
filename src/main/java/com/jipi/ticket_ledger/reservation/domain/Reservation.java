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

    public void confirm() {
        if (this.status != ReservationStatus.PENDING) throw new IllegalStateException("진행 중이거나 예매가 확정된 상태입니다.");
        this.status = ReservationStatus.CONFIRMED;
    }

    public void cancel(LocalDateTime canceledAt) {
        if (this.status != ReservationStatus.PENDING && this.status != ReservationStatus.CONFIRMED) throw new IllegalStateException("진행 중이거나 확정된 예매만 취소할 수 있습니다.");
        this.status = ReservationStatus.CANCELED;
        this.canceledAt = canceledAt;
    }

    public void expire() {
        if (this.status != ReservationStatus.PENDING) throw new IllegalStateException("대기 상태의 예매만 만료 처리할 수 있습니다.");
        this.status = ReservationStatus.EXPIRED;
    }
}
