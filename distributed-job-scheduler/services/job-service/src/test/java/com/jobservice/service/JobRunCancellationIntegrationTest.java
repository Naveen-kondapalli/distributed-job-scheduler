package com.jobservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import com.jobservice.cancellation.CancellationSignalException;
import com.jobservice.cancellation.CancellationSignalService;
import com.jobservice.enums.JobRunStatus;
import com.jobservice.exception.ResourceNotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class JobRunCancellationIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobService jobService;

    @MockitoBean
    private CancellationSignalService cancellationSignalService;

    @BeforeEach
    void ensureSchema() {
        jdbcTemplate.execute("ALTER TABLE job_runs ADD COLUMN IF NOT EXISTS cancel_requested_at timestamp");
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
                    WHERE u.username LIKE 'job_cancel_int_%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM job_runs
                WHERE job_id IN (
                    SELECT j.id
                    FROM jobs j
                    JOIN users u ON u.id = j.user_id
                    WHERE u.username LIKE 'job_cancel_int_%'
                )
                """);
        jdbcTemplate.update("DELETE FROM jobs WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'job_cancel_int_%')");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'job_cancel_int_%'");
    }

    @Test
    void concurrentRunningCancellationCreatesOneDurableIntent() throws Exception {
        Long userId = insertUser("concurrent");
        Long jobId = insertJob(userId, "ACTIVE", "FUTURE", NOW.plusMinutes(1), NOW.plusMinutes(1), null);
        Long runId = insertRun(jobId, "RUNNING", "executor-1", 0, null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> cancelAfterLatch(jobId, runId, userId, ready, start));
            var b = executor.submit(() -> cancelAfterLatch(jobId, runId, userId, ready, start));
            ready.await();
            start.countDown();

            assertThat(a.get().status()).isEqualTo(JobRunStatus.CANCEL_REQUESTED);
            assertThat(b.get().status()).isEqualTo(JobRunStatus.CANCEL_REQUESTED);
        } finally {
            executor.shutdownNow();
        }

        assertThat(status(runId)).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelRequestedAt(runId)).isNotNull();
    }

    @Test
    void redisSignalFailureDoesNotRollbackRunningCancellationIntent() {
        Long userId = insertUser("redis_outage");
        Long jobId = insertJob(userId, "ACTIVE", "FUTURE", NOW.plusMinutes(1), NOW.plusMinutes(1), null);
        Long runId = insertRun(jobId, "RUNNING", "executor-1", 0, null);
        doThrow(new CancellationSignalException("signal failed", new RuntimeException("redis down")))
                .when(cancellationSignalService).createSignal(runId, userId);

        assertThatThrownBy(() -> jobService.cancelJobRun(jobId, runId, userId))
                .isInstanceOf(CancellationSignalException.class);

        assertThat(status(runId)).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelRequestedAt(runId)).isNotNull();
    }

    @Test
    void wrongJobRunRelationshipIsSafeNotFoundAndRunUnchanged() {
        Long userId = insertUser("wrong_relation");
        Long jobA = insertJob(userId, "ACTIVE", "FUTURE", NOW.plusMinutes(1), NOW.plusMinutes(1), null);
        Long jobB = insertJob(userId, "ACTIVE", "FUTURE", NOW.plusMinutes(2), NOW.plusMinutes(2), null);
        Long runB = insertRun(jobB, "QUEUED", null, 0, null);

        assertThatThrownBy(() -> jobService.cancelJobRun(jobA, runB, userId))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(status(runB)).isEqualTo("QUEUED");
    }

    private com.jobservice.dto.response.JobRunStatusResponse cancelAfterLatch(Long jobId, Long runId, Long userId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return jobService.cancelJobRun(jobId, runId, userId);
    }

    private Long insertUser(String suffix) {
        String username = "job_cancel_int_" + suffix + "_" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password, created_at, updated_at)
                VALUES (?, ?, 'password', ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.test", ts(NOW), ts(NOW));
    }

    private Long insertJob(Long userId, String status, String scheduleType, LocalDateTime scheduledTime, LocalDateTime nextRunAt, String cronExpression) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO jobs (
                    user_id, name, job_type, schedule_type, scheduled_time, cron_expression, next_run_at,
                    payload, status, max_retries, created_at, updated_at
                )
                VALUES (?, 'job', 'HTTP', ?, ?, ?, ?, ?::jsonb, ?, 3, ?, ?)
                RETURNING id
                """, Long.class, userId, scheduleType, ts(scheduledTime), cronExpression, ts(nextRunAt), "{\"method\":\"GET\",\"url\":\"https://example.test\"}", status, ts(NOW), ts(NOW));
    }

    private Long insertRun(Long jobId, String status, String executorId, int retryCount, LocalDateTime nextRetryAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO job_runs (
                    job_id, executor_id, status, scheduled_at, retry_count, started_at,
                    cancel_requested_at, completed_at, next_retry_at, error_message, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL, ?, ?)
                RETURNING id
                """, Long.class, jobId, executorId, status, ts(NOW), retryCount, ts(NOW.minusMinutes(1)), ts(nextRetryAt), ts(NOW), ts(NOW));
    }

    private String status(Long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM job_runs WHERE id=?", String.class, runId);
    }

    private LocalDateTime cancelRequestedAt(Long runId) {
        return jdbcTemplate.queryForObject("SELECT cancel_requested_at FROM job_runs WHERE id=?", LocalDateTime.class, runId);
    }

    private Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
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
