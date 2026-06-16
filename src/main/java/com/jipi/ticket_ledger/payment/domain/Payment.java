package com.jipi.ticket_ledger.payment.domain;

import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_group_id", unique = true)
    private ReservationGroup reservationGroup;

    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime confirmingAt;

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

    public Payment(ReservationGroup reservationGroup, Integer amount, LocalDateTime now, String orderId) {
        this(reservationGroup, amount, now, orderId, "KRW");
    }

    public Payment(ReservationGroup reservationGroup, Integer amount, LocalDateTime now, String orderId, String currency) {
        this.reservationGroup = reservationGroup;
        this.amount = amount;
        this.status = PaymentStatus.READY;
        this.requestedAt = now;
        this.orderId = orderId;
        this.currency = currency;
    }

    public Integer totalAmountWithVat() {
        int vat = (int) Math.round(this.amount * 0.1d);
        return this.amount + vat;
    }

    public void confirming() {
        if (this.status != PaymentStatus.READY) {
            throw new IllegalStateException("결제 대기 상태에서만 승인 진행 상태로 변경할 수 있습니다.");
        }
        this.status = PaymentStatus.CONFIRMING;
        this.confirmingAt = LocalDateTime.now();
    }

    public void approve(String paymentKey, String method, String pgStatus) {
        if (this.status != PaymentStatus.CONFIRMING) {
            throw new IllegalStateException("승인 진행 상태의 결제만 승인 완료할 수 있습니다.");
        }
        this.paymentKey = paymentKey;
        this.method = method;
        this.pgStatus = pgStatus;
        this.status = PaymentStatus.APPROVED;
        this.approvedAt = LocalDateTime.now();
    }

    public void fail() {
        if (this.status != PaymentStatus.READY && this.status != PaymentStatus.CONFIRMING) {
            throw new IllegalStateException("결제 대기 또는 승인 진행 상태에서만 실패 처리할 수 있습니다.");
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
