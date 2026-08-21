package com.watcherservice.observability;

import com.watcherservice.enums.OutboxStatus;
import com.watcherservice.enums.ScheduleType;
import com.watcherservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WatcherMetrics {

    private final MeterRegistry meterRegistry;
    private final OutboxEventRepository outboxEventRepository;

    @PostConstruct
    void registerGauges() {
        for (OutboxStatus status : new OutboxStatus[]{OutboxStatus.PENDING, OutboxStatus.PROCESSING, OutboxStatus.FAILED}) {
            meterRegistry.gauge(
                    "scheduler.outbox.events",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("status", status.name())),
                    outboxEventRepository,
                    repository -> repository.countByStatus(status)
            );
        }
    }

    public void jobClaimed(ScheduleType scheduleType) {
        meterRegistry.counter("scheduler.watcher.jobs.claimed", "schedule_type", scheduleType.name()).increment();
    }

    public Timer.Sample pollStarted() {
        return Timer.start(meterRegistry);
    }

    public void pollCompleted(Timer.Sample sample) {
        sample.stop(meterRegistry.timer("scheduler.watcher.poll.duration"));
    }

    public void outboxPublish(String topic, String result) {
        meterRegistry.counter("scheduler.outbox.publish", "topic", topic, "result", result).increment();
    }

    public long outboxPublishStarted() {
        return System.nanoTime();
    }

    public void outboxPublishDuration(String topic, long startNanos) {
        meterRegistry.timer("scheduler.outbox.publish.duration", "topic", topic)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
