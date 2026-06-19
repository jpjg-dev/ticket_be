package com.jipi.ticket_ledger.event.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

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

    // 예매 오픈은 "전 세계 동일한 한 순간"이라 절대 시점(Instant)으로 둔다(타임존 무관).
    // 공연 시작/종료(Schedule)는 공연장 로컬 벽시계라 LocalDateTime 으로 두는 것과 대비된다.
    @Column(nullable = false)
    private Instant bookingOpenAt;

    @Column(nullable = false)
    private Instant createdAt;

    public Event(String title, String description, String venue,
                 Instant bookingOpenAt, Instant createdAt) {
        this.title = title;
        this.description = description;
        this.venue = venue;
        this.bookingOpenAt = bookingOpenAt;
        this.createdAt = createdAt;
    }

    // 테스트/시드 편의: 로컬 시각을 서비스 타임존(systemDefault) 기준 Instant 로 변환한다.
    public Event(String title, String description, String venue,
                 LocalDateTime bookingOpenAt, LocalDateTime createdAt) {
        this(title, description, venue,
                bookingOpenAt.atZone(ZoneId.systemDefault()).toInstant(),
                createdAt.atZone(ZoneId.systemDefault()).toInstant());
    }

}
