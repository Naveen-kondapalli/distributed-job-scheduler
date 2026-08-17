package com.executorservice.http;

public record HttpExecutionResult(
        boolean success,
        int statusCode,
        String failureReason,
        long durationMs
) {
}
