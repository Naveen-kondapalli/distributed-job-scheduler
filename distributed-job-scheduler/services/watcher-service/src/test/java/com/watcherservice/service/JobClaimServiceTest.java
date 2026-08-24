package com.watcherservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.watcherservice.entity.JobEntity;
import com.watcherservice.entity.JobRunEntity;
import com.watcherservice.entity.OutboxEventEntity;
import com.watcherservice.enums.JobRunStatus;
import com.watcherservice.enums.JobStatus;
import com.watcherservice.enums.OutboxStatus;
import com.watcherservice.enums.ScheduleType;
import com.watcherservice.observability.WatcherMetrics;
import com.watcherservice.repository.JobRepository;
import com.watcherservice.repository.JobRunRepository;
import com.watcherservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class JobClaimServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 15, 45, 30);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 8, 12, 15, 45);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 8, 12, 15, 46);

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobRunRepository jobRunRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private JobClaimService jobClaimService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        jobClaimService = new JobClaimService(jobRepository, jobRunRepository, outboxEventRepository, new ObjectMapper(), new WatcherMetrics(meterRegistry, outboxEventRepository));
    }

    @Test
    void claimDueOccurrencesQueriesActiveJobsByNextRunAtWithBatchSize() {
        when(jobRepository.findDueJobsForUpdateSkipLocked(JobStatus.ACTIVE.name(), NOW, 25))
                .thenReturn(List.of());

        jobClaimService.claimDueOccurrences(NOW, 25);

        verify(jobRepository).findDueJobsForUpdateSkipLocked(JobStatus.ACTIVE.name(), NOW, 25);
    }

    @Test
    void cancelledJobsAreNotClaimedBecauseRepositoryQueryOnlyRequestsActiveJobs() {
        when(jobRepository.findDueJobsForUpdateSkipLocked(JobStatus.ACTIVE.name(), NOW, 100))
                .thenReturn(List.of());

        jobClaimService.claimDueOccurrences(NOW, 100);

        verify(jobRepository).findDueJobsForUpdateSkipLocked(JobStatus.ACTIVE.name(), NOW, 100);
        verify(jobRunRepository, org.mockito.Mockito.never())
                .saveAndFlush(org.mockito.ArgumentMatchers.any(JobRunEntity.class));
    }

    @Test
    void claimFutureJobCreatesQueuedRunOutboxEventAndClearsNextRunAt() {
        JobEntity job = job(10L, ScheduleType.FUTURE, T1, null);
        when(jobRepository.findDueJobsForUpdateSkipLocked(JobStatus.ACTIVE.name(), NOW, 100))
                .thenReturn(List.of(job));
        when(jobRunRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(JobRunEntity.class)))
                .thenAnswer(invocation -> {
                    JobRunEntity run = invocation.getArgument(0);
                    run.setId(50L);
                    return run;
                });
        ArgumentCaptor<JobRunEntity> runCaptor = ArgumentCaptor.forClass(JobRunEntity.class);
        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);

        jobClaimService.claimDueOccurrences(NOW, 100);

        verify(jobRunRepository).saveAndFlush(runCaptor.capture());
        verify(outboxEventRepository).save(outboxCaptor.capture());
        JobRunEntity run = runCaptor.getValue();
        assertThat(run.getJob()).isEqualTo(job);
        assertThat(run.getStatus()).isEqualTo(JobRunStatus.QUEUED);
        assertThat(run.getScheduledAt()).isEqualTo(T1);
        assertThat(run.getRetryCount()).isZero();
        assertThat(run.getStartedAt()).isNull();
        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getExecutorId()).isNull();
        assertThat(run.getErrorMessage()).isNull();
        assertThat(job.getNextRunAt()).isNull();

        OutboxEventEntity outboxEvent = outboxCaptor.getValue();
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEvent.getEventType()).isEqualTo("JOB_RUN_QUEUED");
        assertThat(outboxEvent.getAggregateType()).isEqualTo("JOB_RUN");
        assertThat(outboxEvent.getAggregateId()).isEqualTo(50L);
        assertThat(outboxEvent.getTopic()).isEqualTo("run");
        assertThat(outboxEvent.getMessageKey()).isEqualTo("50");
        assertThat(outboxEvent.getAttemptCount()).isZero();
        assertThat(outboxEvent.getPayload()).contains("\"runId\":50");
        assertThat(outboxEvent.getPayload()).contains("\"jobId\":10");
        assertThat(outboxEvent.getPayload()).contains("\"scheduledAt\":\"2026-08-12T15:45:00\"");
        assertThat(outboxEvent.getPayload()).contains("\"eventVersion\":1");
    }

    @Test
    void claimCronJobCreatesQueuedRunAndAdvancesFromClaimedOccurrence() {
        JobEntity job = job(10L, ScheduleType.CRON, T1, "0 * * * * *");
        when(jobRepository.findDueJobsForUpdateSkipLocked(JobStatus.ACTIVE.name(), NOW, 100))
                .thenReturn(List.of(job));
        when(jobRunRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(JobRunEntity.class)))
                .thenAnswer(invocation -> {
                    JobRunEntity run = invocation.getArgument(0);
                    run.setId(50L);
                    return run;
                });

        jobClaimService.claimDueOccurrences(NOW, 100);

        assertThat(job.getNextRunAt()).isEqualTo(T2);
        verify(outboxEventRepository).save(org.mockito.ArgumentMatchers.any(OutboxEventEntity.class));
    }

    @Test
    void claimRollsBackStateMutationIfJobRunCreationFailsBeforeAdvance() {
        JobEntity job = job(10L, ScheduleType.FUTURE, T1, null);
        when(jobRepository.findDueJobsForUpdateSkipLocked(JobStatus.ACTIVE.name(), NOW, 100))
                .thenReturn(List.of(job));
        when(jobRunRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(JobRunEntity.class)))
                .thenThrow(new IllegalStateException("insert failed"));

        assertThatThrownBy(() -> jobClaimService.claimDueOccurrences(NOW, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("insert failed");

        assertThat(job.getNextRunAt()).isEqualTo(T1);
    }

    @Test
    void claimRollsBackStateMutationIfOutboxCreationFailsBeforeAdvance() {
        JobEntity job = job(10L, ScheduleType.FUTURE, T1, null);
        when(jobRepository.findDueJobsForUpdateSkipLocked(JobStatus.ACTIVE.name(), NOW, 100))
                .thenReturn(List.of(job));
        when(jobRunRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(JobRunEntity.class)))
                .thenAnswer(invocation -> {
                    JobRunEntity run = invocation.getArgument(0);
                    run.setId(50L);
                    return run;
                });
        when(outboxEventRepository.save(org.mockito.ArgumentMatchers.any(OutboxEventEntity.class)))
                .thenThrow(new IllegalStateException("outbox insert failed"));

        assertThatThrownBy(() -> jobClaimService.claimDueOccurrences(NOW, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox insert failed");

        assertThat(job.getNextRunAt()).isEqualTo(T1);
    }

    private JobEntity job(Long id, ScheduleType scheduleType, LocalDateTime nextRunAt, String cronExpression) {
        JobEntity job = new JobEntity();
        job.setId(id);
        job.setName("job");
        job.setStatus(JobStatus.ACTIVE);
        job.setScheduleType(scheduleType);
        job.setNextRunAt(nextRunAt);
        job.setCronExpression(cronExpression);
        return job;
    }
}
