package com.jipi.ticket_ledger.payment.domain;

import com.jipi.ticket_ledger.reservation.domain.Reservation;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", unique = true)
    private Reservation reservation;

    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime canceledAt;

    public Payment(Reservation reservation, Integer amount, LocalDateTime now) {
        this.reservation = reservation;
        this.amount = amount;
        this.status = PaymentStatus.READY;
        this.requestedAt = now;
    }

    public void approve(LocalDateTime approvedAt) {
        if (this.status != PaymentStatus.READY) {
            throw new IllegalStateException("결제 대기 상태에서만 승인할 수 있습니다.");
        }
        this.status = PaymentStatus.APPROVED;
        this.approvedAt = approvedAt;
    }

    public void fail() {
        if (this.status != PaymentStatus.READY) {
            throw new IllegalStateException("결제 대기 상태에서만 실패 처리할 수 있습니다.");
        }
        this.status = PaymentStatus.FAILED;
    }

    public void cancel(LocalDateTime canceledAt) {
        if (this.status != PaymentStatus.APPROVED) {
            throw new IllegalStateException("승인된 결제만 취소할 수 있습니다.");
        }
        this.status = PaymentStatus.CANCELED;
        this.canceledAt = canceledAt;
    }
}
