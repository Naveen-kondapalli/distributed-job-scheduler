package com.watcherservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.watcherservice.entity.OutboxEventEntity;
import com.watcherservice.enums.OutboxStatus;
import com.watcherservice.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxEventStateServiceTest {

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-12T10:15:30Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 15, 45, 30);

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxEventStateService outboxEventStateService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, APPLICATION_ZONE);
        outboxEventStateService = new OutboxEventStateService(outboxEventRepository, clock);
    }

    @Test
    void claimPublishableEventsMarksPendingEventsProcessingAndIncrementsAttempts() {
        OutboxEventEntity event = event(1L, OutboxStatus.PENDING, 0);
        when(outboxEventRepository.findPublishableForUpdateSkipLocked(
                NOW,
                NOW.minusSeconds(30),
                10,
                100
        )).thenReturn(List.of(event));

        List<OutboxEventEntity> claimed = outboxEventStateService.claimPublishableEvents(100, 10, 30000);

        assertThat(claimed).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getProcessingStartedAt()).isEqualTo(NOW);
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void markPublishedStoresPublishedAtAndClearsRetryFields() {
        OutboxEventEntity event = event(1L, OutboxStatus.PROCESSING, 1);
        event.setLastError("old");
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));

        outboxEventStateService.markPublished(1L);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        assertThat(event.getLastError()).isNull();
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getProcessingStartedAt()).isNull();
    }

    @Test
    void markFailedOrRetryableSchedulesRetryWithExponentialBackoff() {
        OutboxEventEntity event = event(1L, OutboxStatus.PROCESSING, 2);
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));

        outboxEventStateService.markFailedOrRetryable(1L, new IllegalStateException("kafka down"), 1000, 30000, 10);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getLastError()).isEqualTo("kafka down");
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(event.getProcessingStartedAt()).isNull();
    }

    @Test
    void markFailedOrRetryableMarksFailedWhenMaxAttemptsExhausted() {
        OutboxEventEntity event = event(1L, OutboxStatus.PROCESSING, 10);
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));

        outboxEventStateService.markFailedOrRetryable(1L, new IllegalStateException("kafka down"), 1000, 30000, 10);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("kafka down");
    }

    private OutboxEventEntity event(Long id, OutboxStatus status, int attemptCount) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(id);
        event.setStatus(status);
        event.setAttemptCount(attemptCount);
        return event;
    }
}
