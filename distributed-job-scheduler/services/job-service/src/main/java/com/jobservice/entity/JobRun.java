package com.jobservice.entity;

import com.jobservice.enums.JobRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "job_runs",
        indexes = {
                @Index(name = "idx_job_runs_job_id", columnList = "job_id"),
                @Index(name = "idx_job_runs_status", columnList = "status"),
                @Index(name = "idx_job_runs_executor_id", columnList = "executor_id"),
                @Index(name = "idx_job_runs_status_next_retry_at", columnList = "status, next_retry_at")
        }
)
public class JobRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "executor_id", length = 100)
    private String executorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobRunStatus status;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
