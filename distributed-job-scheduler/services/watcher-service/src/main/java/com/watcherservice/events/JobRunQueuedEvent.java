package com.watcherservice.events;

import java.time.LocalDateTime;

public record JobRunQueuedEvent(
        String eventId,
        int eventVersion,
        Long runId,
        Long jobId,
        LocalDateTime scheduledAt,
        String eventType,
        LocalDateTime occurredAt
) {
}
