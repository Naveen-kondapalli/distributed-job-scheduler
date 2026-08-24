package com.executorservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.FailureCategory;
import com.executorservice.http.HttpExecutionResult;
import com.executorservice.repository.JobRunRepository;
import com.executorservice.repository.OutboxEventRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "executor.retry.scheduler-interval-ms=3600000",
        "executor.instance-id=executor-1",
        "executor.heartbeat.enabled=false"
})
class OutboxTransactionRollbackIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobRunRepository jobRunRepository;

    @Autowired
    private RetrySchedulingService retrySchedulingService;

    @Autowired
    private ExecutionFailureHandler failureHandler;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUpFailingOutbox() {
        ensureRetryScheduledStatusIsAllowedInLocalSchema();
        reset(outboxEventRepository);
        when(outboxEventRepository.findByEventId(anyString())).thenReturn(Optional.empty());
        when(outboxEventRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("simulated outbox failure"));
    }

    private void ensureRetryScheduledStatusIsAllowedInLocalSchema() {
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
                DELETE FROM job_runs jr
                USING jobs j, users u
                WHERE jr.job_id = j.id
                  AND j.user_id = u.id
                  AND u.username LIKE 'executor_rollback_%'
                """);
        jdbcTemplate.update("""
                DELETE FROM job_runs
                WHERE job_id IN (
                    SELECT id
                    FROM jobs
                    WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'executor_rollback_%')
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM jobs
                WHERE user_id IN (
                    SELECT id FROM users WHERE username LIKE 'executor_rollback_%'
                )
                """);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'executor_rollback_%'");
    }

    @Test
    void retryOutboxPersistenceFailureRollsBackRetrySchedulingTransaction() {
        Long runId = insertRun("executor_rollback_retry", "RETRY_SCHEDULED", 1, LocalDateTime.now(CLOCK).minusSeconds(1));

        assertThatThrownBy(() -> retrySchedulingService.publishDueRetries(100))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(status(runId)).isEqualTo("RETRY_SCHEDULED");
        assertThat(nextRetryAt(runId)).isNotNull();
    }

    @Test
    void deadOutboxPersistenceFailureRollsBackTerminalFailedTransaction() {
        Long runId = insertRun("executor_rollback_dead", "RUNNING", 0, null);
        JobRunEntity run = jobRunRepository.findWithJobById(runId).orElseThrow();

        assertThatThrownBy(() -> failureHandler.handleFailure(
                run,
                new HttpExecutionResult(false, 404, FailureCategory.NON_RETRYABLE, "HTTP 404 returned by target", null, 10)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(status(runId)).isEqualTo("RUNNING");
        assertThat(completedAt(runId)).isNull();
        assertThat(nextRetryAt(runId)).isNull();
    }

    private Long insertRun(String username, String status, int retryCount, LocalDateTime nextRetryAt) {
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
                """, Long.class, userId, "rollback test", ts(LocalDateTime.now(CLOCK)), ts(LocalDateTime.now(CLOCK)),
                "{\"method\":\"GET\",\"url\":\"https://jsonplaceholder.typicode.com/posts/1\"}",
                ts(LocalDateTime.now(CLOCK)), ts(LocalDateTime.now(CLOCK)));

        return jdbcTemplate.queryForObject("""
                INSERT INTO job_runs (
                    job_id, executor_id, status, scheduled_at, retry_count, started_at,
                    completed_at, next_retry_at, error_message, created_at, updated_at
                )
                VALUES (?, 'executor-1', ?, ?, ?, ?, NULL, ?, 'last failure', ?, ?)
                RETURNING id
                """, Long.class, jobId, status, ts(LocalDateTime.now(CLOCK).minusMinutes(1)), retryCount,
                ts(LocalDateTime.now(CLOCK).minusSeconds(10)), nextRetryAt == null ? null : ts(nextRetryAt),
                ts(LocalDateTime.now(CLOCK)), ts(LocalDateTime.now(CLOCK)));
    }

    private String status(Long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM job_runs WHERE id = ?", String.class, runId);
    }

    private LocalDateTime completedAt(Long runId) {
        return jdbcTemplate.queryForObject("SELECT completed_at FROM job_runs WHERE id = ?", LocalDateTime.class, runId);
    }

    private LocalDateTime nextRetryAt(Long runId) {
        return jdbcTemplate.queryForObject("SELECT next_retry_at FROM job_runs WHERE id = ?", LocalDateTime.class, runId);
    }

    private Timestamp ts(LocalDateTime value) {
        return Timestamp.valueOf(value);
    }

    @TestConfiguration
    static class FailingOutboxConfig {
        @Bean
        @Primary
        OutboxEventRepository failingOutboxEventRepository() {
            return Mockito.mock(OutboxEventRepository.class);
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }
}
