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

    public Seat(Schedule schedule, String seatNumber, String grade,
                Integer price, SeatStatus status, LocalDateTime createdAt) {
        this.schedule = schedule;
        this.seatNumber = seatNumber;
        this.grade = grade;
        this.price = price;
        this.status = status;
        this.createdAt = createdAt;
    }
}
