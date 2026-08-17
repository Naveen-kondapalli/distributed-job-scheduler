package com.executorservice.service;

import com.executorservice.dto.HttpJobPayload;
import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.enums.JobType;
import com.executorservice.http.HttpExecutionResult;
import com.executorservice.http.HttpJobExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class JobExecutionService {

    private static final int SUPPORTED_EVENT_VERSION = 1;
    private static final String SUPPORTED_EVENT_TYPE = "JOB_RUN_QUEUED";

    private final JobRunClaimService claimService;
    private final JobRunCompletionService completionService;
    private final HttpJobExecutor httpJobExecutor;
    private final ObjectMapper objectMapper;
    private final String executorInstanceId;
    private final Counter successCounter;
    private final Counter failedCounter;
    private final Counter duplicateCounter;
    private final Timer httpDurationTimer;

    public JobExecutionService(
            JobRunClaimService claimService,
            JobRunCompletionService completionService,
            HttpJobExecutor httpJobExecutor,
            ObjectMapper objectMapper,
            String executorInstanceId,
            MeterRegistry meterRegistry
    ) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.httpJobExecutor = httpJobExecutor;
        this.objectMapper = objectMapper;
        this.executorInstanceId = executorInstanceId;
        this.successCounter = meterRegistry.counter("executor.jobs.success");
        this.failedCounter = meterRegistry.counter("executor.jobs.failed");
        this.duplicateCounter = meterRegistry.counter("executor.jobs.duplicate");
        this.httpDurationTimer = meterRegistry.timer("executor.http.duration");
    }

    public ProcessingDecision process(JobRunQueuedEvent event) {
        ProcessingDecision invalidEvent = validateEvent(event);
        if (invalidEvent != null) {
            return invalidEvent;
        }

        ClaimResult claim = claimService.claim(event);
        if (claim.outcome() == ClaimResult.Outcome.NOT_FOUND) {
            log.error("JobRun queued event references missing runId={}", event.runId());
            return ProcessingDecision.ack();
        }
        if (claim.outcome() == ClaimResult.Outcome.INCONSISTENT_EVENT) {
            Long actualJobId = claim.jobRun() == null || claim.jobRun().getJob() == null ? null : claim.jobRun().getJob().getId();
            log.error(
                    "Inconsistent JobRun event ignored: eventRunId={}, eventJobId={}, actualJobId={}",
                    event.runId(),
                    event.jobId(),
                    actualJobId
            );
            return ProcessingDecision.ack();
        }
        if (claim.outcome() == ClaimResult.Outcome.DUPLICATE_OR_TERMINAL) {
            duplicateCounter.increment();
            log.info("Duplicate event ignored: runId={}, status={}", event.runId(), claim.existingStatus());
            return ProcessingDecision.ack();
        }

        JobRunEntity run = claim.jobRun();
        log.info("Execution claimed: runId={}, jobId={}, executorId={}", run.getId(), run.getJob().getId(), executorInstanceId);

        try {
            executeClaimedRun(run);
            return ProcessingDecision.ack();
        } catch (Exception ex) {
            log.error("Infrastructure failure after claim: runId={}, reason={}", run.getId(), ex.getMessage(), ex);
            return ProcessingDecision.nack();
        }
    }

    private void executeClaimedRun(JobRunEntity run) {
        if (run.getJob().getJobType() != JobType.HTTP) {
            completionService.markFailed(run.getId(), "Unsupported job type");
            failedCounter.increment();
            log.info("Execution failed: runId={}, reason={}, durationMs={}", run.getId(), "Unsupported job type", 0);
            return;
        }

        HttpJobPayload payload = toHttpPayload(run);
        HttpExecutionResult result = httpJobExecutor.execute(payload, run.getId());
        httpDurationTimer.record(result.durationMs(), TimeUnit.MILLISECONDS);

        if (result.success()) {
            completionService.markSuccess(run.getId());
            successCounter.increment();
            log.info("Execution succeeded: runId={}, statusCode={}, durationMs={}", run.getId(), result.statusCode(), result.durationMs());
        } else {
            completionService.markFailed(run.getId(), result.failureReason());
            failedCounter.increment();
            log.info("Execution failed: runId={}, reason={}, durationMs={}", run.getId(), result.failureReason(), result.durationMs());
        }
    }

    private HttpJobPayload toHttpPayload(JobRunEntity run) {
        try {
            String json = objectMapper.writeValueAsString(run.getJob().getPayload());
            return objectMapper.readValue(json, HttpJobPayload.class);
        } catch (Exception ex) {
            return new HttpJobPayload(null, null, null, null);
        }
    }

    private ProcessingDecision validateEvent(JobRunQueuedEvent event) {
        if (event == null || event.runId() == null || event.jobId() == null) {
            log.error("Invalid JobRun queued event ignored: missing runId or jobId");
            return ProcessingDecision.ack();
        }
        if (event.eventVersion() != SUPPORTED_EVENT_VERSION) {
            log.error("Unsupported JobRun event version ignored: runId={}, eventVersion={}", event.runId(), event.eventVersion());
            return ProcessingDecision.ack();
        }
        if (!SUPPORTED_EVENT_TYPE.equals(event.eventType())) {
            log.error("Unsupported JobRun event type ignored: runId={}, eventType={}", event.runId(), event.eventType());
            return ProcessingDecision.ack();
        }
        return null;
    }

    public record ProcessingDecision(boolean acknowledge) {
        public static ProcessingDecision ack() {
            return new ProcessingDecision(true);
        }

        public static ProcessingDecision nack() {
            return new ProcessingDecision(false);
        }
    }
}
