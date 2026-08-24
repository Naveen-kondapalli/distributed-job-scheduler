package com.executorservice.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.service.JobExecutionService;
import java.time.LocalDateTime;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

class JobRunEventConsumerMdcTest {

    @Test
    void clearsMdcAfterConsumingRunEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JobExecutionService jobExecutionService = org.mockito.Mockito.mock(JobExecutionService.class);
        Acknowledgment acknowledgment = org.mockito.Mockito.mock(Acknowledgment.class);
        JobRunEventConsumer consumer = new JobRunEventConsumer(objectMapper, jobExecutionService);
        JobRunQueuedEvent event = new JobRunQueuedEvent(
                "event-1",
                1,
                100L,
                25L,
                LocalDateTime.of(2026, 8, 21, 10, 0),
                "JOB_RUN_QUEUED",
                LocalDateTime.of(2026, 8, 21, 10, 0)
        );
        when(jobExecutionService.process(any())).thenAnswer(invocation -> {
            assertThat(MDC.get("eventId")).isEqualTo("event-1");
            assertThat(MDC.get("jobId")).isEqualTo("25");
            assertThat(MDC.get("runId")).isEqualTo("100");
            return JobExecutionService.ProcessingDecision.ack();
        });

        consumer.consume(new ConsumerRecord<>("run", 0, 1L, "100", objectMapper.writeValueAsString(event)), acknowledgment);

        assertThat(MDC.get("eventId")).isNull();
        assertThat(MDC.get("jobId")).isNull();
        assertThat(MDC.get("runId")).isNull();
    }
}
