package com.executorservice.service;

import com.executorservice.entity.JobRunEntity;
import com.executorservice.repository.JobRunRepository;
import com.executorservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final io.micrometer.core.instrument.Counter retryEventCreatedCounter;

    public RetrySchedulingService(
            JobRunRepository jobRunRepository,
            OutboxEventRepository outboxEventRepository,
            OutboxEventFactory outboxEventFactory,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.jobRunRepository = jobRunRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventFactory = outboxEventFactory;
        this.clock = clock;
        this.retryEventCreatedCounter = meterRegistry.counter("executor.retry.event.created");
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
                    com.executorservice.enums.FailureCategory.RETRYABLE
            );
            outboxEventRepository.findByEventId(retryEvent.getEventId()).orElseGet(() -> outboxEventRepository.save(retryEvent));
            retryEventCreatedCounter.increment();
            log.info("Due retry event created: runId={}, retryCount={}", run.getId(), run.getRetryCount());
        }
        return dueRuns.size();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
