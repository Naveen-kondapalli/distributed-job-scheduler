package com.executorservice.dto;

import com.executorservice.enums.FailureCategory;
import java.time.LocalDateTime;

public record JobRunRetryEvent(
        String eventId,
        int eventVersion,
        String eventType,
        Long runId,
        Long jobId,
        int retryCount,
        LocalDateTime scheduledAt,
        LocalDateTime retryAt,
        LocalDateTime occurredAt,
        FailureCategory failureCategory
) {
}
