package com.executorservice.heartbeat;

import org.springframework.stereotype.Component;

@Component
public class ExecutorHeartbeatKeys {

    private static final String PREFIX = "scheduler:executor:heartbeat:";

    public String heartbeatKey(String executorId) {
        return PREFIX + executorId;
    }
}
