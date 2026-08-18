package com.executorservice.service;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.dto.JobRunDeadEvent;
import com.executorservice.dto.JobRunRetryEvent;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.entity.OutboxEventEntity;
import com.executorservice.enums.FailureCategory;
import com.executorservice.enums.OutboxStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OutboxEventFactory {

    public static final int EVENT_VERSION = 1;
    public static final String RETRY_EVENT_TYPE = "JOB_RUN_RETRY_SCHEDULED";
    public static final String DEAD_EVENT_TYPE = "JOB_RUN_DEAD";

    private static final String AGGREGATE_TYPE_JOB_RUN = "JOB_RUN";

    private final ExecutorProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxEventEntity retryEvent(JobRunEntity run, int retryCount, LocalDateTime retryAt, FailureCategory failureCategory) {
        String eventId = deterministicEventId("retry:%d:%d".formatted(run.getId(), retryCount));
        JobRunRetryEvent event = new JobRunRetryEvent(
                eventId,
                EVENT_VERSION,
                RETRY_EVENT_TYPE,
                run.getId(),
                run.getJob().getId(),
                retryCount,
                run.getScheduledAt(),
                retryAt,
                LocalDateTime.now(clock),
                failureCategory
        );
        return outboxEvent(eventId, run.getId(), RETRY_EVENT_TYPE, properties.getKafka().getRetryTopic(), serialize(event));
    }

    public OutboxEventEntity deadEvent(JobRunEntity run, FailureCategory failureCategory, String failureReason) {
        String eventId = deterministicEventId("dead:%d".formatted(run.getId()));
        JobRunDeadEvent event = new JobRunDeadEvent(
                eventId,
                EVENT_VERSION,
                DEAD_EVENT_TYPE,
                run.getId(),
                run.getJob().getId(),
                safeInt(run.getRetryCount()),
                safeInt(run.getJob().getMaxRetries()),
                run.getScheduledAt(),
                LocalDateTime.now(clock),
                failureCategory,
                failureReason
        );
        return outboxEvent(eventId, run.getId(), DEAD_EVENT_TYPE, properties.getKafka().getDeadTopic(), serialize(event));
    }

    private OutboxEventEntity outboxEvent(String eventId, Long runId, String eventType, String topic, String payload) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventId(eventId);
        event.setAggregateType(AGGREGATE_TYPE_JOB_RUN);
        event.setAggregateId(runId);
        event.setEventType(eventType);
        event.setTopic(topic);
        event.setMessageKey(String.valueOf(runId));
        event.setPayload(payload);
        event.setStatus(OutboxStatus.PENDING);
        event.setAttemptCount(0);
        return event;
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize executor outbox event", ex);
        }
    }

    private String deterministicEventId(String identity) {
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
