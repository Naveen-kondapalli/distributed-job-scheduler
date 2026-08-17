package com.executorservice.service;

import com.executorservice.enums.JobRunStatus;
import com.executorservice.repository.JobRunRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobRunCompletionService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final JobRunRepository jobRunRepository;
    private final String executorInstanceId;

    @Transactional
    public void markSuccess(Long runId) {
        int updated = jobRunRepository.markSuccess(
                runId,
                executorInstanceId,
                LocalDateTime.now(),
                JobRunStatus.RUNNING,
                JobRunStatus.SUCCESS
        );
        if (updated != 1) {
            throw new IllegalStateException("Unable to mark JobRun SUCCESS; ownership or status changed");
        }
    }

    @Transactional
    public void markFailed(Long runId, String errorMessage) {
        int updated = jobRunRepository.markFailed(
                runId,
                executorInstanceId,
                LocalDateTime.now(),
                sanitize(errorMessage),
                JobRunStatus.RUNNING,
                JobRunStatus.FAILED
        );
        if (updated != 1) {
            throw new IllegalStateException("Unable to mark JobRun FAILED; ownership or status changed");
        }
    }

    private String sanitize(String message) {
        String safe = message == null || message.isBlank() ? "Execution failed" : message.replaceAll("[\\r\\n\\t]+", " ");
        return safe.length() <= MAX_ERROR_MESSAGE_LENGTH ? safe : safe.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
