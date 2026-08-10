package com.jobservice.service;

import com.jobservice.dto.request.CreateJobRequest;
import com.jobservice.dto.request.UpdateJobRequest;
import com.jobservice.dto.response.JobResponse;
import com.jobservice.dto.response.JobStatusResponse;
import com.jobservice.entity.Job;
import com.jobservice.entity.User;
import com.jobservice.enums.JobStatus;
import com.jobservice.enums.ScheduleType;
import com.jobservice.exception.BadRequestException;
import com.jobservice.exception.ConflictException;
import com.jobservice.exception.ErrorCode;
import com.jobservice.exception.ResourceNotFoundException;
import com.jobservice.mapper.JobMapper;
import com.jobservice.repository.JobRepository;
import com.jobservice.repository.UserRepository;
import com.jobservice.service.interfaces.JobServiceInterface;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class JobService implements JobServiceInterface {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final String JOB_NOT_FOUND_MESSAGE = "Job not found";
    private static final String CANCELLED_JOB_MESSAGE = "Cancelled jobs cannot be modified";

    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final JobMapper jobMapper;

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

        return jobMapper.toResponse(job);
    }

    @Override
    @Transactional
    public void deleteJob(Long jobId, Long userId) {
        Job job = findOwnedJob(jobId, userId);
        jobRepository.delete(job);
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
            if (!scheduledTime.isAfter(LocalDateTime.now())) {
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
            if (scheduledTime != null) {
                throw invalidSchedule("For CRON jobs, scheduledTime must not be defined");
            }
        }
    }

    private BadRequestException invalidSchedule(String message) {
        return new BadRequestException(ErrorCode.INVALID_JOB_SCHEDULE, message);
    }
}
