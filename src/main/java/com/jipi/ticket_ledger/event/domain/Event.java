package com.jipi.ticket_ledger.event.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 100)
    private String venue;

    @Column(nullable = false)
    private LocalDateTime bookingOpenAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Event(String title, String description, String venue,
                 LocalDateTime bookingOpenAt, LocalDateTime createdAt) {
        this.title = title;
        this.description = description;
        this.venue = venue;
        this.bookingOpenAt = bookingOpenAt;
        this.createdAt = createdAt;
    }

}
