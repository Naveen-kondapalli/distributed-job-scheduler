package com.watcherservice.scheduler;

import com.watcherservice.service.OutboxPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final OutboxPublisherService outboxPublisherService;

    @Scheduled(fixedDelayString = "${outbox.publisher.interval-ms:500}")
    public void publishPendingEvents() {
        try {
            outboxPublisherService.publishPendingEvents();
        } catch (Exception ex) {
            log.error("Failed to publish pending outbox events", ex);
        }
    }
}
