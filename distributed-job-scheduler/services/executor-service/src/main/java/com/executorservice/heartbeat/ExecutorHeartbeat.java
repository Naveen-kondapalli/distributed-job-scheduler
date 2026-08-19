package com.executorservice.heartbeat;

import java.time.LocalDateTime;

public record ExecutorHeartbeat(
        String executorId,
        String serviceName,
        LocalDateTime startedAt,
        LocalDateTime lastHeartbeatAt,
        String hostname,
        String applicationVersion,
        String instanceToken
) {
}
