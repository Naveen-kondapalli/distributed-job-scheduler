package com.watcherservice.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.watcherservice.entity.OutboxEventEntity;
import com.watcherservice.enums.OutboxStatus;
import com.watcherservice.observability.WatcherMetrics;
import com.watcherservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxEventStateService outboxEventStateService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxPublisherService outboxPublisherService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        outboxPublisherService = new OutboxPublisherService(outboxEventStateService, kafkaTemplate, new ObjectMapper(), new WatcherMetrics(meterRegistry, outboxEventRepository));
        ReflectionTestUtils.setField(outboxPublisherService, "batchSize", 100);
        ReflectionTestUtils.setField(outboxPublisherService, "processingTimeoutMs", 30000L);
        ReflectionTestUtils.setField(outboxPublisherService, "baseDelayMs", 1000L);
        ReflectionTestUtils.setField(outboxPublisherService, "maxDelayMs", 30000L);
        ReflectionTestUtils.setField(outboxPublisherService, "maxAttempts", 10);
        ReflectionTestUtils.setField(outboxPublisherService, "sendTimeoutMs", 10000L);
    }

    @Test
    void successfulPublishMarksEventPublishedAfterKafkaAck() {
        OutboxEventEntity event = event();
        when(outboxEventStateService.claimPublishableEvents(100, 10, 30000L)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult()));

        outboxPublisherService.publishPendingEvents();

        verify(outboxEventStateService).markPublished(1L);
        verify(outboxEventStateService, never()).markFailedOrRetryable(any(), any(), any(Long.class), any(Long.class), any(Integer.class));
    }

    @Test
    void kafkaFailureKeepsEventRetryable() {
        OutboxEventEntity event = event();
        when(outboxEventStateService.claimPublishableEvents(100, 10, 30000L)).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("kafka unavailable"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);
        when(outboxEventStateService.markFailedOrRetryable(
                org.mockito.ArgumentMatchers.eq(1L),
                any(Exception.class),
                org.mockito.ArgumentMatchers.eq(1000L),
                org.mockito.ArgumentMatchers.eq(30000L),
                org.mockito.ArgumentMatchers.eq(10)
        )).thenReturn(event);

        outboxPublisherService.publishPendingEvents();

        verify(outboxEventStateService).markFailedOrRetryable(
                org.mockito.ArgumentMatchers.eq(1L),
                any(Exception.class),
                org.mockito.ArgumentMatchers.eq(1000L),
                org.mockito.ArgumentMatchers.eq(30000L),
                org.mockito.ArgumentMatchers.eq(10)
        );
        verify(outboxEventStateService, never()).markPublished(1L);
    }

    @Test
    void malformedPayloadIsHandledAsPublishFailure() {
        OutboxEventEntity event = event();
        event.setPayload("{not-json");
        when(outboxEventStateService.claimPublishableEvents(100, 10, 30000L)).thenReturn(List.of(event));
        when(outboxEventStateService.markFailedOrRetryable(
                org.mockito.ArgumentMatchers.eq(1L),
                any(Exception.class),
                org.mockito.ArgumentMatchers.eq(1000L),
                org.mockito.ArgumentMatchers.eq(30000L),
                org.mockito.ArgumentMatchers.eq(10)
        )).thenReturn(event);

        outboxPublisherService.publishPendingEvents();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(outboxEventStateService).markFailedOrRetryable(
                org.mockito.ArgumentMatchers.eq(1L),
                any(Exception.class),
                org.mockito.ArgumentMatchers.eq(1000L),
                org.mockito.ArgumentMatchers.eq(30000L),
                org.mockito.ArgumentMatchers.eq(10)
        );
    }

    private OutboxEventEntity event() {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(1L);
        event.setEventId("event-1");
        event.setAggregateId(50L);
        event.setEventType("JOB_RUN_QUEUED");
        event.setTopic("run");
        event.setMessageKey("50");
        event.setPayload("""
                {"eventId":"event-1","eventVersion":1,"runId":50,"jobId":10,"scheduledAt":"2026-08-12T15:45:00","eventType":"JOB_RUN_QUEUED","occurredAt":"2026-08-12T15:45:30"}
                """);
        event.setStatus(OutboxStatus.PROCESSING);
        event.setAttemptCount(1);
        return event;
    }

    private SendResult<String, String> sendResult() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("run", 0), 10, 0, 0, 0, 0);
        return new SendResult<>(null, metadata);
    }
}
