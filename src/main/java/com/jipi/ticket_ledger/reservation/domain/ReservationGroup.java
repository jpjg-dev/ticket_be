package com.jipi.ticket_ledger.reservation.domain;

import com.jipi.ticket_ledger.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "reservation_groups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationGroup {
    public static final int MAX_SEAT_COUNT = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationGroupStatus status;

    public ReservationGroup(User user, LocalDateTime now, LocalDateTime expiresAt) {
        this.user = user;
        this.createdAt = now;
        this.expiresAt = expiresAt;
        this.status = ReservationGroupStatus.PENDING;
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return !this.expiresAt.isAfter(now);
    }

    public void confirm() {
        if (this.status != ReservationGroupStatus.PENDING) {
            throw new IllegalStateException("대기 상태의 예매 묶음만 확정할 수 있습니다.");
        }
        this.status = ReservationGroupStatus.CONFIRMED;
    }

    public void cancel() {
        if (this.status != ReservationGroupStatus.CONFIRMED) {
            throw new IllegalStateException("확정된 예매 묶음만 취소할 수 있습니다.");
        }
        this.status = ReservationGroupStatus.CANCELED;
    }

    public void expire() {
        if (this.status != ReservationGroupStatus.PENDING) {
            throw new IllegalStateException("대기 상태의 예매 묶음만 만료 처리할 수 있습니다.");
        }
        this.status = ReservationGroupStatus.EXPIRED;
    }
}
