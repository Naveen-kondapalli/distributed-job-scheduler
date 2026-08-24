package com.executorservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.executorservice.cancellation.CancellationSignalKeys;
import com.executorservice.cancellation.ExecutorCancellationService;
import com.executorservice.enums.JobRunStatus;
import com.executorservice.heartbeat.ExecutorHeartbeatKeys;
import com.executorservice.repository.JobRunRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "executor.instance-id=executor-1",
        "executor.heartbeat.enabled=false"
})
class CancellationCorrectnessIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobRunRepository jobRunRepository;

    @Autowired
    private RetrySchedulingService retrySchedulingService;

    @Autowired
    private ExecutorCancellationService cancellationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void ensureSchema() {
        jdbcTemplate.execute("ALTER TABLE job_runs ADD COLUMN IF NOT EXISTS cancel_requested_at timestamp");
        jdbcTemplate.execute("ALTER TABLE job_runs DROP CONSTRAINT IF EXISTS job_runs_status_check");
        jdbcTemplate.execute("""
                ALTER TABLE job_runs
                ADD CONSTRAINT job_runs_status_check
                CHECK ((status)::text = ANY ((ARRAY[
                    'QUEUED'::character varying,
                    'RUNNING'::character varying,
                    'RETRY_SCHEDULED'::character varying,
                    'CANCEL_REQUESTED'::character varying,
                    'SUCCESS'::character varying,
                    'FAILED'::character varying,
                    'CANCELLED'::character varying
                ])::text[]))
                """);
        redisTemplate.delete(new CancellationSignalKeys().key(10L));
        redisTemplate.delete(new ExecutorHeartbeatKeys().heartbeatKey("executor-1"));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("""
                DELETE FROM outbox_events
                WHERE aggregate_id IN (
                    SELECT jr.id
                    FROM job_runs jr
                    JOIN jobs j ON j.id = jr.job_id
                    JOIN users u ON u.id = j.user_id
                    WHERE u.username LIKE 'cancel_correct_%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM job_runs jr
                USING jobs j, users u
                WHERE jr.job_id = j.id
                  AND j.user_id = u.id
                  AND u.username LIKE 'cancel_correct_%'
                """);
        jdbcTemplate.update("""
                DELETE FROM job_runs
                WHERE job_id IN (
                    SELECT id
                    FROM jobs
                    WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'cancel_correct_%')
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM jobs
                WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'cancel_correct_%')
                """);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'cancel_correct_%'");
        redisTemplate.delete(new ExecutorHeartbeatKeys().heartbeatKey("executor-1"));
    }

    @Test
    void cancelVsSuccessAllowsOnlyOneFinalState() throws Exception {
        for (int i = 0; i < 8; i++) {
            Long runId = insertRun("success_" + i, "RUNNING", 0, null, NOW.minusMinutes(10), null);
            RaceResult result = race(
                    () -> requestCancellation(runId),
                    () -> jobRunRepository.markSuccess(runId, "executor-1", NOW, JobRunStatus.RUNNING, JobRunStatus.SUCCESS)
            );

            String status = status(runId);
            assertThat(result.left() + result.right()).isEqualTo(1);
            assertThat(status).isIn("CANCEL_REQUESTED", "SUCCESS");
            if ("CANCEL_REQUESTED".equals(status)) {
                assertThat(jobRunRepository.markSuccess(runId, "executor-1", NOW, JobRunStatus.RUNNING, JobRunStatus.SUCCESS)).isZero();
                assertThat(cancellationService.completeIfCancellationRequested(runId)).isTrue();
                assertThat(status(runId)).isEqualTo("CANCELLED");
            } else {
                assertThat(requestCancellation(runId)).isZero();
                assertThat(status(runId)).isEqualTo("SUCCESS");
            }
        }
    }

    @Test
    void cancelVsRetryAllowsOnlyOneTransitionAndNoRetryAfterCancellation() throws Exception {
        Long runId = insertRun("retry", "RUNNING", 0, null, NOW.minusMinutes(10), null);
        RaceResult result = race(
                () -> requestCancellation(runId),
                () -> jobRunRepository.scheduleRetryAfterFailure(runId, "executor-1", 0, 1, NOW.plusSeconds(1), "retryable", JobRunStatus.RUNNING, JobRunStatus.RETRY_SCHEDULED)
        );

        assertThat(result.left() + result.right()).isEqualTo(1);
        if ("CANCEL_REQUESTED".equals(status(runId))) {
            assertThat(retryEvents(runId)).isZero();
            assertThat(jobRunRepository.scheduleRetryAfterFailure(runId, "executor-1", 0, 1, NOW.plusSeconds(1), "retryable", JobRunStatus.RUNNING, JobRunStatus.RETRY_SCHEDULED)).isZero();
            assertThat(cancellationService.completeIfCancellationRequested(runId)).isTrue();
            assertThat(status(runId)).isEqualTo("CANCELLED");
        } else {
            assertThat(status(runId)).isEqualTo("RETRY_SCHEDULED");
            jdbcTemplate.update("UPDATE job_runs SET status='CANCELLED', completed_at=?, next_retry_at=NULL WHERE id=? AND status='RETRY_SCHEDULED'", ts(NOW), runId);
            assertThat(retrySchedulingService.publishDueRetries(10)).isZero();
            assertThat(retryEvents(runId)).isZero();
            assertThat(status(runId)).isEqualTo("CANCELLED");
        }
    }

    @Test
    void cancelVsTerminalFailureAllowsOnlyOneFinalState() throws Exception {
        Long runId = insertRun("failed", "RUNNING", 0, null, NOW.minusMinutes(10), null);
        RaceResult result = race(
                () -> requestCancellation(runId),
                () -> jobRunRepository.markFailed(runId, "executor-1", NOW, "terminal", JobRunStatus.RUNNING, JobRunStatus.FAILED)
        );

        assertThat(result.left() + result.right()).isEqualTo(1);
        assertThat(status(runId)).isIn("CANCEL_REQUESTED", "FAILED");
        if ("CANCEL_REQUESTED".equals(status(runId))) {
            assertThat(jobRunRepository.markFailed(runId, "executor-1", NOW, "terminal", JobRunStatus.RUNNING, JobRunStatus.FAILED)).isZero();
            assertThat(cancellationService.completeIfCancellationRequested(runId)).isTrue();
            assertThat(status(runId)).isEqualTo("CANCELLED");
        } else {
            assertThat(requestCancellation(runId)).isZero();
            assertThat(status(runId)).isEqualTo("FAILED");
        }
    }

    @Test
    void staleCancellationRecoveryUsesCancelRequestedAtAndHeartbeat() {
        Long freshCancel = insertRun("fresh", "CANCEL_REQUESTED", 0, null, NOW.minusHours(2), NOW.minusSeconds(1));
        Long oldActiveOwner = insertRun("active_owner", "CANCEL_REQUESTED", 0, null, NOW.minusHours(2), NOW.minusMinutes(2));
        Long oldDeadOwner = insertRun("dead_owner", "CANCEL_REQUESTED", 0, null, NOW.minusHours(2), NOW.minusMinutes(2));
        jdbcTemplate.update("UPDATE job_runs SET executor_id='executor-dead' WHERE id=?", oldDeadOwner);
        redisTemplate.opsForValue().set(new ExecutorHeartbeatKeys().heartbeatKey("executor-1"), "{}");

        int recovered = cancellationService.cancelStaleRequestedRuns(60000);

        assertThat(recovered).isEqualTo(1);
        assertThat(status(freshCancel)).isEqualTo("CANCEL_REQUESTED");
        assertThat(status(oldActiveOwner)).isEqualTo("CANCEL_REQUESTED");
        assertThat(status(oldDeadOwner)).isEqualTo("CANCELLED");
        assertThat(completedAt(oldDeadOwner)).isNotNull();
        assertThat(retryEvents(oldDeadOwner)).isZero();
        assertThat(deadEvents(oldDeadOwner)).isZero();
    }

    @Test
    void cancelledRetryScheduledRunIsNotRediscoveredByRetryScheduler() {
        Long runId = insertRun("cancelled_retry", "CANCELLED", 1, NOW.minusSeconds(1), NOW.minusMinutes(5), null);

        assertThat(retrySchedulingService.publishDueRetries(10)).isZero();

        assertThat(status(runId)).isEqualTo("CANCELLED");
        assertThat(retryEvents(runId)).isZero();
        assertThat(retryCount(runId)).isEqualTo(1);
    }

    @Test
    void executorCancellationCompletionDeletesRealRedisSignal() {
        Long runId = insertRun("redis_cleanup", "CANCEL_REQUESTED", 0, null, NOW.minusMinutes(5), NOW.minusMinutes(2));
        String key = new CancellationSignalKeys().key(runId);
        redisTemplate.opsForValue().set(key, "{\"runId\":" + runId + "}", Duration.ofSeconds(30));

        assertThat(redisTemplate.getExpire(key)).isPositive();

        assertThat(cancellationService.completeIfCancellationRequested(runId)).isTrue();

        assertThat(redisTemplate.hasKey(key)).isFalse();
        assertThat(status(runId)).isEqualTo("CANCELLED");
    }

    private RaceResult race(Callable<Integer> left, Callable<Integer> right) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> {
                ready.countDown();
                start.await();
                return left.call();
            });
            var b = executor.submit(() -> {
                ready.countDown();
                start.await();
                return right.call();
            });
            ready.await();
            start.countDown();
            return new RaceResult(a.get(), b.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private int requestCancellation(Long runId) {
        return jdbcTemplate.update("""
                UPDATE job_runs
                SET status='CANCEL_REQUESTED', cancel_requested_at=?, error_message=NULL
                WHERE id=? AND status='RUNNING'
                """, ts(NOW), runId);
    }

    private Long insertRun(String suffix, String status, int retryCount, LocalDateTime nextRetryAt, LocalDateTime startedAt, LocalDateTime cancelRequestedAt) {
        String username = "cancel_correct_" + suffix + "_" + UUID.randomUUID();
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password, created_at, updated_at)
                VALUES (?, ?, 'password', ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.test", ts(NOW), ts(NOW));
        Long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO jobs (
                    user_id, name, job_type, schedule_type, scheduled_time, next_run_at,
                    payload, status, max_retries, created_at, updated_at
                )
                VALUES (?, 'job', 'HTTP', 'FUTURE', ?, ?, ?::jsonb, 'ACTIVE', 3, ?, ?)
                RETURNING id
                """, Long.class, userId, ts(NOW), ts(NOW), "{\"method\":\"GET\",\"url\":\"https://example.test\"}", ts(NOW), ts(NOW));
        return jdbcTemplate.queryForObject("""
                INSERT INTO job_runs (
                    job_id, executor_id, status, scheduled_at, retry_count, started_at,
                    cancel_requested_at, completed_at, next_retry_at, error_message, created_at, updated_at
                )
                VALUES (?, 'executor-1', ?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?)
                RETURNING id
                """, Long.class, jobId, status, ts(NOW), retryCount, ts(startedAt), ts(cancelRequestedAt), ts(nextRetryAt), ts(NOW), ts(NOW));
    }

    private String status(Long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM job_runs WHERE id=?", String.class, runId);
    }

    private LocalDateTime completedAt(Long runId) {
        return jdbcTemplate.queryForObject("SELECT completed_at FROM job_runs WHERE id=?", LocalDateTime.class, runId);
    }

    private int retryEvents(Long runId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='JOB_RUN_RETRY_SCHEDULED'", Integer.class, runId);
    }

    private int deadEvents(Long runId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='JOB_RUN_DEAD'", Integer.class, runId);
    }

    private int retryCount(Long runId) {
        return jdbcTemplate.queryForObject("SELECT retry_count FROM job_runs WHERE id=?", Integer.class, runId);
    }

    private Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private record RaceResult(int left, int right) {
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }

    }
}
