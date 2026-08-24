package com.executorservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.executorservice.entity.JobEntity;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.entity.OutboxEventEntity;
import com.executorservice.enums.FailureCategory;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.http.HttpExecutionResult;
import com.executorservice.observability.ExecutorMetrics;
import com.executorservice.repository.JobRunRepository;
import com.executorservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ExecutionFailureHandlerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    @Mock
    private JobRunRepository jobRunRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventFactory outboxEventFactory;

    @Mock
    private RetryBackoffService retryBackoffService;

    private ExecutionFailureHandler handler;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        handler = new ExecutionFailureHandler(
                jobRunRepository,
                outboxEventRepository,
                outboxEventFactory,
                retryBackoffService,
                "executor-1",
                CLOCK,
                new ExecutorMetrics(meterRegistry)
        );
    }

    @Test
    void retryableFailureWithRetriesRemainingSchedulesRetryWithoutDeadEvent() {
        JobRunEntity run = run(10L, 0, 3);
        when(retryBackoffService.calculateDelay(1, null)).thenReturn(Duration.ofSeconds(2));
        when(jobRunRepository.scheduleRetryAfterFailure(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("executor-1"),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("HTTP 500 returned by target"),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RETRY_SCHEDULED)
        )).thenReturn(1);

        var decision = handler.handleFailure(run, new HttpExecutionResult(false, 500, FailureCategory.RETRYABLE, "HTTP 500 returned by target", null, 20));

        assertThat(decision).isEqualTo(ExecutionFailureHandler.FailureDecision.RETRY_SCHEDULED);
        verify(outboxEventRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonRetryableFailureCreatesDeadEvent() {
        JobRunEntity run = run(11L, 0, 3);
        OutboxEventEntity dead = new OutboxEventEntity();
        dead.setEventId("dead-event");
        when(jobRunRepository.markFailed(
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq("executor-1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("HTTP 404 returned by target"),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.FAILED)
        )).thenReturn(1);
        when(jobRunRepository.findWithJobById(11L)).thenReturn(Optional.of(run));
        when(outboxEventFactory.deadEvent(run, FailureCategory.NON_RETRYABLE, "HTTP 404 returned by target")).thenReturn(dead);
        when(outboxEventRepository.findByEventId("dead-event")).thenReturn(Optional.empty());

        var decision = handler.handleFailure(run, new HttpExecutionResult(false, 404, FailureCategory.NON_RETRYABLE, "HTTP 404 returned by target", null, 20));

        assertThat(decision).isEqualTo(ExecutionFailureHandler.FailureDecision.DEAD);
        verify(outboxEventRepository).save(dead);
    }

    @Test
    void maxRetriesZeroCreatesDeadEventForRetryableInitialFailure() {
        JobRunEntity run = run(12L, 0, 0);
        OutboxEventEntity dead = new OutboxEventEntity();
        dead.setEventId("dead-event");
        when(jobRunRepository.markFailed(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq("executor-1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("HTTP 500 returned by target"),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.FAILED)
        )).thenReturn(1);
        when(jobRunRepository.findWithJobById(12L)).thenReturn(Optional.of(run));
        when(outboxEventFactory.deadEvent(run, FailureCategory.RETRYABLE, "HTTP 500 returned by target")).thenReturn(dead);
        when(outboxEventRepository.findByEventId("dead-event")).thenReturn(Optional.empty());

        var decision = handler.handleFailure(run, new HttpExecutionResult(false, 500, FailureCategory.RETRYABLE, "HTTP 500 returned by target", null, 20));

        assertThat(decision).isEqualTo(ExecutionFailureHandler.FailureDecision.DEAD);
        verify(outboxEventRepository).save(dead);
    }

    private JobRunEntity run(Long runId, int retryCount, int maxRetries) {
        JobEntity job = new JobEntity();
        job.setId(100L);
        job.setMaxRetries(maxRetries);
        JobRunEntity run = new JobRunEntity();
        run.setId(runId);
        run.setJob(job);
        run.setStatus(JobRunStatus.RUNNING);
        run.setRetryCount(retryCount);
        run.setScheduledAt(LocalDateTime.now(CLOCK).minusMinutes(1));
        return run;
    }
}
