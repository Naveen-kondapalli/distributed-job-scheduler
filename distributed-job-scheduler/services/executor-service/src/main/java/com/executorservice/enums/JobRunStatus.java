package com.executorservice.enums;

public enum JobRunStatus {
    QUEUED,
    RUNNING,
    RETRY_SCHEDULED,
    CANCEL_REQUESTED,
    SUCCESS,
    FAILED,
    CANCELLED
}
