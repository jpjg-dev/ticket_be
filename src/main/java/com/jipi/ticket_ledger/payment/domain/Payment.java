package com.jipi.ticket_ledger.payment.domain;

import com.jipi.ticket_ledger.reservation.domain.Reservation;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_reservation", columnNames = "reservation_id")
        }
)
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

    @Column(nullable = false, unique = true, length = 100)
    private String orderId;

    @Column(unique = true, length = 200)
    private String paymentKey;

    @Column(length = 50)
    private String pgStatus;

    @Column(length = 50)
    private String method;

    @Column(nullable = false, length = 10)
    private String currency;

    public Payment(Reservation reservation, Integer amount, LocalDateTime now, String orderId) {
        this(reservation, amount, now, orderId, "KRW");
    }

    public Payment(Reservation reservation, Integer amount, LocalDateTime now,String orderId, String currency) {
        this.reservation = reservation;
        this.amount = amount;
        this.status = PaymentStatus.READY;
        this.requestedAt = now;
        this.orderId = orderId;
        this.currency = currency;
    }

    public void approve(String paymentKey, String method, String pgStatus) {
        if (this.status != PaymentStatus.READY) {
            throw new IllegalStateException("결제 대기 상태에서만 승인할 수 있습니다.");
        }
        this.paymentKey = paymentKey;
        this.method = method;
        this.pgStatus = pgStatus;
        this.status = PaymentStatus.APPROVED;
        this.approvedAt = LocalDateTime.now();
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
