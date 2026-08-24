package com.executorservice.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.heartbeat.ExecutorHeartbeatKeys;
import com.executorservice.observability.ExecutorMetrics;
import com.executorservice.repository.JobRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;

class ExecutorCancellationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    @Mock
    private JobRunRepository jobRunRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    private ExecutorCancellationService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        service = new ExecutorCancellationService(jobRunRepository, redisTemplate, new CancellationSignalKeys(), new ExecutorHeartbeatKeys(), "executor-1", CLOCK, new ExecutorMetrics(meterRegistry));
    }

    @Test
    void ownedCancelRequestedRunBecomesCancelledAndSignalIsRemoved() {
        when(jobRunRepository.findOwnedStatus(10L, "executor-1")).thenReturn(Optional.of(JobRunStatus.CANCEL_REQUESTED));
        when(jobRunRepository.markCancelledIfOwnedAndRequested(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("executor-1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCEL_REQUESTED),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCELLED)
        )).thenReturn(1);

        assertThat(service.completeIfCancellationRequested(10L)).isTrue();

        verify(redisTemplate).delete("scheduler:execution:cancel:10");
    }

    @Test
    void executorDoesNotCancelRunItDoesNotOwn() {
        when(jobRunRepository.findOwnedStatus(11L, "executor-1")).thenReturn(Optional.empty());

        assertThat(service.completeIfCancellationRequested(11L)).isFalse();
    }

    @Test
    void staleCancelRequestedRunsAreFinalizedUsingRunningTimeout() {
        JobRunEntity stale = new JobRunEntity();
        stale.setId(12L);
        stale.setExecutorId("executor-1");
        when(jobRunRepository.findStaleCancellationRequests(
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCEL_REQUESTED)
        )).thenReturn(List.of(stale));
        when(redisTemplate.hasKey("scheduler:executor:heartbeat:executor-1")).thenReturn(false);
        when(jobRunRepository.markCancelRequestedRunCancelled(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCEL_REQUESTED),
                org.mockito.ArgumentMatchers.eq(JobRunStatus.CANCELLED)
        )).thenReturn(1);

        assertThat(service.cancelStaleRequestedRuns(60000)).isEqualTo(1);
    }
}
