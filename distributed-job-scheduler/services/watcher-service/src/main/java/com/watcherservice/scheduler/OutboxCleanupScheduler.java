package com.watcherservice.scheduler;

import com.watcherservice.service.OutboxEventStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupScheduler {

    private final OutboxEventStateService outboxEventStateService;

    @Value("${outbox.cleanup.retention-days:7}")
    private int retentionDays;

    @Scheduled(fixedDelayString = "${outbox.cleanup.interval-ms:3600000}")
    public void cleanupPublishedEvents() {
        int deleted = outboxEventStateService.cleanupPublishedOlderThan(retentionDays);
        if (deleted > 0) {
            log.info("Cleaned up published outbox events: count={}", deleted);
        }
    }
}
