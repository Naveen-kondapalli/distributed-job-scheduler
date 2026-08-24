package com.executorservice.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.dto.JobRunQueuedEvent;
import com.executorservice.cancellation.ExecutorCancellationService;
import com.executorservice.entity.JobEntity;
import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.enums.JobType;
import com.executorservice.http.HttpExecutionResult;
import com.executorservice.http.HttpJobExecutor;
import com.executorservice.observability.ExecutorMetrics;
import com.executorservice.service.ClaimResult;
import com.executorservice.service.ExecutionFailureHandler;
import com.executorservice.service.JobExecutionService;
import com.executorservice.service.JobRunClaimService;
import com.executorservice.service.JobRunCompletionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ExecutorHeartbeatServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-19T07:00:00Z"), ZoneId.of("Asia/Kolkata"));

    @Mock
    private StringRedisTemplate redisTemplate;

    private ExecutorProperties properties;
    private ExecutorHeartbeatService service;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new ExecutorProperties();
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        service = new ExecutorHeartbeatService(
                redisTemplate,
                new ExecutorHeartbeatKeys(),
                properties,
                objectMapper,
                CLOCK,
                "executor-1",
                "executor-service",
                Optional.empty(),
                new ExecutorMetrics(meterRegistry)
        );
    }

    @Test
    void initialRegistrationWritesJsonHeartbeatWithTtl() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);

        boolean registered = service.registerOrRefresh();

        assertThat(registered).isTrue();
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> ttlCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), eq(java.util.List.of("scheduler:executor:heartbeat:executor-1")),
                valueCaptor.capture(), ttlCaptor.capture(), any());

        JsonNode json = objectMapper.readTree((String) valueCaptor.getValue());
        assertThat(json.get("executorId").asText()).isEqualTo("executor-1");
        assertThat(json.get("serviceName").asText()).isEqualTo("executor-service");
        assertThat(json.get("lastHeartbeatAt").asText()).isEqualTo("2026-08-19T12:30:00");
        assertThat(json.get("instanceToken").asText()).isEqualTo(service.instanceToken());
        assertThat(ttlCaptor.getValue()).isEqualTo("30000");
    }

    @Test
    void differentExecutorsUseIndependentHeartbeatKeys() {
        ExecutorHeartbeatService executor2 = new ExecutorHeartbeatService(
                redisTemplate,
                new ExecutorHeartbeatKeys(),
                properties,
                objectMapper,
                CLOCK,
                "executor-2",
                "executor-service",
                Optional.empty(),
                new ExecutorMetrics(new SimpleMeterRegistry())
        );
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);

        service.registerOrRefresh();
        executor2.registerOrRefresh();

        verify(redisTemplate).execute(any(RedisScript.class), eq(java.util.List.of("scheduler:executor:heartbeat:executor-1")),
                any(), any(), any());
        verify(redisTemplate).execute(any(RedisScript.class), eq(java.util.List.of("scheduler:executor:heartbeat:executor-2")),
                any(), any(), any());
    }

    @Test
    void duplicateActiveExecutorIdRejectsRegistration() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(-1L);

        assertThatThrownBy(service::registerOrRefresh)
                .isInstanceOf(DuplicateExecutorInstanceException.class);
    }

    @Test
    void redisFailureIsContainedAtHeartbeatBoundary() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(service.registerOrRefresh()).isFalse();
    }

    @Test
    void gracefulShutdownRemovesOnlyOwnedHeartbeat() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        service.removeHeartbeat();

        verify(redisTemplate).execute(any(RedisScript.class), eq(java.util.List.of("scheduler:executor:heartbeat:executor-1")),
                eq(service.instanceToken()));
    }

    @Test
    void heartbeatFailureDoesNotAffectSuccessfulJobExecution() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        assertThat(service.registerOrRefresh()).isFalse();

        JobRunClaimService claimService = org.mockito.Mockito.mock(JobRunClaimService.class);
        JobRunCompletionService completionService = org.mockito.Mockito.mock(JobRunCompletionService.class);
        ExecutionFailureHandler failureHandler = org.mockito.Mockito.mock(ExecutionFailureHandler.class);
        HttpJobExecutor httpJobExecutor = org.mockito.Mockito.mock(HttpJobExecutor.class);
        ExecutorCancellationService cancellationService = org.mockito.Mockito.mock(ExecutorCancellationService.class);
        JobExecutionService executionService = new JobExecutionService(
                claimService,
                completionService,
                failureHandler,
                httpJobExecutor,
                cancellationService,
                objectMapper,
                "executor-1",
                new ExecutorMetrics(meterRegistry)
        );
        JobRunQueuedEvent event = new JobRunQueuedEvent("event-1", 1, 10L, 20L, LocalDateTime.now(CLOCK), "JOB_RUN_QUEUED", LocalDateTime.now(CLOCK));
        JobRunEntity run = run();
        when(claimService.claim(event)).thenReturn(new ClaimResult(ClaimResult.Outcome.CLAIMED, run, JobRunStatus.RUNNING));
        when(httpJobExecutor.execute(any(), eq(10L))).thenReturn(new HttpExecutionResult(true, 200, null, null, null, 25));

        JobExecutionService.ProcessingDecision decision = executionService.process(event);

        assertThat(decision.acknowledge()).isTrue();
        verify(completionService).markSuccess(10L);
    }

    private JobRunEntity run() {
        JobEntity job = new JobEntity();
        job.setId(20L);
        job.setJobType(JobType.HTTP);
        job.setPayload(Map.of("method", "GET", "url", "https://jsonplaceholder.typicode.com/posts/1"));

        JobRunEntity run = new JobRunEntity();
        run.setId(10L);
        run.setJob(job);
        run.setStatus(JobRunStatus.RUNNING);
        return run;
    }
}
