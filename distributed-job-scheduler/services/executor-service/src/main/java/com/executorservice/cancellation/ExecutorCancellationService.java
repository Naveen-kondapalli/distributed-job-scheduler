package com.executorservice.cancellation;

import com.executorservice.enums.JobRunStatus;
import com.executorservice.heartbeat.ExecutorHeartbeatKeys;
import com.executorservice.observability.ExecutorMetrics;
import com.executorservice.repository.JobRunRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutorCancellationService {

    private final JobRunRepository jobRunRepository;
    private final StringRedisTemplate redisTemplate;
    private final CancellationSignalKeys keys;
    private final ExecutorHeartbeatKeys heartbeatKeys;
    private final String executorInstanceId;
    private final Clock clock;
    private final ExecutorMetrics metrics;

    @Transactional
    public boolean completeIfCancellationRequested(Long runId) {
        JobRunStatus status = jobRunRepository.findOwnedStatus(runId, executorInstanceId).orElse(null);
        if (status != JobRunStatus.CANCEL_REQUESTED) {
            return false;
        }
        int updated = jobRunRepository.markCancelledIfOwnedAndRequested(
                runId,
                executorInstanceId,
                LocalDateTime.now(clock),
                JobRunStatus.CANCEL_REQUESTED,
                JobRunStatus.CANCELLED
        );
        if (updated == 1) {
            deleteSignal(runId);
            metrics.cancellationCompleted();
            log.info("Running execution cancelled: runId={}, executorId={}", runId, executorInstanceId);
            return true;
        }
        JobRunStatus current = jobRunRepository.findOwnedStatus(runId, executorInstanceId).orElse(null);
        log.info("Cancellation race lost: runId={}, currentStatus={}", runId, current);
        return current == JobRunStatus.CANCELLED;
    }

    public boolean signalExists(Long runId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(keys.key(runId)));
        } catch (Exception ex) {
            metrics.cancellationSignalFailure();
            log.warn("Cancellation signal lookup failed: runId={}, reason={}", runId, ex.getMessage());
            return false;
        }
    }

    public int cancelStaleRequestedRuns(long runningTimeoutMs) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime requestedBefore = now.minusNanos(runningTimeoutMs * 1_000_000);
        int updated = 0;
        for (var run : jobRunRepository.findStaleCancellationRequests(requestedBefore, JobRunStatus.CANCEL_REQUESTED)) {
            if (ownerHeartbeatActive(run.getExecutorId())) {
                log.debug("Stale cancellation left for active owner: runId={}, executorId={}", run.getId(), run.getExecutorId());
                continue;
            }
            updated += finalizeRecoveredCancellation(run.getId(), now);
        }
        if (updated > 0) {
            log.info("Stale cancellation requests finalized: count={}", updated);
        }
        return updated;
    }

    @Transactional
    protected int finalizeRecoveredCancellation(Long runId, LocalDateTime completedAt) {
        int updated = jobRunRepository.markCancelRequestedRunCancelled(
                runId,
                completedAt,
                JobRunStatus.CANCEL_REQUESTED,
                JobRunStatus.CANCELLED
        );
        if (updated == 1) {
            deleteSignal(runId);
        }
        return updated;
    }

    private boolean ownerHeartbeatActive(String ownerExecutorId) {
        if (ownerExecutorId == null || ownerExecutorId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(heartbeatKeys.heartbeatKey(ownerExecutorId)));
        } catch (DataAccessException ex) {
            log.warn(
                    "Heartbeat lookup unavailable; leaving cancellation recovery untouched: executorId={}, reason={}",
                    ownerExecutorId,
                    ex.getMessage()
            );
            return true;
        } catch (Exception ex) {
            log.warn(
                    "Heartbeat lookup failed; leaving cancellation recovery untouched: executorId={}, reason={}",
                    ownerExecutorId,
                    ex.getMessage()
            );
            return true;
        }
    }

    private void deleteSignal(Long runId) {
        try {
            redisTemplate.delete(keys.key(runId));
        } catch (Exception ex) {
            metrics.cancellationSignalFailure();
            log.warn("Cancellation signal cleanup failed: runId={}, reason={}", runId, ex.getMessage());
        }
    }
}
