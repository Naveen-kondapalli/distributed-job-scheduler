package com.jobservice.service;

import com.jobservice.cancellation.CancellationSignalService;
import com.jobservice.dto.request.CreateJobRequest;
import com.jobservice.dto.request.UpdateJobRequest;
import com.jobservice.dto.response.JobResponse;
import com.jobservice.dto.response.JobRunStatusResponse;
import com.jobservice.dto.response.JobStatusResponse;
import com.jobservice.entity.Job;
import com.jobservice.entity.JobRun;
import com.jobservice.entity.User;
import com.jobservice.enums.JobRunStatus;
import com.jobservice.enums.JobStatus;
import com.jobservice.enums.ScheduleType;
import com.jobservice.exception.BadRequestException;
import com.jobservice.exception.ConflictException;
import com.jobservice.exception.ErrorCode;
import com.jobservice.exception.ResourceNotFoundException;
import com.jobservice.mapper.JobMapper;
import com.jobservice.repository.JobRepository;
import com.jobservice.repository.JobRunRepository;
import com.jobservice.repository.UserRepository;
import com.jobservice.service.interfaces.JobServiceInterface;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class JobService implements JobServiceInterface {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final String JOB_NOT_FOUND_MESSAGE = "Job not found";
    private static final String CANCELLED_JOB_MESSAGE = "Cancelled jobs cannot be modified";

    private final JobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final UserRepository userRepository;
    private final JobMapper jobMapper;
    private final Clock clock;
    private final CancellationSignalService cancellationSignalService;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional
    public JobResponse createJob(CreateJobRequest request, Long userId) {
        validateSchedule(request.scheduleType(), request.scheduledTime(), request.cronExpression());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Job job = jobMapper.toEntity(request);
        job.setUser(user);
        job.setStatus(JobStatus.ACTIVE);
        job.setMaxRetries(request.maxRetries() == null ? DEFAULT_MAX_RETRIES : request.maxRetries());
        job.setNextRunAt(calculateNextRunAt(job.getScheduleType(), job.getScheduledTime(), job.getCronExpression()));

        return jobMapper.toResponse(jobRepository.save(job));
    }

    @Override
    @Transactional(readOnly = true)
    public JobResponse getJob(Long jobId, Long userId) {
        return jobMapper.toResponse(findOwnedJob(jobId, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JobResponse> getAllJobs(Long userId, Pageable pageable) {
        return jobRepository.findAllByUserId(userId, pageable).map(jobMapper::toResponse);
    }

    @Override
    @Transactional
    public JobResponse updateJob(Long jobId, UpdateJobRequest request, Long userId) {
        Job job = findOwnedJob(jobId, userId);
        ensureModifiable(job);
        validateSchedule(request.scheduleType(), request.scheduledTime(), request.cronExpression());

        applyUpdate(request, job);
        job.setMaxRetries(request.maxRetries() == null ? DEFAULT_MAX_RETRIES : request.maxRetries());
        job.setNextRunAt(calculateNextRunAt(job.getScheduleType(), job.getScheduledTime(), job.getCronExpression()));

        return jobMapper.toResponse(job);
    }

    @Override
    @Transactional
    public void deleteJob(Long jobId, Long userId) {
        Job job = findOwnedJob(jobId, userId);
        job.setNextRunAt(null);
        jobRepository.delete(job);
    }

    @Override
    @Transactional
    public JobStatusResponse cancelJob(Long jobId, Long userId) {
        Job job = findOwnedJob(jobId, userId);
        job.setStatus(JobStatus.CANCELLED);
        job.setNextRunAt(null);
        // Job cancellation prevents future scheduling only. Already queued or running
        // JobRuns are handled by the execution pipeline in a later phase.
        return new JobStatusResponse(job.getId(), job.getStatus());
    }

    @Override
    public JobRunStatusResponse cancelJobRun(Long jobId, Long runId, Long userId) {
        CancellationDecision decision = new TransactionTemplate(transactionManager).execute(status -> cancelJobRunInTransaction(jobId, runId, userId));
        if (decision.signalRequired()) {
            cancellationSignalService.createSignal(runId, userId);
            log.info("Running cancellation signal created: runId={}, executorId={}", runId, decision.executorId());
        }
        log.info("Cancellation requested: jobId={}, runId={}, status={}, executorId={}", jobId, runId, decision.status(), decision.executorId());
        return new JobRunStatusResponse(jobId, runId, decision.status());
    }

    @Override
    @Transactional
    public JobStatusResponse pauseJob(Long jobId, Long userId) {
        Job job = findOwnedJob(jobId, userId);
        ensureModifiable(job);
        if (job.getStatus() == JobStatus.ACTIVE) {
            job.setStatus(JobStatus.PAUSED);
        }
        return new JobStatusResponse(job.getId(), job.getStatus());
    }

    @Override
    @Transactional
    public JobStatusResponse resumeJob(Long jobId, Long userId) {
        Job job = findOwnedJob(jobId, userId);
        ensureModifiable(job);
        if (job.getStatus() == JobStatus.PAUSED) {
            if (job.getScheduleType() == ScheduleType.CRON) {
                job.setNextRunAt(nextCronRunAfter(job.getCronExpression(), LocalDateTime.now(clock)));
            }
            job.setStatus(JobStatus.ACTIVE);
        }
        return new JobStatusResponse(job.getId(), job.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public JobStatusResponse getJobStatus(Long jobId, Long userId) {
        Job job = findOwnedJob(jobId, userId);
        return new JobStatusResponse(job.getId(), job.getStatus());
    }

    private Job findOwnedJob(Long jobId, Long userId) {
        return jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(JOB_NOT_FOUND_MESSAGE));
    }

    private CancellationDecision cancelJobRunInTransaction(Long jobId, Long runId, Long userId) {
        JobRun run = jobRunRepository.findByIdAndJobIdAndJobUserId(runId, jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("JobRun not found"));
        JobRunStatus current = run.getStatus();
        if (current == JobRunStatus.CANCELLED) {
            return new CancellationDecision(JobRunStatus.CANCELLED, false, run.getExecutorId());
        }
        if (current == JobRunStatus.CANCEL_REQUESTED) {
            return new CancellationDecision(JobRunStatus.CANCEL_REQUESTED, false, run.getExecutorId());
        }
        if (current == JobRunStatus.SUCCESS || current == JobRunStatus.FAILED) {
            throw new ConflictException(ErrorCode.CONFLICT, "Terminal JobRun cannot be cancelled");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (current == JobRunStatus.QUEUED || current == JobRunStatus.RETRY_SCHEDULED) {
            int updated = jobRunRepository.cancelQueuedOrRetryScheduled(
                    runId,
                    jobId,
                    userId,
                    current,
                    JobRunStatus.CANCELLED,
                    now
            );
            if (updated != 1) {
                JobRun latest = jobRunRepository.findByIdAndJobIdAndJobUserId(runId, jobId, userId)
                        .orElseThrow(() -> new ResourceNotFoundException("JobRun not found"));
                return cancellationDecisionForConcurrentState(latest.getStatus());
            }
            log.info("{} execution cancelled: runId={}", current == JobRunStatus.QUEUED ? "Queued" : "Retry", runId);
            return new CancellationDecision(JobRunStatus.CANCELLED, false, run.getExecutorId());
        }

        if (current == JobRunStatus.RUNNING) {
            int updated = jobRunRepository.requestRunningCancellation(
                    runId,
                    jobId,
                    userId,
                    now,
                    JobRunStatus.RUNNING,
                    JobRunStatus.CANCEL_REQUESTED
            );
            if (updated != 1) {
                JobRun latest = jobRunRepository.findByIdAndJobIdAndJobUserId(runId, jobId, userId)
                        .orElseThrow(() -> new ResourceNotFoundException("JobRun not found"));
                return cancellationDecisionForConcurrentState(latest.getStatus());
            }
            return new CancellationDecision(JobRunStatus.CANCEL_REQUESTED, true, run.getExecutorId());
        }

        throw new ConflictException(ErrorCode.CONFLICT, "JobRun cannot be cancelled from status " + current);
    }

    private CancellationDecision cancellationDecisionForConcurrentState(JobRunStatus status) {
        if (status == JobRunStatus.CANCELLED) {
            return new CancellationDecision(JobRunStatus.CANCELLED, false, null);
        }
        if (status == JobRunStatus.CANCEL_REQUESTED) {
            return new CancellationDecision(JobRunStatus.CANCEL_REQUESTED, false, null);
        }
        if (status == JobRunStatus.SUCCESS || status == JobRunStatus.FAILED) {
            throw new ConflictException(ErrorCode.CONFLICT, "Terminal JobRun cannot be cancelled");
        }
        throw new ConflictException(ErrorCode.CONFLICT, "JobRun cancellation race lost; current status is " + status);
    }

    private record CancellationDecision(JobRunStatus status, boolean signalRequired, String executorId) {
    }

    private void ensureModifiable(Job job) {
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new ConflictException(ErrorCode.CONFLICT, CANCELLED_JOB_MESSAGE);
        }
    }

    private void applyUpdate(UpdateJobRequest request, Job job) {
        job.setName(request.name());
        job.setDescription(request.description());
        job.setJobType(request.jobType());
        job.setScheduleType(request.scheduleType());
        job.setScheduledTime(request.scheduledTime());
        job.setCronExpression(request.cronExpression());
        job.setPayload(request.payload());
    }

    private void validateSchedule(ScheduleType scheduleType, LocalDateTime scheduledTime, String cronExpression) {
        if (scheduleType == ScheduleType.IMMEDIATE) {
            if (scheduledTime != null || StringUtils.hasText(cronExpression)) {
                throw invalidSchedule("IMMEDIATE jobs must not define scheduledTime or cronExpression");
            }
            return;
        }

        if (scheduleType == ScheduleType.FUTURE) {
            if (scheduledTime == null) {
                throw invalidSchedule("For FUTURE jobs, scheduledTime is required");
            }
            if (!scheduledTime.isAfter(LocalDateTime.now(clock))) {
                throw invalidSchedule("scheduledTime must be in the future");
            }
            if (StringUtils.hasText(cronExpression)) {
                throw invalidSchedule("For FUTURE jobs, cronExpression must not be defined");
            }
            return;
        }

        if (scheduleType == ScheduleType.CRON) {
            if (!StringUtils.hasText(cronExpression)) {
                throw invalidSchedule("For CRON jobs, cronExpression is required");
            }
            parseCronExpression(cronExpression);
            if (scheduledTime != null) {
                throw invalidSchedule("For CRON jobs, scheduledTime must not be defined");
            }
        }
    }

    private LocalDateTime calculateNextRunAt(ScheduleType scheduleType, LocalDateTime scheduledTime, String cronExpression) {
        if (scheduleType == ScheduleType.FUTURE) {
            return scheduledTime;
        }
        if (scheduleType == ScheduleType.CRON) {
            return nextCronRunAfter(cronExpression, LocalDateTime.now(clock));
        }
        return null;
    }

    private LocalDateTime nextCronRunAfter(String cronExpression, LocalDateTime after) {
        LocalDateTime nextRunAt = parseCronExpression(cronExpression).next(after);
        if (nextRunAt == null) {
            throw invalidSchedule("cronExpression does not produce a next run time");
        }
        return nextRunAt;
    }

    private CronExpression parseCronExpression(String cronExpression) {
        try {
            return CronExpression.parse(cronExpression);
        } catch (IllegalArgumentException ex) {
            throw invalidSchedule("Invalid cronExpression");
        }
    }

    private BadRequestException invalidSchedule(String message) {
        return new BadRequestException(ErrorCode.INVALID_JOB_SCHEDULE, message);
    }
}
