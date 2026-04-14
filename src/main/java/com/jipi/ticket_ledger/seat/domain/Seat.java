package com.jipi.ticket_ledger.seat.domain;

import com.jipi.ticket_ledger.event.domain.Schedule;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Column(nullable = false, length = 20)
    private String seatNumber;

    @Column(nullable = false, length = 20)
    private String grade;

    @Column(nullable = false)
    private Integer price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Seat(Schedule schedule, String seatNumber, String grade, Integer price, LocalDateTime createdAt) {
        this.schedule = schedule;
        this.seatNumber = seatNumber;
        this.grade = grade;
        this.price = price;
        this.status = SeatStatus.AVAILABLE;
        this.createdAt = createdAt;
    }

    public void hold(){
        if(this.status != SeatStatus.AVAILABLE) throw  new IllegalStateException("선택 가능한 좌석만 예약 가능합니다.");
        this.status = SeatStatus.HELD;
    }

    public void book(){
        if(this.status != SeatStatus.HELD) throw new IllegalStateException("예약된 좌석만 예매 확정할 수 있습니다.");
        this.status = SeatStatus.BOOKED;
    }

    public void release() {
        if (this.status != SeatStatus.HELD) throw new IllegalStateException("예약된 좌석만 해제할 수 있습니다.");
        this.status = SeatStatus.AVAILABLE;
    }

    public void releaseBooked() {
        if (this.status != SeatStatus.BOOKED) {
            throw new IllegalStateException("확정된 좌석만 취소 후 복구할 수 있습니다.");
        }
        this.status = SeatStatus.AVAILABLE;
    }
}
