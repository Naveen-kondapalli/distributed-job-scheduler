package com.executorservice.service;

import com.executorservice.entity.JobRunEntity;
import com.executorservice.entity.OutboxEventEntity;
import com.executorservice.enums.FailureCategory;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.http.HttpExecutionResult;
import com.executorservice.repository.JobRunRepository;
import com.executorservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ExecutionFailureHandler {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final JobRunRepository jobRunRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final RetryBackoffService retryBackoffService;
    private final String executorInstanceId;
    private final Clock clock;
    private final Counter retryScheduledCounter;
    private final Counter retryExhaustedCounter;
    private final Counter deadCreatedCounter;

    public ExecutionFailureHandler(
            JobRunRepository jobRunRepository,
            OutboxEventRepository outboxEventRepository,
            OutboxEventFactory outboxEventFactory,
            RetryBackoffService retryBackoffService,
            String executorInstanceId,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.jobRunRepository = jobRunRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventFactory = outboxEventFactory;
        this.retryBackoffService = retryBackoffService;
        this.executorInstanceId = executorInstanceId;
        this.clock = clock;
        this.retryScheduledCounter = meterRegistry.counter("executor.retry.scheduled");
        this.retryExhaustedCounter = meterRegistry.counter("executor.retry.exhausted");
        this.deadCreatedCounter = meterRegistry.counter("executor.dead.created");
    }

    @Transactional
    public FailureDecision handleFailure(JobRunEntity run, HttpExecutionResult result) {
        String safeReason = sanitize(result.failureReason());
        int currentRetryCount = safeInt(run.getRetryCount());
        int maxRetries = safeInt(run.getJob().getMaxRetries());
        FailureCategory category = result.failureCategory() == null ? FailureCategory.NON_RETRYABLE : result.failureCategory();

        if (category == FailureCategory.RETRYABLE && currentRetryCount < maxRetries) {
            int nextRetryCount = currentRetryCount + 1;
            LocalDateTime retryAt = LocalDateTime.now(clock).plus(retryBackoffService.calculateDelay(nextRetryCount, result.retryAfter()));
            int updated = jobRunRepository.scheduleRetryAfterFailure(
                    run.getId(),
                    executorInstanceId,
                    currentRetryCount,
                    nextRetryCount,
                    retryAt,
                    safeReason,
                    JobRunStatus.RUNNING,
                    JobRunStatus.RETRY_SCHEDULED
            );
            if (updated != 1) {
                throw new IllegalStateException("Unable to schedule retry; ownership, status, or retryCount changed");
            }
            retryScheduledCounter.increment();
            log.info("Retry scheduled: runId={}, retryCount={}, retryAt={}, reason={}", run.getId(), nextRetryCount, retryAt, safeReason);
            return FailureDecision.RETRY_SCHEDULED;
        }

        markTerminalFailedWithDeadEvent(run, category, safeReason);
        if (category == FailureCategory.RETRYABLE) {
            retryExhaustedCounter.increment();
            log.info("Retries exhausted: runId={}, retryCount={}", run.getId(), currentRetryCount);
        }
        return FailureDecision.DEAD;
    }

    private void markTerminalFailedWithDeadEvent(JobRunEntity run, FailureCategory category, String safeReason) {
        int updated = jobRunRepository.markFailed(
                run.getId(),
                executorInstanceId,
                LocalDateTime.now(clock),
                safeReason,
                JobRunStatus.RUNNING,
                JobRunStatus.FAILED
        );
        if (updated != 1) {
            throw new IllegalStateException("Unable to mark terminal FAILED; ownership or status changed");
        }
        JobRunEntity failed = jobRunRepository.findWithJobById(run.getId()).orElseThrow();
        OutboxEventEntity deadEvent = outboxEventFactory.deadEvent(failed, category, safeReason);
        outboxEventRepository.findByEventId(deadEvent.getEventId()).orElseGet(() -> outboxEventRepository.save(deadEvent));
        deadCreatedCounter.increment();
        log.info("Dead event created: runId={}", run.getId());
    }

    private String sanitize(String message) {
        String safe = message == null || message.isBlank() ? "Execution failed" : message.replaceAll("[\\r\\n\\t]+", " ");
        return safe.length() <= MAX_ERROR_MESSAGE_LENGTH ? safe : safe.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    public enum FailureDecision {
        RETRY_SCHEDULED,
        DEAD
    }
}
