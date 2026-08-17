package com.executorservice.consumer;

import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.service.JobExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobRunEventConsumer {

    private final ObjectMapper objectMapper;
    private final JobExecutionService jobExecutionService;

    @KafkaListener(
            topics = "${executor.kafka.topic:run}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        JobRunQueuedEvent event;
        try {
            event = objectMapper.readValue(record.value(), JobRunQueuedEvent.class);
        } catch (Exception ex) {
            log.error(
                    "Malformed Kafka record skipped: topic={}, partition={}, offset={}, key={}, reason={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    ex.getMessage()
            );
            acknowledgment.acknowledge();
            return;
        }

        try {
            JobExecutionService.ProcessingDecision decision = jobExecutionService.process(event);
            if (decision.acknowledge()) {
                acknowledgment.acknowledge();
            }
        } catch (Exception ex) {
            log.error(
                    "Kafka record processing failed before durable handling: topic={}, partition={}, offset={}, key={}, reason={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    ex.getMessage(),
                    ex
            );
        }
    }
}
