package com.executorservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.executorservice.entity.JobEntity;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.entity.OutboxEventEntity;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.repository.JobRunRepository;
import com.executorservice.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RetrySchedulingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    @Mock
    private JobRunRepository jobRunRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventFactory outboxEventFactory;

    private RetrySchedulingService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RetrySchedulingService(jobRunRepository, outboxEventRepository, outboxEventFactory, CLOCK);
    }

    @Test
    void dueRetryCreatesRetryOutboxEventAndClearsNextRetryAt() {
        JobRunEntity run = run();
        OutboxEventEntity outbox = new OutboxEventEntity();
        outbox.setEventId("retry-event");
        when(jobRunRepository.findDueRetriesForUpdateSkipLocked(LocalDateTime.now(CLOCK), 100)).thenReturn(List.of(run));
        when(outboxEventFactory.retryEvent(org.mockito.ArgumentMatchers.eq(run), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.any()))
                .thenReturn(outbox);
        when(outboxEventRepository.findByEventId("retry-event")).thenReturn(Optional.empty());

        int count = service.publishDueRetries(100);

        assertThat(count).isEqualTo(1);
        assertThat(run.getNextRetryAt()).isNull();
        verify(outboxEventRepository).save(outbox);
    }

    private JobRunEntity run() {
        JobEntity job = new JobEntity();
        job.setId(100L);
        JobRunEntity run = new JobRunEntity();
        run.setId(10L);
        run.setJob(job);
        run.setStatus(JobRunStatus.RETRY_SCHEDULED);
        run.setRetryCount(1);
        run.setScheduledAt(LocalDateTime.now(CLOCK).minusMinutes(1));
        run.setNextRetryAt(LocalDateTime.now(CLOCK));
        return run;
    }
}
