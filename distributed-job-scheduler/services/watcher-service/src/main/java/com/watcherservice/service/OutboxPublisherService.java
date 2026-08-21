package com.watcherservice.service;

import com.watcherservice.entity.OutboxEventEntity;
import com.watcherservice.observability.WatcherMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final OutboxEventStateService outboxEventStateService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final WatcherMetrics metrics;

    @Value("${outbox.publisher.batch-size:100}")
    private int batchSize;

    @Value("${outbox.publisher.processing-timeout-ms:30000}")
    private long processingTimeoutMs;

    @Value("${outbox.publisher.retry.base-delay-ms:1000}")
    private long baseDelayMs;

    @Value("${outbox.publisher.retry.max-delay-ms:30000}")
    private long maxDelayMs;

    @Value("${outbox.publisher.max-attempts:10}")
    private int maxAttempts;

    @Value("${outbox.publisher.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    public void publishPendingEvents() {
        List<OutboxEventEntity> events = outboxEventStateService.claimPublishableEvents(
                batchSize,
                maxAttempts,
                processingTimeoutMs
        );

        events.forEach(this::publishOne);
    }

    private void publishOne(OutboxEventEntity event) {
        long started = metrics.outboxPublishStarted();
        putMdc(event);
        try {
            validatePayload(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    event.getTopic(),
                    event.getMessageKey(),
                    event.getPayload()
            );
            addHeader(record, "eventId", event.getEventId());
            addHeader(record, "eventType", event.getEventType());
            addHeader(record, "eventVersion", "1");

            SendResult<String, String> result = kafkaTemplate.send(record)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            outboxEventStateService.markPublished(event.getId());
            metrics.outboxPublish(event.getTopic(), "success");
            metrics.outboxPublishDuration(event.getTopic(), started);

            log.info(
                    "Outbox event published: eventId={}, runId={}, topic={}, partition={}, offset={}",
                    event.getEventId(),
                    event.getAggregateId(),
                    event.getTopic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset()
            );
        } catch (Exception ex) {
            OutboxEventEntity updated = outboxEventStateService.markFailedOrRetryable(
                    event.getId(),
                    ex,
                    baseDelayMs,
                    maxDelayMs,
                    maxAttempts
            );
            metrics.outboxPublish(event.getTopic(), "failure");
            metrics.outboxPublishDuration(event.getTopic(), started);
            log.warn(
                    "Outbox publish failed: eventId={}, attempt={}, nextAttemptAt={}",
                    event.getEventId(),
                    event.getAttemptCount(),
                    updated.getNextAttemptAt(),
                    ex
            );
        } finally {
            MDC.clear();
        }
    }

    private void putMdc(OutboxEventEntity event) {
        if (event.getEventId() != null) {
            MDC.put("eventId", event.getEventId());
        }
        if (event.getAggregateId() != null) {
            MDC.put("runId", String.valueOf(event.getAggregateId()));
        }
    }

    private void validatePayload(OutboxEventEntity event) throws Exception {
        objectMapper.readTree(event.getPayload());
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }
}
