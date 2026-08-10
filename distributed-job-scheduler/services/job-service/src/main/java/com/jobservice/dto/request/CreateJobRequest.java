package com.jobservice.dto.request;

import com.jobservice.enums.JobType;
import com.jobservice.enums.ScheduleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Map;

public record CreateJobRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        String description,

        @NotNull
        JobType jobType,

        @NotNull
        ScheduleType scheduleType,

        LocalDateTime scheduledTime,

        String cronExpression,

        @NotNull
        @NotEmpty
        Map<String, Object> payload,

        @Min(0)
        @Max(10)
        Integer maxRetries
) {
}
