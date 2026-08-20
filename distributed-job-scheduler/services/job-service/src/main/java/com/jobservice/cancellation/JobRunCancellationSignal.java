package com.jobservice.cancellation;

import java.time.LocalDateTime;

public record JobRunCancellationSignal(
        Long runId,
        LocalDateTime requestedAt,
        String requestedBy
) {
}
