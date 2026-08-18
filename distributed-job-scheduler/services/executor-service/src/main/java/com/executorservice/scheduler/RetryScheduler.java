package com.executorservice.scheduler;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.service.RetrySchedulingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final RetrySchedulingService retrySchedulingService;
    private final ExecutorProperties properties;

    @Scheduled(fixedDelayString = "${executor.retry.scheduler-interval-ms:500}")
    public void publishDueRetries() {
        try {
            retrySchedulingService.publishDueRetries(properties.getRetry().getBatchSize());
        } catch (Exception ex) {
            log.error("Failed to schedule due retry events", ex);
        }
    }
}
