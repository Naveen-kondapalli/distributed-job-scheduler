package com.watchservice.service;

import com.watcherservice.entity.JobEntity;
import com.watcherservice.enums.JobStatus;
import com.watcherservice.enums.ScheduleType;
import com.watcherservice.repository.JobRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobPollingService {

    private static final Sort DUE_JOB_SORT = Sort.by(
            Sort.Order.asc("scheduledTime"),
            Sort.Order.asc("id")
    );

    private final JobRepository jobRepository;
    private final Clock clock;

    @Value("${watcher.polling.batch-size:100}")
    private int batchSize;

    @Transactional(readOnly = true)
    public void pollDueFutureJobs() {
        LocalDateTime now = LocalDateTime.now(clock);
        PageRequest pageRequest = PageRequest.of(0, batchSize, DUE_JOB_SORT);

        jobRepository.findByStatusAndScheduleTypeAndScheduledTimeIsNotNullAndScheduledTimeLessThanEqual(
                        JobStatus.ACTIVE,
                        ScheduleType.FUTURE,
                        now,
                        pageRequest
                )
                .forEach(this::logDueJob);
    }

    private void logDueJob(JobEntity job) {
        // Duplicate polling is expected in this phase because Kafka publishing,
        // status transitions, Redis locking, and JobRun creation are intentionally deferred.
        log.info(
                "Due job found: id={}, name={}, scheduledTime={}",
                job.getId(),
                job.getName(),
                job.getScheduledTime()
        );
    }
}
