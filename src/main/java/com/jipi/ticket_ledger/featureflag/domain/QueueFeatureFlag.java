package com.jipi.ticket_ledger.featureflag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "queue_feature_flags")
public class QueueFeatureFlag {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_mode", nullable = false, length = 16)
    private QueueMode queueMode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "version", nullable = false)
    private Long version;

    public QueueFeatureFlag(QueueMode queueMode, Instant updatedAt) {
        this.id = SINGLETON_ID;
        this.queueMode = queueMode;
        this.updatedAt = updatedAt;
        this.version = 0L;
    }

    public QueueModeSnapshot snapshot() {
        return new QueueModeSnapshot(queueMode, version);
    }
}
