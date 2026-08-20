package com.jobservice.repository;

import com.jobservice.entity.JobRun;
import com.jobservice.enums.JobRunStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    @EntityGraph(attributePaths = "job")
    Optional<JobRun> findByIdAndJobIdAndJobUserId(Long id, Long jobId, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE JobRun run
            SET run.status = :cancelledStatus,
                run.completedAt = :completedAt,
                run.nextRetryAt = NULL,
                run.errorMessage = NULL
            WHERE run.id = :runId
              AND run.job.id = :jobId
              AND run.job.user.id = :userId
              AND run.status = :expectedStatus
            """)
    int cancelQueuedOrRetryScheduled(
            @Param("runId") Long runId,
            @Param("jobId") Long jobId,
            @Param("userId") Long userId,
            @Param("expectedStatus") JobRunStatus expectedStatus,
            @Param("cancelledStatus") JobRunStatus cancelledStatus,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE JobRun run
            SET run.status = :cancelRequestedStatus,
                run.cancelRequestedAt = :cancelRequestedAt,
                run.errorMessage = NULL
            WHERE run.id = :runId
              AND run.job.id = :jobId
              AND run.job.user.id = :userId
              AND run.status = :runningStatus
            """)
    int requestRunningCancellation(
            @Param("runId") Long runId,
            @Param("jobId") Long jobId,
            @Param("userId") Long userId,
            @Param("cancelRequestedAt") LocalDateTime cancelRequestedAt,
            @Param("runningStatus") JobRunStatus runningStatus,
            @Param("cancelRequestedStatus") JobRunStatus cancelRequestedStatus
    );
}
