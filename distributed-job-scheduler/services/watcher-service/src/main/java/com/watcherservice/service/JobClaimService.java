package com.watcherservice.service;

import com.watcherservice.entity.JobEntity;
import com.watcherservice.entity.JobRunEntity;
import com.watcherservice.entity.OutboxEventEntity;
import com.watcherservice.enums.JobRunStatus;
import com.watcherservice.enums.JobStatus;
import com.watcherservice.enums.OutboxStatus;
import com.watcherservice.enums.ScheduleType;
import com.watcherservice.events.JobRunQueuedEvent;
import com.watcherservice.repository.JobRepository;
import com.watcherservice.repository.JobRunRepository;
import com.watcherservice.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobClaimService {

    private static final String AGGREGATE_TYPE_JOB_RUN = "JOB_RUN";
    private static final String EVENT_TYPE_JOB_RUN_QUEUED = "JOB_RUN_QUEUED";
    private static final String RUN_TOPIC = "run";
    private static final int EVENT_VERSION = 1;

    private final JobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void claimDueOccurrences(LocalDateTime now, int batchSize) {
        List<JobEntity> dueJobs = jobRepository.findDueJobsForUpdateSkipLocked(
                JobStatus.ACTIVE.name(),
                now,
                batchSize
        );

        dueJobs.forEach(job -> claimOccurrence(job, now));
    }

    private void claimOccurrence(JobEntity job, LocalDateTime now) {
        LocalDateTime scheduledAt = job.getNextRunAt();

        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setJob(job);
        jobRun.setStatus(JobRunStatus.QUEUED);
        jobRun.setScheduledAt(scheduledAt);
        jobRun.setRetryCount(0);

        JobRunEntity savedRun = jobRunRepository.saveAndFlush(jobRun);

        outboxEventRepository.save(createJobRunQueuedOutboxEvent(job, savedRun, scheduledAt, now));

        if (job.getScheduleType() == ScheduleType.CRON) {
            job.setNextRunAt(nextCronRunAfter(job.getCronExpression(), scheduledAt));
        } else {
            job.setNextRunAt(null);
        }

        log.info(
                "Job occurrence queued: jobId={}, runId={}, scheduledAt={}, nextRunAt={}",
                job.getId(),
                savedRun.getId(),
                scheduledAt,
                job.getNextRunAt()
        );
    }

    private OutboxEventEntity createJobRunQueuedOutboxEvent(
            JobEntity job,
            JobRunEntity jobRun,
            LocalDateTime scheduledAt,
            LocalDateTime occurredAt
    ) {
        String eventId = UUID.randomUUID().toString();
        JobRunQueuedEvent event = new JobRunQueuedEvent(
                eventId,
                EVENT_VERSION,
                jobRun.getId(),
                job.getId(),
                scheduledAt,
                EVENT_TYPE_JOB_RUN_QUEUED,
                occurredAt
        );

        OutboxEventEntity outboxEvent = new OutboxEventEntity();
        outboxEvent.setEventId(eventId);
        outboxEvent.setAggregateType(AGGREGATE_TYPE_JOB_RUN);
        outboxEvent.setAggregateId(jobRun.getId());
        outboxEvent.setEventType(EVENT_TYPE_JOB_RUN_QUEUED);
        outboxEvent.setTopic(RUN_TOPIC);
        outboxEvent.setMessageKey(String.valueOf(jobRun.getId()));
        outboxEvent.setPayload(serialize(event));
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setAttemptCount(0);
        return outboxEvent;
    }

    private String serialize(JobRunQueuedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize JobRun queued outbox event", ex);
        }
    }

    private LocalDateTime nextCronRunAfter(String cronExpression, LocalDateTime after) {
        LocalDateTime nextRunAt = CronExpression.parse(cronExpression).next(after);
        if (nextRunAt == null) {
            throw new IllegalStateException("Cron expression does not produce a next run time for job occurrence");
        }
        return nextRunAt;
    }
}
