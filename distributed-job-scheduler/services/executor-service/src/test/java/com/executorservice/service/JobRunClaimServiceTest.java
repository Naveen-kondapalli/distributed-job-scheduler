package com.executorservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.dto.JobRunRetryEvent;
import com.executorservice.entity.JobEntity;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.repository.JobRunRepository;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class JobRunClaimServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    @Mock
    private JobRunRepository jobRunRepository;

    private JobRunClaimService claimService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ExecutorProperties properties = new ExecutorProperties();
        properties.getExecution().setRunningTimeoutMs(60_000);
        claimService = new JobRunClaimService(jobRunRepository, "executor-1", CLOCK, properties);
    }

    @Test
    void queuedRunUsesAtomicConditionalClaim() {
        JobRunQueuedEvent event = event(50L, 10L);
        JobRunEntity queued = run(50L, 10L, JobRunStatus.QUEUED);
        JobRunEntity running = run(50L, 10L, JobRunStatus.RUNNING);
        when(jobRunRepository.findWithJobById(50L)).thenReturn(Optional.of(queued), Optional.of(running));
        when(jobRunRepository.claimQueuedRun(
                org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq("executor-1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.QUEUED),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING)
        )).thenReturn(1);

        ClaimResult result = claimService.claim(event);

        assertThat(result.outcome()).isEqualTo(ClaimResult.Outcome.CLAIMED);
        verify(jobRunRepository).claimQueuedRun(
                org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq("executor-1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.QUEUED),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING)
        );
    }

    @Test
    void concurrentLoserDoesNotClaim() {
        JobRunQueuedEvent event = event(50L, 10L);
        JobRunEntity queued = run(50L, 10L, JobRunStatus.QUEUED);
        JobRunEntity running = run(50L, 10L, JobRunStatus.RUNNING);
        when(jobRunRepository.findWithJobById(50L)).thenReturn(Optional.of(queued), Optional.of(running));
        when(jobRunRepository.claimQueuedRun(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(0);

        ClaimResult result = claimService.claim(event);

        assertThat(result.outcome()).isEqualTo(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL);
        assertThat(result.existingStatus()).isEqualTo(JobRunStatus.RUNNING);
    }

    @Test
    void freshRunningRunIsNotClaimedOrAcknowledgedAsTerminalDuplicate() {
        JobRunQueuedEvent event = event(51L, 10L);
        JobRunEntity running = run(51L, 10L, JobRunStatus.RUNNING);
        running.setStartedAt(LocalDateTime.now(CLOCK).minusSeconds(30));
        when(jobRunRepository.findWithJobById(51L)).thenReturn(Optional.of(running));

        ClaimResult result = claimService.claim(event);

        assertThat(result.outcome()).isEqualTo(ClaimResult.Outcome.FRESH_RUNNING);
        assertThat(result.existingStatus()).isEqualTo(JobRunStatus.RUNNING);
    }

    @Test
    void staleRunningRunCanBeAtomicallyReclaimed() {
        JobRunQueuedEvent event = event(52L, 10L);
        JobRunEntity stale = run(52L, 10L, JobRunStatus.RUNNING);
        stale.setStartedAt(LocalDateTime.now(CLOCK).minusMinutes(2));
        JobRunEntity reclaimed = run(52L, 10L, JobRunStatus.RUNNING);
        reclaimed.setExecutorId("executor-1");
        reclaimed.setStartedAt(LocalDateTime.now(CLOCK));
        when(jobRunRepository.findWithJobById(52L)).thenReturn(Optional.of(stale), Optional.of(reclaimed));
        when(jobRunRepository.reclaimStaleRunningRun(
                org.mockito.ArgumentMatchers.eq(52L),
                org.mockito.ArgumentMatchers.eq("executor-1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING)
        )).thenReturn(1);

        ClaimResult result = claimService.claim(event);

        assertThat(result.outcome()).isEqualTo(ClaimResult.Outcome.CLAIMED);
        verify(jobRunRepository).reclaimStaleRunningRun(
                org.mockito.ArgumentMatchers.eq(52L),
                org.mockito.ArgumentMatchers.eq("executor-1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING)
        );
    }

    @Test
    void concurrentStaleRunningReclaimLoserDoesNotExecute() {
        JobRunQueuedEvent event = event(53L, 10L);
        JobRunEntity stale = run(53L, 10L, JobRunStatus.RUNNING);
        stale.setStartedAt(LocalDateTime.now(CLOCK).minusMinutes(2));
        when(jobRunRepository.findWithJobById(53L)).thenReturn(Optional.of(stale), Optional.of(stale));
        when(jobRunRepository.reclaimStaleRunningRun(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(0);

        ClaimResult result = claimService.claim(event);

        assertThat(result.outcome()).isEqualTo(ClaimResult.Outcome.FRESH_RUNNING);
    }

    @Test
    void terminalStatusesAreNeverRecoveredThroughRunEventPath() {
        for (JobRunStatus status : java.util.List.of(JobRunStatus.CANCEL_REQUESTED, JobRunStatus.SUCCESS, JobRunStatus.FAILED, JobRunStatus.CANCELLED)) {
            JobRunQueuedEvent event = event(60L + status.ordinal(), 10L);
            JobRunEntity terminal = run(60L + status.ordinal(), 10L, status);
            when(jobRunRepository.findWithJobById(60L + status.ordinal())).thenReturn(Optional.of(terminal));

            ClaimResult result = claimService.claim(event);

            assertThat(result.outcome()).isEqualTo(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL);
            assertThat(result.existingStatus()).isEqualTo(status);
        }
    }

    @Test
    void freshRunningRetryRunIsNotReclaimed() {
        JobRunRetryEvent event = retryEvent(70L, 10L, 1);
        JobRunEntity running = run(70L, 10L, JobRunStatus.RUNNING);
        running.setRetryCount(1);
        running.setStartedAt(LocalDateTime.now(CLOCK).minusSeconds(30));
        when(jobRunRepository.findWithJobById(70L)).thenReturn(Optional.of(running));

        ClaimResult result = claimService.claimRetry(event);

        assertThat(result.outcome()).isEqualTo(ClaimResult.Outcome.FRESH_RUNNING);
    }

    private JobRunQueuedEvent event(Long runId, Long jobId) {
        return new JobRunQueuedEvent("event-1", 1, runId, jobId, LocalDateTime.now(), "JOB_RUN_QUEUED", LocalDateTime.now());
    }

    private JobRunRetryEvent retryEvent(Long runId, Long jobId, int retryCount) {
        return new JobRunRetryEvent("retry-event", 1, "JOB_RUN_RETRY_SCHEDULED", runId, jobId, retryCount, LocalDateTime.now(CLOCK), LocalDateTime.now(CLOCK), LocalDateTime.now(CLOCK), null);
    }

    private JobRunEntity run(Long runId, Long jobId, JobRunStatus status) {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        JobRunEntity run = new JobRunEntity();
        run.setId(runId);
        run.setJob(job);
        run.setStatus(status);
        return run;
    }
}
