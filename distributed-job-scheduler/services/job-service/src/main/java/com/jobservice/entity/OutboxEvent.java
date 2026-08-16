package com.jobservice.entity;

import com.jobservice.enums.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "outbox_events",
        indexes = {
                @Index(name = "uk_outbox_events_event_id", columnList = "event_id", unique = true),
                @Index(name = "idx_outbox_events_status_next_attempt_at", columnList = "status, next_attempt_at"),
                @Index(name = "idx_outbox_events_status_created_at", columnList = "status, created_at")
        }
)
public class OutboxEvent extends BaseEntity {

    @Column(name = "event_id", length = 36, nullable = false, unique = true)
    private String eventId;

    @Column(name = "aggregate_type", length = 100, nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "topic", length = 100, nullable = false)
    private String topic;

    @Column(name = "message_key", length = 100, nullable = false)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
