package com.executorservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "executor.retry.scheduler-interval-ms=3600000",
        "executor.instance-id=executor-1",
        "executor.heartbeat.enabled=false"
})
class RetryDlqDurabilityIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetrySchedulingService retrySchedulingService;

    @BeforeEach
    void ensureRetryScheduledStatusIsAllowedInLocalSchema() {
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
                    WHERE u.username LIKE 'executor_durable_%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM job_runs
                WHERE job_id IN (
                    SELECT j.id
                    FROM jobs j
                    JOIN users u ON u.id = j.user_id
                    WHERE u.username LIKE 'executor_durable_%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM jobs
                WHERE user_id IN (
                    SELECT id FROM users WHERE username LIKE 'executor_durable_%'
                )
                """);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'executor_durable_%'");
    }

    @Test
    void dueRetryIsDiscoveredFromPostgresAndNotScheduledRepeatedly() {
        Long runId = insertRetryScheduledRun("executor_durable_restart", 1, LocalDateTime.now(CLOCK).minusSeconds(1));

        int firstCount = retrySchedulingService.publishDueRetries(100);
        int secondCount = retrySchedulingService.publishDueRetries(100);

        assertThat(firstCount).isEqualTo(1);
        assertThat(secondCount).isZero();
        assertThat(countRetryEvents(runId)).isEqualTo(1);
        assertThat(nextRetryAt(runId)).isNull();
    }

    private Long insertRetryScheduledRun(String username, int retryCount, LocalDateTime nextRetryAt) {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.test", "password", ts(LocalDateTime.now(CLOCK)), ts(LocalDateTime.now(CLOCK)));

        Long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO jobs (
                    user_id, name, job_type, schedule_type, scheduled_time, next_run_at,
                    payload, status, max_retries, created_at, updated_at
                )
                VALUES (?, ?, 'HTTP', 'FUTURE', ?, ?, ?::jsonb, 'ACTIVE', 3, ?, ?)
                RETURNING id
                """, Long.class, userId, "durable retry", ts(LocalDateTime.now(CLOCK)), ts(LocalDateTime.now(CLOCK)),
                "{\"method\":\"GET\",\"url\":\"https://jsonplaceholder.typicode.com/posts/1\"}",
                ts(LocalDateTime.now(CLOCK)), ts(LocalDateTime.now(CLOCK)));

        return jdbcTemplate.queryForObject("""
                INSERT INTO job_runs (
                    job_id, executor_id, status, scheduled_at, retry_count, started_at,
                    completed_at, next_retry_at, error_message, created_at, updated_at
                )
                VALUES (?, 'executor-1', 'RETRY_SCHEDULED', ?, ?, ?, NULL, ?, 'HTTP 500 returned by target', ?, ?)
                RETURNING id
                """, Long.class, jobId, ts(LocalDateTime.now(CLOCK).minusMinutes(1)), retryCount,
                ts(LocalDateTime.now(CLOCK).minusSeconds(10)), ts(nextRetryAt), ts(LocalDateTime.now(CLOCK)), ts(LocalDateTime.now(CLOCK)));
    }

    private Integer countRetryEvents(Long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND topic = 'retry' AND event_type = 'JOB_RUN_RETRY_SCHEDULED'",
                Integer.class,
                runId
        );
    }

    private LocalDateTime nextRetryAt(Long runId) {
        return jdbcTemplate.queryForObject("SELECT next_retry_at FROM job_runs WHERE id = ?", LocalDateTime.class, runId);
    }

    private Timestamp ts(LocalDateTime value) {
        return Timestamp.valueOf(value);
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
