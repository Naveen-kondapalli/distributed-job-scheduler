package com.jobservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobservice.dto.request.CreateJobRequest;
import com.jobservice.dto.request.UpdateJobRequest;
import com.jobservice.dto.response.JobStatusResponse;
import com.jobservice.cancellation.CancellationSignalException;
import com.jobservice.cancellation.CancellationSignalService;
import com.jobservice.entity.Job;
import com.jobservice.entity.JobRun;
import com.jobservice.entity.User;
import com.jobservice.enums.JobRunStatus;
import com.jobservice.enums.JobStatus;
import com.jobservice.enums.JobType;
import com.jobservice.enums.ScheduleType;
import com.jobservice.exception.BadRequestException;
import com.jobservice.exception.ConflictException;
import com.jobservice.exception.ResourceNotFoundException;
import com.jobservice.mapper.JobMapper;
import com.jobservice.observability.JobServiceMetrics;
import com.jobservice.repository.JobRepository;
import com.jobservice.repository.JobRunRepository;
import com.jobservice.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-12T10:15:30Z");
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 12, 15, 45, 30);
    private static final LocalDateTime NEXT_MINUTE = LocalDateTime.of(2026, 8, 12, 15, 46);

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobRunRepository jobRunRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CancellationSignalService cancellationSignalService;

    private final JobMapper jobMapper = Mappers.getMapper(JobMapper.class);

    private JobService jobService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, APPLICATION_ZONE);
        meterRegistry = new SimpleMeterRegistry();
        jobService = new JobService(jobRepository, jobRunRepository, userRepository, jobMapper, fixedClock, cancellationSignalService, noOpTransactionManager(), new JobServiceMetrics(meterRegistry));
    }

    @Test
    void createJobAssignsAuthenticatedUserAndActiveStatus() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(10L);
            return job;
        });
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);

        var response = jobService.createJob(immediateCreateRequest(), 1L);

        verify(jobRepository).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getUser()).isEqualTo(user);
        assertThat(jobCaptor.getValue().getStatus()).isEqualTo(JobStatus.ACTIVE);
        assertThat(jobCaptor.getValue().getMaxRetries()).isEqualTo(3);
        assertThat(jobCaptor.getValue().getNextRunAt()).isNull();
        assertThat(response.id()).isEqualTo(10L);
        assertThat(meterRegistry.counter("scheduler.jobs.created", "schedule_type", "IMMEDIATE").count()).isEqualTo(1.0);
        assertThat(response.status()).isEqualTo(JobStatus.ACTIVE);
    }

    @Test
    void futureCreateSetsNextRunAtToScheduledTime() {
        User user = user(1L);
        LocalDateTime scheduledTime = FIXED_NOW.plusMinutes(10);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);

        jobService.createJob(createRequest(ScheduleType.FUTURE, scheduledTime, null), 1L);

        verify(jobRepository).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getNextRunAt()).isEqualTo(scheduledTime);
    }

    @Test
    void cronCreateSetsNextRunAtToNextCronOccurrence() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);

        jobService.createJob(createRequest(ScheduleType.CRON, null, "0 * * * * *"), 1L);

        verify(jobRepository).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getNextRunAt()).isEqualTo(NEXT_MINUTE);
    }

    @Test
    void futureWithoutScheduledTimeFails() {
        assertThatThrownBy(() -> jobService.createJob(createRequest(ScheduleType.FUTURE, null, null), 1L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("For FUTURE jobs, scheduledTime is required");
    }

    @Test
    void futureWithPastScheduledTimeFails() {
        assertThatThrownBy(() -> jobService.createJob(
                createRequest(ScheduleType.FUTURE, FIXED_NOW.minusMinutes(1), null),
                1L
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("scheduledTime must be in the future");
    }

    @Test
    void cronWithoutCronExpressionFails() {
        assertThatThrownBy(() -> jobService.createJob(createRequest(ScheduleType.CRON, null, null), 1L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("For CRON jobs, cronExpression is required");
    }

    @Test
    void cronWithInvalidCronExpressionFails() {
        assertThatThrownBy(() -> jobService.createJob(createRequest(ScheduleType.CRON, null, "not-a-cron"), 1L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid cronExpression");
    }

    @Test
    void immediateWithScheduledTimeFails() {
        assertThatThrownBy(() -> jobService.createJob(
                createRequest(ScheduleType.IMMEDIATE, FIXED_NOW.plusMinutes(10), null),
                1L
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("IMMEDIATE jobs must not define scheduledTime or cronExpression");
    }

    @Test
    void ownerCanRetrieveJob() {
        Job job = job(10L, 1L, JobStatus.ACTIVE);
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        assertThat(jobService.getJob(10L, 1L).id()).isEqualTo(10L);
    }

    @Test
    void anotherUserGetsNotFound() {
        when(jobRepository.findByIdAndUserId(10L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.getJob(10L, 2L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Job not found");
    }

    @Test
    void nonexistentJobGetsNotFound() {
        when(jobRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.getJob(99L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Job not found");
    }

    @Test
    void getAllReturnsOnlyAuthenticatedUsersJobsWithPagination() {
        when(jobRepository.findAllByUserId(1L, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(java.util.List.of(job(10L, 1L, JobStatus.ACTIVE)), PageRequest.of(0, 1), 2));

        Page<?> response = jobService.getAllJobs(1L, PageRequest.of(0, 1));

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(2);
        verify(jobRepository).findAllByUserId(1L, PageRequest.of(0, 1));
    }

    @Test
    void ownerCanUpdateJob() {
        Job job = job(10L, 1L, JobStatus.ACTIVE);
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        var response = jobService.updateJob(10L, updateRequest("updated", ScheduleType.IMMEDIATE, null, null), 1L);

        assertThat(response.name()).isEqualTo("updated");
    }

    @Test
    void updateFutureSchedulingRecomputesNextRunAt() {
        Job job = job(10L, 1L, JobStatus.ACTIVE);
        LocalDateTime scheduledTime = FIXED_NOW.plusHours(1);
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        jobService.updateJob(10L, updateRequest("updated", ScheduleType.FUTURE, scheduledTime, null), 1L);

        assertThat(job.getNextRunAt()).isEqualTo(scheduledTime);
    }

    @Test
    void updateCronSchedulingRecomputesNextRunAt() {
        Job job = job(10L, 1L, JobStatus.ACTIVE);
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        jobService.updateJob(10L, updateRequest("updated", ScheduleType.CRON, null, "0 * * * * *"), 1L);

        assertThat(job.getNextRunAt()).isEqualTo(NEXT_MINUTE);
    }

    @Test
    void cancelledJobCannotBeUpdated() {
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job(10L, 1L, JobStatus.CANCELLED)));

        assertThatThrownBy(() -> jobService.updateJob(10L, updateRequest("updated", ScheduleType.IMMEDIATE, null, null), 1L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Cancelled jobs cannot be modified");
    }

    @Test
    void cancelActiveFutureJobSetsCancelledAndClearsNextRunAt() {
        Job job = job(10L, 1L, JobStatus.ACTIVE);
        job.setScheduleType(ScheduleType.FUTURE);
        job.setScheduledTime(FIXED_NOW.plusMinutes(10));
        job.setNextRunAt(FIXED_NOW.plusMinutes(10));
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        JobStatusResponse response = jobService.cancelJob(10L, 1L);

        assertThat(response.jobStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getNextRunAt()).isNull();
        assertThat(job.getScheduledTime()).isEqualTo(FIXED_NOW.plusMinutes(10));
    }

    @Test
    void cancelActiveCronJobSetsCancelledAndClearsNextRunAt() {
        Job job = job(10L, 1L, JobStatus.ACTIVE);
        job.setScheduleType(ScheduleType.CRON);
        job.setCronExpression("0 * * * * *");
        job.setNextRunAt(NEXT_MINUTE);
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        JobStatusResponse response = jobService.cancelJob(10L, 1L);

        assertThat(response.jobStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getNextRunAt()).isNull();
        assertThat(job.getCronExpression()).isEqualTo("0 * * * * *");
    }

    @Test
    void cancelPausedFutureJobSucceeds() {
        Job job = job(10L, 1L, JobStatus.PAUSED);
        job.setScheduleType(ScheduleType.FUTURE);
        job.setNextRunAt(FIXED_NOW.plusMinutes(10));
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        assertThat(jobService.cancelJob(10L, 1L).jobStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getNextRunAt()).isNull();
    }

    @Test
    void cancelPausedCronJobSucceeds() {
        Job job = job(10L, 1L, JobStatus.PAUSED);
        job.setScheduleType(ScheduleType.CRON);
        job.setCronExpression("0 * * * * *");
        job.setNextRunAt(NEXT_MINUTE);
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        assertThat(jobService.cancelJob(10L, 1L).jobStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getNextRunAt()).isNull();
    }

    @Test
    void cancelAlreadyCancelledJobIsIdempotent() {
        Job job = job(10L, 1L, JobStatus.CANCELLED);
        job.setNextRunAt(null);
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        JobStatusResponse response = jobService.cancelJob(10L, 1L);

        assertThat(response.jobStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getNextRunAt()).isNull();
    }

    @Test
    void cancelByAnotherUserGetsNotFound() {
        when(jobRepository.findByIdAndUserId(10L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.cancelJob(10L, 2L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Job not found");
    }

    @Test
    void cancelNonexistentJobGetsNotFound() {
        when(jobRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.cancelJob(99L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Job not found");
    }

    @Test
    void cancelQueuedJobRunSetsCancelledWithoutRedisSignal() {
        JobRun run = run(100L, job(10L, 1L, JobStatus.ACTIVE), JobRunStatus.QUEUED);
        when(jobRunRepository.findByIdAndJobIdAndJobUserId(100L, 10L, 1L)).thenReturn(Optional.of(run));
        when(jobRunRepository.cancelQueuedOrRetryScheduled(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.QUEUED),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCELLED),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        )).thenReturn(1);

        var response = jobService.cancelJobRun(10L, 100L, 1L);

        assertThat(response.status()).isEqualTo(JobRunStatus.CANCELLED);
        verify(cancellationSignalService, org.mockito.Mockito.never()).createSignal(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cancelRetryScheduledJobRunClearsViaAtomicUpdateWithoutRedisSignal() {
        JobRun run = run(101L, job(10L, 1L, JobStatus.ACTIVE), JobRunStatus.RETRY_SCHEDULED);
        when(jobRunRepository.findByIdAndJobIdAndJobUserId(101L, 10L, 1L)).thenReturn(Optional.of(run));
        when(jobRunRepository.cancelQueuedOrRetryScheduled(
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RETRY_SCHEDULED),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCELLED),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        )).thenReturn(1);

        assertThat(jobService.cancelJobRun(10L, 101L, 1L).status()).isEqualTo(JobRunStatus.CANCELLED);
    }

    @Test
    void cancelRunningJobRunRequestsCancellationAndSignalsRedis() {
        JobRun run = run(102L, job(10L, 1L, JobStatus.ACTIVE), JobRunStatus.RUNNING);
        run.setExecutorId("executor-1");
        when(jobRunRepository.findByIdAndJobIdAndJobUserId(102L, 10L, 1L)).thenReturn(Optional.of(run));
        when(jobRunRepository.requestRunningCancellation(
                org.mockito.ArgumentMatchers.eq(102L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCEL_REQUESTED)
        )).thenReturn(1);

        var response = jobService.cancelJobRun(10L, 102L, 1L);

        assertThat(response.status()).isEqualTo(JobRunStatus.CANCEL_REQUESTED);
        verify(cancellationSignalService).createSignal(102L, 1L);
    }

    @Test
    void redisFailureLeavesRunningCancellationIntentDurableAndReportsFailure() {
        JobRun run = run(103L, job(10L, 1L, JobStatus.ACTIVE), JobRunStatus.RUNNING);
        when(jobRunRepository.findByIdAndJobIdAndJobUserId(103L, 10L, 1L)).thenReturn(Optional.of(run));
        when(jobRunRepository.requestRunningCancellation(
                org.mockito.ArgumentMatchers.eq(103L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCEL_REQUESTED)
        )).thenReturn(1);
        org.mockito.Mockito.doThrow(new CancellationSignalException("signal failed", new RuntimeException("redis down")))
                .when(cancellationSignalService).createSignal(103L, 1L);

        assertThatThrownBy(() -> jobService.cancelJobRun(10L, 103L, 1L))
                .isInstanceOf(CancellationSignalException.class);

        verify(jobRunRepository).requestRunningCancellation(
                org.mockito.ArgumentMatchers.eq(103L),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.RUNNING),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCEL_REQUESTED)
        );
    }

    @Test
    void cancelSuccessJobRunConflicts() {
        JobRun run = run(104L, job(10L, 1L, JobStatus.ACTIVE), JobRunStatus.SUCCESS);
        when(jobRunRepository.findByIdAndJobIdAndJobUserId(104L, 10L, 1L)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> jobService.cancelJobRun(10L, 104L, 1L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelAlreadyCancelledJobRunIsIdempotent() {
        JobRun run = run(105L, job(10L, 1L, JobStatus.ACTIVE), JobRunStatus.CANCELLED);
        when(jobRunRepository.findByIdAndJobIdAndJobUserId(105L, 10L, 1L)).thenReturn(Optional.of(run));

        assertThat(jobService.cancelJobRun(10L, 105L, 1L).status()).isEqualTo(JobRunStatus.CANCELLED);
    }

    @Test
    void cancelJobRunForAnotherUserGetsNotFound() {
        when(jobRunRepository.findByIdAndJobIdAndJobUserId(100L, 10L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.cancelJobRun(10L, 100L, 2L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("JobRun not found");
    }

    @Test
    void updateValidatesScheduleRules() {
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job(10L, 1L, JobStatus.ACTIVE)));

        assertThatThrownBy(() -> jobService.updateJob(
                10L,
                updateRequest("updated", ScheduleType.CRON, FIXED_NOW.plusMinutes(10), "0 * * * * *"),
                1L
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("For CRON jobs, scheduledTime must not be defined");
    }

    @Test
    void pauseActiveJobSetsPaused() {
        Job job = job(10L, 1L, JobStatus.ACTIVE);
        job.setNextRunAt(FIXED_NOW.plusMinutes(10));
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        assertThat(jobService.pauseJob(10L, 1L).jobStatus()).isEqualTo(JobStatus.PAUSED);
        assertThat(job.getNextRunAt()).isEqualTo(FIXED_NOW.plusMinutes(10));
    }

    @Test
    void pauseAlreadyPausedIsIdempotent() {
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job(10L, 1L, JobStatus.PAUSED)));

        assertThat(jobService.pauseJob(10L, 1L).jobStatus()).isEqualTo(JobStatus.PAUSED);
    }

    @Test
    void cancelledJobCannotBePaused() {
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job(10L, 1L, JobStatus.CANCELLED)));

        assertThatThrownBy(() -> jobService.pauseJob(10L, 1L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void resumePausedJobSetsActive() {
        Job job = job(10L, 1L, JobStatus.PAUSED);
        job.setScheduleType(ScheduleType.FUTURE);
        job.setScheduledTime(FIXED_NOW.minusMinutes(5));
        job.setNextRunAt(FIXED_NOW.minusMinutes(5));
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        assertThat(jobService.resumeJob(10L, 1L).jobStatus()).isEqualTo(JobStatus.ACTIVE);
        assertThat(job.getNextRunAt()).isEqualTo(FIXED_NOW.minusMinutes(5));
    }

    @Test
    void resumeCronJobCalculatesNextOccurrenceAfterResumeTime() {
        Job job = job(10L, 1L, JobStatus.PAUSED);
        job.setScheduleType(ScheduleType.CRON);
        job.setCronExpression("0 * * * * *");
        job.setNextRunAt(FIXED_NOW.minusHours(1));
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        assertThat(jobService.resumeJob(10L, 1L).jobStatus()).isEqualTo(JobStatus.ACTIVE);

        assertThat(job.getNextRunAt()).isEqualTo(NEXT_MINUTE);
    }

    @Test
    void resumeAlreadyActiveIsIdempotent() {
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job(10L, 1L, JobStatus.ACTIVE)));

        assertThat(jobService.resumeJob(10L, 1L).jobStatus()).isEqualTo(JobStatus.ACTIVE);
    }

    @Test
    void cancelledJobCannotBeResumed() {
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job(10L, 1L, JobStatus.CANCELLED)));

        assertThatThrownBy(() -> jobService.resumeJob(10L, 1L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void ownerCanDeleteJob() {
        Job job = job(10L, 1L, JobStatus.ACTIVE);
        when(jobRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(job));

        jobService.deleteJob(10L, 1L);

        verify(jobRepository).delete(job);
    }

    @Test
    void deleteByAnotherUserGetsNotFound() {
        when(jobRepository.findByIdAndUserId(10L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.deleteJob(10L, 2L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteNonexistentJobGetsNotFound() {
        when(jobRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.deleteJob(99L, 1L)).isInstanceOf(ResourceNotFoundException.class);
    }

    private CreateJobRequest immediateCreateRequest() {
        return createRequest(ScheduleType.IMMEDIATE, null, null);
    }

    private CreateJobRequest createRequest(ScheduleType scheduleType, LocalDateTime scheduledTime, String cronExpression) {
        return new CreateJobRequest("job", "description", JobType.HTTP, scheduleType, scheduledTime, cronExpression, Map.of("url", "https://example.com"), null);
    }

    private UpdateJobRequest updateRequest(String name, ScheduleType scheduleType, LocalDateTime scheduledTime, String cronExpression) {
        return new UpdateJobRequest(name, "description", JobType.HTTP, scheduleType, scheduledTime, cronExpression, Map.of("url", "https://example.com"), 1);
    }

    private Job job(Long jobId, Long userId, JobStatus status) {
        Job job = new Job();
        job.setId(jobId);
        job.setUser(user(userId));
        job.setName("job");
        job.setDescription("description");
        job.setJobType(JobType.HTTP);
        job.setScheduleType(ScheduleType.IMMEDIATE);
        job.setPayload(Map.of("url", "https://example.com"));
        job.setStatus(status);
        job.setMaxRetries(3);
        return job;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("naveen");
        user.setEmail("naveen@example.com");
        user.setPassword("$2a$hash");
        return user;
    }

    private JobRun run(Long runId, Job job, JobRunStatus status) {
        JobRun run = new JobRun();
        run.setId(runId);
        run.setJob(job);
        run.setStatus(status);
        run.setScheduledAt(FIXED_NOW);
        run.setRetryCount(0);
        return run;
    }

    private PlatformTransactionManager noOpTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
