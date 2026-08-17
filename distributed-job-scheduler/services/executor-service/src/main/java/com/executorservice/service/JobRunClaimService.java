package com.executorservice.service;

import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.repository.JobRunRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobRunClaimService {

    private final JobRunRepository jobRunRepository;
    private final String executorInstanceId;

    @Transactional
    public ClaimResult claim(JobRunQueuedEvent event) {
        JobRunEntity run = jobRunRepository.findWithJobById(event.runId()).orElse(null);
        if (run == null) {
            return new ClaimResult(ClaimResult.Outcome.NOT_FOUND, null, null);
        }
        if (!run.getId().equals(event.runId()) || !run.getJob().getId().equals(event.jobId())) {
            return new ClaimResult(ClaimResult.Outcome.INCONSISTENT_EVENT, run, run.getStatus());
        }
        if (run.getStatus() != JobRunStatus.QUEUED) {
            return new ClaimResult(ClaimResult.Outcome.DUPLICATE_OR_TERMINAL, run, run.getStatus());
        }

        int claimed = jobRunRepository.claimQueuedRun(
                event.runId(),
                executorInstanceId,
                LocalDateTime.now(),
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
}
