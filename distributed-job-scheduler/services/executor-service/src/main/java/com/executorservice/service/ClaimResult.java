package com.executorservice.service;

import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.JobRunStatus;

public record ClaimResult(
        Outcome outcome,
        JobRunEntity jobRun,
        JobRunStatus existingStatus
) {
    public enum Outcome {
        CLAIMED,
        FRESH_RUNNING,
        DUPLICATE_OR_TERMINAL,
        INCONSISTENT_EVENT,
        NOT_FOUND
    }
}
