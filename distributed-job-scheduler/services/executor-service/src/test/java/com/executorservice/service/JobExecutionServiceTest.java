package com.executorservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.cancellation.ExecutorCancellationService;
import com.executorservice.entity.JobEntity;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.FailureCategory;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.enums.JobType;
import com.executorservice.http.HttpExecutionResult;
import com.executorservice.http.HttpJobExecutor;
import com.executorservice.observability.ExecutorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

class JobExecutionServiceTest {

    @Mock
    private JobRunClaimService claimService;

    @Mock
    private JobRunCompletionService completionService;

    @Mock
    private ExecutionFailureHandler failureHandler;

    @Mock
    private HttpJobExecutor httpJobExecutor;

    @Mock
    private ExecutorCancellationService cancellationService;

    private JobExecutionService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        service = new JobExecutionService(
                claimService,
                completionService,
                failureHandler,
                httpJobExecutor,
                cancellationService,
                new ObjectMapper(),
                "executor-1",
                new ExecutorMetrics(meterRegistry)
        );
    }

    @Test
    void cancelledQueuedEventIsAcknowledgedAndNotExecuted() {
        JobRunQueuedEvent event = event(56L, 10L);
        JobRunEntity run = run(56L, 10L, JobRunStatus.CANCELLED, Map.of());
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL, run, JobRunStatus.CANCELLED));

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(httpJobExecutor, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(meterRegistry.counter("scheduler.executor.kafka.duplicate", "topic", "run").count()).isEqualTo(1.0);
    }

    @Test
    void cancellationRequestedBeforeHttpMarksCancelledAndSkipsHttp() {
        JobRunQueuedEvent event = event(57L, 10L);
        JobRunEntity run = run(57L, 10L, JobRunStatus.RUNNING, Map.of("method", "GET", "url", "https://jsonplaceholder.typicode.com/posts/1"));
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.CLAIMED, run, JobRunStatus.RUNNING));
        when(cancellationService.completeIfCancellationRequested(57L)).thenReturn(true);

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(httpJobExecutor, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        verify(completionService, never()).markSuccess(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cancellationRequestedAfterHttpPreventsSuccessOverwrite() {
        JobRunQueuedEvent event = event(58L, 10L);
        JobRunEntity run = run(58L, 10L, JobRunStatus.RUNNING, Map.of("method", "GET", "url", "https://jsonplaceholder.typicode.com/posts/1"));
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.CLAIMED, run, JobRunStatus.RUNNING));
        when(cancellationService.completeIfCancellationRequested(58L)).thenReturn(false, true);
        when(httpJobExecutor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(58L)))
                .thenReturn(new HttpExecutionResult(true, 200, null, null, null, 25));

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(completionService, never()).markSuccess(58L);
    }

    @Test
    void successfulHttpJobMarksSuccess() {
        JobRunQueuedEvent event = event(50L, 10L);
        JobRunEntity run = run(50L, 10L, JobRunStatus.RUNNING, Map.of("method", "GET", "url", "https://jsonplaceholder.typicode.com/posts/1"));
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.CLAIMED, run, JobRunStatus.RUNNING));
        when(httpJobExecutor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50L)))
                .thenReturn(new HttpExecutionResult(true, 200, null, null, null, 25));

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(completionService).markSuccess(50L);
        verify(completionService, never()).markFailed(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void http500MarksFailedWithoutRetry() {
        JobRunQueuedEvent event = event(51L, 10L);
        JobRunEntity run = run(51L, 10L, JobRunStatus.RUNNING, Map.of("method", "GET", "url", "https://jsonplaceholder.typicode.com/posts/1"));
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.CLAIMED, run, JobRunStatus.RUNNING));
        when(httpJobExecutor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(51L)))
                .thenReturn(new HttpExecutionResult(false, 500, FailureCategory.RETRYABLE, "HTTP 500 returned by target", null, 30));
        when(failureHandler.handleFailure(org.mockito.ArgumentMatchers.eq(run), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ExecutionFailureHandler.FailureDecision.RETRY_SCHEDULED);

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(failureHandler).handleFailure(org.mockito.ArgumentMatchers.eq(run), org.mockito.ArgumentMatchers.any());
        verify(completionService, never()).markSuccess(51L);
    }

    @Test
    void duplicateSuccessIsAcknowledgedAndNotExecuted() {
        JobRunQueuedEvent event = event(52L, 10L);
        JobRunEntity run = run(52L, 10L, JobRunStatus.SUCCESS, Map.of());
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL, run, JobRunStatus.SUCCESS));

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(httpJobExecutor, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void freshRunningDuplicateIsNotAcknowledgedAndNotExecuted() {
        JobRunQueuedEvent event = event(53L, 10L);
        JobRunEntity run = run(53L, 10L, JobRunStatus.RUNNING, Map.of());
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.FRESH_RUNNING, run, JobRunStatus.RUNNING));

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isFalse();
        verify(httpJobExecutor, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void wrongJobIdEventIsAcknowledgedAndNotExecuted() {
        JobRunQueuedEvent event = event(54L, 20L);
        JobRunEntity run = run(54L, 21L, JobRunStatus.QUEUED, Map.of());
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.INCONSISTENT_EVENT, run, JobRunStatus.QUEUED));

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(httpJobExecutor, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void unsupportedEventVersionIsAcknowledgedAndNotExecuted() {
        JobRunQueuedEvent event = new JobRunQueuedEvent("event-1", 2, 55L, 10L, LocalDateTime.now(), "JOB_RUN_QUEUED", LocalDateTime.now());

        JobExecutionService.ProcessingDecision decision = service.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(claimService, never()).claim(org.mockito.ArgumentMatchers.any());
    }

    private JobRunQueuedEvent event(Long runId, Long jobId) {
        return new JobRunQueuedEvent("event-1", 1, runId, jobId, LocalDateTime.now(), "JOB_RUN_QUEUED", LocalDateTime.now());
    }

    private JobRunEntity run(Long runId, Long jobId, JobRunStatus status, Map<String, Object> payload) {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setJobType(JobType.HTTP);
        job.setPayload(payload);

        JobRunEntity run = new JobRunEntity();
        run.setId(runId);
        run.setJob(job);
        run.setStatus(status);
        return run;
    }
}
