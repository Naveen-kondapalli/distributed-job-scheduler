package com.executorservice.service;

import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.FailureCategory;
import com.executorservice.repository.JobRunRepository;
import com.executorservice.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class RetrySchedulingService {

    private final JobRunRepository jobRunRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final Clock clock;

    public RetrySchedulingService(
            JobRunRepository jobRunRepository,
            OutboxEventRepository outboxEventRepository,
            OutboxEventFactory outboxEventFactory,
            Clock clock
    ) {
        this.jobRunRepository = jobRunRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventFactory = outboxEventFactory;
        this.clock = clock;
    }

    @Transactional
    public int publishDueRetries(int batchSize) {
        List<JobRunEntity> dueRuns = jobRunRepository.findDueRetriesForUpdateSkipLocked(LocalDateTime.now(clock), batchSize);
        for (JobRunEntity run : dueRuns) {
            LocalDateTime retryAt = run.getNextRetryAt();
            run.setNextRetryAt(null);
            var retryEvent = outboxEventFactory.retryEvent(
                    run,
                    safeInt(run.getRetryCount()),
                    retryAt == null ? LocalDateTime.now(clock) : retryAt,
                    FailureCategory.RETRYABLE
            );
            outboxEventRepository.findByEventId(retryEvent.getEventId()).orElseGet(() -> outboxEventRepository.save(retryEvent));
            log.info("Due retry event created: runId={}, retryCount={}", run.getId(), run.getRetryCount());
        }
        return dueRuns.size();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
