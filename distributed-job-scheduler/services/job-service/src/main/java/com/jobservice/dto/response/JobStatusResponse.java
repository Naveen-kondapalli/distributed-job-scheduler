package com.jobservice.dto.response;

import com.jobservice.enums.JobStatus;

public record JobStatusResponse(
        Long jobId,
        JobStatus jobStatus
) {
}
