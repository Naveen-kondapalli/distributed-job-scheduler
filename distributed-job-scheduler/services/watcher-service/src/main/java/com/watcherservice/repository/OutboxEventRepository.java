package com.watcherservice.repository;

import com.watcherservice.entity.OutboxEventEntity;
import com.watcherservice.enums.OutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    long countByStatus(OutboxStatus status);

    @Query(
            value = """
                    SELECT *
                    FROM outbox_events
                    WHERE (
                        status = 'PENDING'
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                        AND attempt_count < :maxAttempts
                    )
                    OR (
                        status = 'PROCESSING'
                        AND processing_started_at < :staleBefore
                        AND attempt_count < :maxAttempts
                    )
                    ORDER BY created_at ASC, id ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<OutboxEventEntity> findPublishableForUpdateSkipLocked(
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit
    );

    @Modifying
    @Query("""
            update OutboxEventEntity e
            set e.status = :failedStatus,
                e.processingStartedAt = null,
                e.lastError = :lastError
            where e.status = :processingStatus
              and e.processingStartedAt < :staleBefore
              and e.attemptCount >= :maxAttempts
            """)
    int markStaleProcessingMaxAttemptsFailed(
            @Param("processingStatus") OutboxStatus processingStatus,
            @Param("failedStatus") OutboxStatus failedStatus,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("lastError") String lastError
    );

    @Modifying
    @Query("delete from OutboxEventEntity e where e.status = :status and e.publishedAt < :publishedBefore")
    int deletePublishedOlderThan(
            @Param("status") OutboxStatus status,
            @Param("publishedBefore") LocalDateTime publishedBefore
    );
}
