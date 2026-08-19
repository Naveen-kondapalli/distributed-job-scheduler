package com.executorservice.scheduler;

import com.executorservice.heartbeat.ExecutorHeartbeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExecutorHeartbeatScheduler {

    private final ExecutorHeartbeatService heartbeatService;

    @Scheduled(fixedDelayString = "${executor.heartbeat.interval-ms:10000}")
    public void refreshHeartbeat() {
        heartbeatService.registerOrRefresh();
    }
}
