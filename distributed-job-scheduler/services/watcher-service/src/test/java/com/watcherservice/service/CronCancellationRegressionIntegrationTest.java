package com.watcherservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CronCancellationRegressionIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 18, 12, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobClaimService jobClaimService;

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
                    WHERE u.username LIKE 'watcher_cron_cancel_%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM job_runs
                WHERE job_id IN (
                    SELECT j.id
                    FROM jobs j
                    JOIN users u ON u.id = j.user_id
                    WHERE u.username LIKE 'watcher_cron_cancel_%'
                )
                """);
        jdbcTemplate.update("DELETE FROM jobs WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'watcher_cron_cancel_%')");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'watcher_cron_cancel_%'");
    }

    @Test
    void cancelledCronOccurrenceLeavesScheduleActiveAndNextOccurrenceIsCreated() {
        Long userId = insertUser();
        Long jobId = insertCronJob(userId, T0);

        jobClaimService.claimDueOccurrences(T0, 10);
        Long firstRunId = latestRun(jobId);
        LocalDateTime nextAfterFirstClaim = nextRunAt(jobId);

        jdbcTemplate.update("""
                UPDATE job_runs
                SET status='CANCELLED', completed_at=?, next_retry_at=NULL
                WHERE id=? AND status='QUEUED'
                """, ts(T0.plusSeconds(5)), firstRunId);

        assertThat(status(firstRunId)).isEqualTo("CANCELLED");
        assertThat(jobStatus(jobId)).isEqualTo("ACTIVE");
        assertThat(nextAfterFirstClaim).isEqualTo(T0.plusMinutes(1));

        jobClaimService.claimDueOccurrences(T0.plusMinutes(1), 10);

        assertThat(runCount(jobId)).isEqualTo(2);
        assertThat(latestRun(jobId)).isNotEqualTo(firstRunId);
        assertThat(jobStatus(jobId)).isEqualTo("ACTIVE");
        assertThat(nextRunAt(jobId)).isEqualTo(T0.plusMinutes(2));
    }

    private Long insertUser() {
        String username = "watcher_cron_cancel_" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password, created_at, updated_at)
                VALUES (?, ?, 'password', ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.test", ts(T0), ts(T0));
    }

    private Long insertCronJob(Long userId, LocalDateTime nextRunAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO jobs (
                    user_id, name, job_type, schedule_type, cron_expression, next_run_at,
                    payload, status, max_retries, created_at, updated_at
                )
                VALUES (?, 'cron', 'HTTP', 'CRON', '0 * * * * *', ?, ?::jsonb, 'ACTIVE', 3, ?, ?)
                RETURNING id
                """, Long.class, userId, ts(nextRunAt), "{\"method\":\"GET\",\"url\":\"https://example.test\"}", ts(T0), ts(T0));
    }

    private Long latestRun(Long jobId) {
        return jdbcTemplate.queryForObject("SELECT id FROM job_runs WHERE job_id=? ORDER BY id DESC LIMIT 1", Long.class, jobId);
    }

    private int runCount(Long jobId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_runs WHERE job_id=?", Integer.class, jobId);
    }

    private String status(Long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM job_runs WHERE id=?", String.class, runId);
    }

    private String jobStatus(Long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM jobs WHERE id=?", String.class, jobId);
    }

    private LocalDateTime nextRunAt(Long jobId) {
        return jdbcTemplate.queryForObject("SELECT next_run_at FROM jobs WHERE id=?", LocalDateTime.class, jobId);
    }

    private Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
