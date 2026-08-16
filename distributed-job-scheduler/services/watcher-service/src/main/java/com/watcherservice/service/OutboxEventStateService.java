package com.watcherservice.service;

import com.watcherservice.entity.OutboxEventEntity;
import com.watcherservice.enums.OutboxStatus;
import com.watcherservice.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxEventStateService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    @Transactional
    public List<OutboxEventEntity> claimPublishableEvents(int batchSize, int maxAttempts, long processingTimeoutMs) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime staleBefore = now.minusNanos(processingTimeoutMs * 1_000_000);
        outboxEventRepository.markStaleProcessingMaxAttemptsFailed(
                OutboxStatus.PROCESSING,
                OutboxStatus.FAILED,
                staleBefore,
                maxAttempts,
                "Outbox publishing exhausted configured attempts before recovery"
        );
        List<OutboxEventEntity> events = outboxEventRepository.findPublishableForUpdateSkipLocked(
                now,
                staleBefore,
                maxAttempts,
                batchSize
        );

        events.forEach(event -> {
            event.setStatus(OutboxStatus.PROCESSING);
            event.setAttemptCount(event.getAttemptCount() + 1);
            event.setProcessingStartedAt(now);
            event.setLastError(null);
        });

        return events;
    }

    @Transactional
    public void markPublished(Long eventRowId) {
        OutboxEventEntity event = outboxEventRepository.findById(eventRowId).orElseThrow();
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(LocalDateTime.now(clock));
        event.setNextAttemptAt(null);
        event.setProcessingStartedAt(null);
        event.setLastError(null);
    }

    @Transactional
    public OutboxEventEntity markFailedOrRetryable(
            Long eventRowId,
            Throwable failure,
            long baseDelayMs,
            long maxDelayMs,
            int maxAttempts
    ) {
        OutboxEventEntity event = outboxEventRepository.findById(eventRowId).orElseThrow();
        event.setProcessingStartedAt(null);
        event.setLastError(sanitizeError(failure));

        if (event.getAttemptCount() >= maxAttempts) {
            event.setStatus(OutboxStatus.FAILED);
            event.setNextAttemptAt(null);
            return event;
        }

        event.setStatus(OutboxStatus.PENDING);
        event.setNextAttemptAt(LocalDateTime.now(clock).plusNanos(calculateBackoffMs(
                event.getAttemptCount(),
                baseDelayMs,
                maxDelayMs
        ) * 1_000_000));
        return event;
    }

    @Transactional
    public int cleanupPublishedOlderThan(int retentionDays) {
        LocalDateTime publishedBefore = LocalDateTime.now(clock).minusDays(retentionDays);
        return outboxEventRepository.deletePublishedOlderThan(OutboxStatus.PUBLISHED, publishedBefore);
    }

    private long calculateBackoffMs(int attemptCount, long baseDelayMs, long maxDelayMs) {
        long multiplier = 1L << Math.min(Math.max(attemptCount - 1, 0), 30);
        long delay = baseDelayMs * multiplier;
        if (delay < 0) {
            return maxDelayMs;
        }
        return Math.min(delay, maxDelayMs);
    }

    private String sanitizeError(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
