package com.watcherservice.service;

import com.watcherservice.observability.WatcherMetrics;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobPollingService {

    private final JobClaimService jobClaimService;
    private final Clock clock;
    private final WatcherMetrics metrics;

    @Value("${watcher.polling.batch-size:100}")
    private int batchSize;

    public void pollDueFutureJobs() {
        Timer.Sample sample = metrics.pollStarted();
        try {
        LocalDateTime now = LocalDateTime.now(clock);
        jobClaimService.claimDueOccurrences(now, batchSize);
        } finally {
            metrics.pollCompleted(sample);
        }
    }
}
