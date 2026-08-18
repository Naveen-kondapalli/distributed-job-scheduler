package com.executorservice.dto;

import com.executorservice.enums.FailureCategory;
import java.time.LocalDateTime;

public record JobRunDeadEvent(
        String eventId,
        int eventVersion,
        String eventType,
        Long runId,
        Long jobId,
        int retryCount,
        int maxRetries,
        LocalDateTime scheduledAt,
        LocalDateTime failedAt,
        FailureCategory failureCategory,
        String failureReason
) {
}
