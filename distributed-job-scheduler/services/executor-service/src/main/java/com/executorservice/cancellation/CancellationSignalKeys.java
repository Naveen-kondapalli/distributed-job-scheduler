package com.executorservice.cancellation;

import org.springframework.stereotype.Component;

@Component
public class CancellationSignalKeys {

    private static final String PREFIX = "scheduler:execution:cancel:";

    public String key(Long runId) {
        return PREFIX + runId;
    }
}
