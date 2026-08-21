package com.executorservice.service;

import com.executorservice.dto.HttpJobPayload;
import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.dto.JobRunRetryEvent;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.cancellation.ExecutorCancellationService;
import com.executorservice.enums.FailureCategory;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.enums.JobType;
import com.executorservice.http.HttpExecutionResult;
import com.executorservice.http.HttpJobExecutor;
import com.executorservice.observability.ExecutorMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class JobExecutionService {

    private static final int SUPPORTED_EVENT_VERSION = 1;
    private static final String QUEUED_EVENT_TYPE = "JOB_RUN_QUEUED";
    private static final String RETRY_EVENT_TYPE = "JOB_RUN_RETRY_SCHEDULED";

    private final JobRunClaimService claimService;
    private final JobRunCompletionService completionService;
    private final ExecutionFailureHandler failureHandler;
    private final HttpJobExecutor httpJobExecutor;
    private final ExecutorCancellationService cancellationService;
    private final ObjectMapper objectMapper;
    private final String executorInstanceId;
    private final ExecutorMetrics metrics;

    public JobExecutionService(
            JobRunClaimService claimService,
            JobRunCompletionService completionService,
            ExecutionFailureHandler failureHandler,
            HttpJobExecutor httpJobExecutor,
            ExecutorCancellationService cancellationService,
            ObjectMapper objectMapper,
            String executorInstanceId,
            ExecutorMetrics metrics
    ) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.failureHandler = failureHandler;
        this.httpJobExecutor = httpJobExecutor;
        this.cancellationService = cancellationService;
        this.objectMapper = objectMapper;
        this.executorInstanceId = executorInstanceId;
        this.metrics = metrics;
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
        if (claim.outcome() == ClaimResult.Outcome.FRESH_RUNNING) {
            metrics.duplicateKafkaEvent("run");
            log.info("Fresh RUNNING event left unacknowledged for stale-running recovery: runId={}, status={}", event.runId(), claim.existingStatus());
            return ProcessingDecision.nack();
        }
        if (claim.outcome() == ClaimResult.Outcome.DUPLICATE_OR_TERMINAL) {
            metrics.duplicateKafkaEvent("run");
            log.info("Duplicate event ignored: runId={}, status={}", event.runId(), claim.existingStatus());
            return ProcessingDecision.ack();
        }

        JobRunEntity run = claim.jobRun();
        log.info("Execution claimed: runId={}, jobId={}, executorId={}", run.getId(), run.getJob().getId(), executorInstanceId);

        try {
            executeClaimedRun(run, false);
            return ProcessingDecision.ack();
        } catch (Exception ex) {
            log.error("Infrastructure failure after claim: runId={}, reason={}", run.getId(), ex.getMessage(), ex);
            return ProcessingDecision.nack();
        }
    }

    public ProcessingDecision processRetry(JobRunRetryEvent event) {
        ProcessingDecision invalidEvent = validateRetryEvent(event);
        if (invalidEvent != null) {
            return invalidEvent;
        }

        ClaimResult claim = claimService.claimRetry(event);
        if (claim.outcome() == ClaimResult.Outcome.NOT_FOUND) {
            log.error("JobRun retry event references missing runId={}", event.runId());
            return ProcessingDecision.ack();
        }
        if (claim.outcome() == ClaimResult.Outcome.INCONSISTENT_EVENT) {
            Long actualJobId = claim.jobRun() == null || claim.jobRun().getJob() == null ? null : claim.jobRun().getJob().getId();
            log.error("Inconsistent retry event ignored: eventRunId={}, eventJobId={}, actualJobId={}", event.runId(), event.jobId(), actualJobId);
            return ProcessingDecision.ack();
        }
        if (claim.outcome() == ClaimResult.Outcome.FRESH_RUNNING) {
            metrics.duplicateKafkaEvent("retry");
            log.info(
                    "Fresh RUNNING retry event left unacknowledged for stale-running recovery: runId={}, retryCount={}, status={}",
                    event.runId(),
                    event.retryCount(),
                    claim.existingStatus()
            );
            return ProcessingDecision.nack();
        }
        if (claim.outcome() == ClaimResult.Outcome.DUPLICATE_OR_TERMINAL) {
            metrics.duplicateKafkaEvent("retry");
            log.info("Stale/duplicate retry event ignored: runId={}, retryCount={}, status={}", event.runId(), event.retryCount(), claim.existingStatus());
            return ProcessingDecision.ack();
        }

        JobRunEntity run = claim.jobRun();
        log.info("Retry claimed: runId={}, retryCount={}, executorId={}", run.getId(), run.getRetryCount(), executorInstanceId);
        try {
            executeClaimedRun(run, true);
            return ProcessingDecision.ack();
        } catch (Exception ex) {
            log.error("Infrastructure failure after retry claim: runId={}, retryCount={}, reason={}", run.getId(), run.getRetryCount(), ex.getMessage(), ex);
            return ProcessingDecision.nack();
        }
    }

    private void executeClaimedRun(JobRunEntity run, boolean retryAttempt) {
        if (run.getJob().getJobType() != JobType.HTTP) {
            if (cancellationService.completeIfCancellationRequested(run.getId())) {
                metrics.execution(run.getJob().getJobType(), "cancelled");
                metrics.executionDuration(run.getJob().getJobType(), "cancelled", 0);
                log.info("Queued execution cancelled before unsupported job handling: runId={}", run.getId());
                return;
            }
            failureHandler.handleFailure(run, new HttpExecutionResult(false, 0, FailureCategory.NON_RETRYABLE, "Unsupported job type", null, 0));
            metrics.execution(run.getJob().getJobType(), "failed");
            metrics.executionDuration(run.getJob().getJobType(), "failed", 0);
            log.info("Execution failed: runId={}, reason={}, durationMs={}", run.getId(), "Unsupported job type", 0);
            return;
        }

        HttpJobPayload payload = toHttpPayload(run);
        if (cancellationService.signalExists(run.getId())) {
            log.debug("Cancellation signal observed before HTTP request: runId={}, executorId={}", run.getId(), executorInstanceId);
        }
        if (cancellationService.completeIfCancellationRequested(run.getId())) {
            metrics.execution(run.getJob().getJobType(), "cancelled");
            metrics.executionDuration(run.getJob().getJobType(), "cancelled", 0);
            log.info("Execution cancelled before HTTP request: runId={}, executorId={}", run.getId(), executorInstanceId);
            return;
        }
        HttpExecutionResult result = httpJobExecutor.execute(payload, run.getId());
        metrics.httpRequest(httpOutcome(result));

        if (cancellationService.completeIfCancellationRequested(run.getId())) {
            metrics.execution(run.getJob().getJobType(), "cancelled");
            metrics.executionDuration(run.getJob().getJobType(), "cancelled", result.durationMs());
            log.info("Execution cancellation won before completion write: runId={}, executorId={}", run.getId(), executorInstanceId);
            return;
        }

        if (result.success()) {
            completionService.markSuccess(run.getId());
            metrics.execution(run.getJob().getJobType(), "success");
            metrics.executionDuration(run.getJob().getJobType(), "success", result.durationMs());
            if (retryAttempt) {
                metrics.retrySuccess();
            }
            log.info("Execution succeeded: runId={}, statusCode={}, durationMs={}", run.getId(), result.statusCode(), result.durationMs());
        } else {
            ExecutionFailureHandler.FailureDecision decision = failureHandler.handleFailure(run, result);
            metrics.execution(run.getJob().getJobType(), "failed");
            metrics.executionDuration(run.getJob().getJobType(), "failed", result.durationMs());
            log.info("Execution failed: runId={}, reason={}, durationMs={}", run.getId(), result.failureReason(), result.durationMs());
        }
    }

    private String httpOutcome(HttpExecutionResult result) {
        if (result.statusCode() >= 200 && result.statusCode() < 300) {
            return "2xx";
        }
        if (result.statusCode() >= 300 && result.statusCode() < 400) {
            return "3xx";
        }
        if (result.statusCode() >= 400 && result.statusCode() < 500) {
            return "4xx";
        }
        if (result.statusCode() >= 500 && result.statusCode() < 600) {
            return "5xx";
        }
        if (result.failureCategory() == FailureCategory.RETRYABLE) {
            return "network_error";
        }
        return "blocked";
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
        if (!QUEUED_EVENT_TYPE.equals(event.eventType())) {
            log.error("Unsupported JobRun event type ignored: runId={}, eventType={}", event.runId(), event.eventType());
            return ProcessingDecision.ack();
        }
        return null;
    }

    private ProcessingDecision validateRetryEvent(JobRunRetryEvent event) {
        if (event == null || event.runId() == null || event.jobId() == null || event.retryCount() < 1) {
            log.error("Invalid JobRun retry event ignored: missing runId/jobId or invalid retryCount");
            return ProcessingDecision.ack();
        }
        if (event.eventVersion() != SUPPORTED_EVENT_VERSION) {
            log.error("Unsupported retry event version ignored: runId={}, eventVersion={}", event.runId(), event.eventVersion());
            return ProcessingDecision.ack();
        }
        if (!RETRY_EVENT_TYPE.equals(event.eventType())) {
            log.error("Unsupported retry event type ignored: runId={}, eventType={}", event.runId(), event.eventType());
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
