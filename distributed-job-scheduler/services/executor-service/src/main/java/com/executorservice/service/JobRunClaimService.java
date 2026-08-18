package com.executorservice.service;

import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.config.ExecutorProperties;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.repository.JobRunRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobRunClaimService {

    private final JobRunRepository jobRunRepository;
    private final String executorInstanceId;
    private final Clock clock;
    private final ExecutorProperties properties;

    @Transactional
    public ClaimResult claim(JobRunQueuedEvent event) {
        JobRunEntity run = jobRunRepository.findWithJobById(event.runId()).orElse(null);
        if (run == null) {
            return new ClaimResult(ClaimResult.Outcome.NOT_FOUND, null, null);
        }
        if (!run.getId().equals(event.runId()) || !run.getJob().getId().equals(event.jobId())) {
            return new ClaimResult(ClaimResult.Outcome.INCONSISTENT_EVENT, run, run.getStatus());
        }
        if (run.getStatus() == JobRunStatus.RUNNING) {
            return reclaimIfStale(event.runId(), run);
        }
        if (run.getStatus() != JobRunStatus.QUEUED) {
            return new ClaimResult(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL, run, run.getStatus());
        }

        int claimed = jobRunRepository.claimQueuedRun(
                event.runId(),
                executorInstanceId,
                LocalDateTime.now(clock),
                JobRunStatus.QUEUED,
                JobRunStatus.RUNNING
        );
        if (claimed != 1) {
            JobRunEntity current = jobRunRepository.findWithJobById(event.runId()).orElse(run);
            return new ClaimResult(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL, current, current.getStatus());
        }

        JobRunEntity claimedRun = jobRunRepository.findWithJobById(event.runId()).orElseThrow();
        return new ClaimResult(ClaimResult.Outcome.CLAIMED, claimedRun, JobRunStatus.RUNNING);
    }

    @Transactional
    public ClaimResult claimRetry(com.executorservice.dto.JobRunRetryEvent event) {
        JobRunEntity run = jobRunRepository.findWithJobById(event.runId()).orElse(null);
        if (run == null) {
            return new ClaimResult(ClaimResult.Outcome.NOT_FOUND, null, null);
        }
        if (!run.getId().equals(event.runId()) || !run.getJob().getId().equals(event.jobId())) {
            return new ClaimResult(ClaimResult.Outcome.INCONSISTENT_EVENT, run, run.getStatus());
        }
        if (run.getStatus() == JobRunStatus.RUNNING && safeInt(run.getRetryCount()) == event.retryCount()) {
            return reclaimIfStale(event.runId(), run);
        }
        if (run.getStatus() != JobRunStatus.RETRY_SCHEDULED
                || safeInt(run.getRetryCount()) != event.retryCount()
                || run.getNextRetryAt() != null) {
            return new ClaimResult(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL, run, run.getStatus());
        }

        int claimed = jobRunRepository.claimRetryRun(
                event.runId(),
                event.retryCount(),
                executorInstanceId,
                LocalDateTime.now(clock),
                JobRunStatus.RETRY_SCHEDULED,
                JobRunStatus.RUNNING
        );
        if (claimed != 1) {
            JobRunEntity current = jobRunRepository.findWithJobById(event.runId()).orElse(run);
            return new ClaimResult(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL, current, current.getStatus());
        }

        JobRunEntity claimedRun = jobRunRepository.findWithJobById(event.runId()).orElseThrow();
        return new ClaimResult(ClaimResult.Outcome.CLAIMED, claimedRun, JobRunStatus.RUNNING);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private ClaimResult reclaimIfStale(Long runId, JobRunEntity run) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime staleBefore = now.minusNanos(properties.getExecution().getRunningTimeoutMs() * 1_000_000);
        if (run.getStartedAt() == null || !run.getStartedAt().isBefore(staleBefore)) {
            return new ClaimResult(ClaimResult.Outcome.FRESH_RUNNING, run, run.getStatus());
        }
        int reclaimed = jobRunRepository.reclaimStaleRunningRun(
                runId,
                executorInstanceId,
                now,
                staleBefore,
                JobRunStatus.RUNNING
        );
        if (reclaimed != 1) {
            JobRunEntity current = jobRunRepository.findWithJobById(runId).orElse(run);
            return new ClaimResult(ClaimResult.Outcome.FRESH_RUNNING, current, current.getStatus());
        }
        JobRunEntity reclaimedRun = jobRunRepository.findWithJobById(runId).orElseThrow();
        return new ClaimResult(ClaimResult.Outcome.CLAIMED, reclaimedRun, JobRunStatus.RUNNING);
    }
}
