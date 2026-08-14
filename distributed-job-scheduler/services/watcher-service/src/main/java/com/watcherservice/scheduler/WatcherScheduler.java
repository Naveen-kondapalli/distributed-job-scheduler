package com.watcherservice.scheduler;

import com.watcherservice.service.JobPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WatcherScheduler {

    private final JobPollingService jobPollingService;

    @Scheduled(fixedDelayString = "${watcher.polling.interval-ms:1000}")
    public void pollDueFutureJobs() {
        try {
            jobPollingService.pollDueFutureJobs();
        } catch (Exception ex) {
            log.error("Failed to poll due future jobs", ex);
        }
    }
}
