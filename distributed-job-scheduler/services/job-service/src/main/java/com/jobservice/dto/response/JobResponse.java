package com.jobservice.dto.response;

import com.jobservice.enums.JobStatus;
import com.jobservice.enums.JobType;
import com.jobservice.enums.ScheduleType;
import java.time.LocalDateTime;
import java.util.Map;

public record JobResponse(
        Long id,
        String name,
        String description,
        JobType jobType,
        ScheduleType scheduleType,
        LocalDateTime scheduledTime,
        String cronExpression,
        Map<String, Object> payload,
        JobStatus status,
        Integer maxRetries,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
