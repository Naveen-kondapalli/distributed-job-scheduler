package com.executorservice.scheduler;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.cancellation.ExecutorCancellationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CancellationRecoveryScheduler {

    private final ExecutorCancellationService cancellationService;
    private final ExecutorProperties properties;

    @Scheduled(fixedDelayString = "${executor.cancellation.recovery-interval-ms:5000}")
    public void finalizeStaleCancellationRequests() {
        try {
            cancellationService.cancelStaleRequestedRuns(properties.getExecution().getRunningTimeoutMs());
        } catch (Exception ex) {
            log.error("Failed to finalize stale cancellation requests", ex);
        }
    }
}
