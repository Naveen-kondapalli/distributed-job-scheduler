package com.executorservice.observability;

import com.executorservice.enums.FailureCategory;
import com.executorservice.enums.JobType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExecutorMetrics {

    private final MeterRegistry meterRegistry;

    public void execution(JobType jobType, String result) {
        meterRegistry.counter("scheduler.executor.execution", "job_type", jobType.name(), "result", result).increment();
    }

    public void executionDuration(JobType jobType, String result, long durationMs) {
        meterRegistry.timer("scheduler.executor.execution.duration", "job_type", jobType.name(), "result", result)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void httpRequest(String outcome) {
        meterRegistry.counter("scheduler.executor.http.requests", "outcome", outcome).increment();
    }

    public void retryScheduled(FailureCategory category) {
        meterRegistry.counter("scheduler.executor.retry.scheduled", "failure_category", category.name()).increment();
    }

    public void retryExhausted(FailureCategory category) {
        meterRegistry.counter("scheduler.executor.retry.exhausted", "failure_category", category.name()).increment();
    }

    public void retrySuccess() {
        meterRegistry.counter("scheduler.executor.retry.success").increment();
    }

    public void deadCreated(FailureCategory category) {
        meterRegistry.counter("scheduler.executor.dead.created", "failure_category", category.name()).increment();
    }

    public void cancellationCompleted() {
        meterRegistry.counter("scheduler.executor.cancellation.completed").increment();
    }

    public void cancellationSignalFailure() {
        meterRegistry.counter("scheduler.executor.cancellation.signal.failure").increment();
    }

    public void heartbeat(String result) {
        meterRegistry.counter("scheduler.executor.heartbeat", "result", result).increment();
    }

    public void duplicateKafkaEvent(String topic) {
        meterRegistry.counter("scheduler.executor.kafka.duplicate", "topic", topic).increment();
    }
}
