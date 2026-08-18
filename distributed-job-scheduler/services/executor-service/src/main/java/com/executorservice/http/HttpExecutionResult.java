package com.executorservice.http;

import com.executorservice.enums.FailureCategory;
import java.time.Duration;

public record HttpExecutionResult(
        boolean success,
        int statusCode,
        FailureCategory failureCategory,
        String failureReason,
        Duration retryAfter,
        long durationMs
) {
}
