package com.jobservice.dto.response;

import com.jobservice.enums.JobRunStatus;

public record JobRunStatusResponse(
        Long jobId,
        Long runId,
        JobRunStatus status
) {
}
