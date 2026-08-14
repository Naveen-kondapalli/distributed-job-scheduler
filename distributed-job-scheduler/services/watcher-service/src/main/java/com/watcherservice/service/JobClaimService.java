package com.watcherservice.service;

import com.watcherservice.entity.JobEntity;
import com.watcherservice.entity.JobRunEntity;
import com.watcherservice.enums.JobRunStatus;
import com.watcherservice.enums.JobStatus;
import com.watcherservice.enums.ScheduleType;
import com.watcherservice.repository.JobRepository;
import com.watcherservice.repository.JobRunRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobClaimService {

    private final JobRepository jobRepository;
    private final JobRunRepository jobRunRepository;

    @Transactional
    public void claimDueOccurrences(LocalDateTime now, int batchSize) {
        List<JobEntity> dueJobs = jobRepository.findDueJobsForUpdateSkipLocked(
                JobStatus.ACTIVE.name(),
                now,
                batchSize
        );

        dueJobs.forEach(this::claimOccurrence);
    }

    private void claimOccurrence(JobEntity job) {
        LocalDateTime scheduledAt = job.getNextRunAt();

        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setJob(job);
        jobRun.setStatus(JobRunStatus.QUEUED);
        jobRun.setScheduledAt(scheduledAt);
        jobRun.setRetryCount(0);

        JobRunEntity savedRun = jobRunRepository.save(jobRun);

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

    private LocalDateTime nextCronRunAfter(String cronExpression, LocalDateTime after) {
        LocalDateTime nextRunAt = CronExpression.parse(cronExpression).next(after);
        if (nextRunAt == null) {
            throw new IllegalStateException("Cron expression does not produce a next run time for job occurrence");
        }
        return nextRunAt;
    }
}
