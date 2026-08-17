package com.executorservice.repository;

import com.executorservice.entity.JobRunEntity;
import com.executorservice.enums.JobRunStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRunRepository extends JpaRepository<JobRunEntity, Long> {

    @EntityGraph(attributePaths = "job")
    Optional<JobRunEntity> findWithJobById(Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE JobRunEntity run
            SET run.status = :runningStatus,
                run.executorId = :executorId,
                run.startedAt = :startedAt,
                run.errorMessage = NULL
            WHERE run.id = :runId
              AND run.status = :queuedStatus
            """)
    int claimQueuedRun(
            @Param("runId") Long runId,
            @Param("executorId") String executorId,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("queuedStatus") JobRunStatus queuedStatus,
            @Param("runningStatus") JobRunStatus runningStatus
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE JobRunEntity run
            SET run.status = :successStatus,
                run.completedAt = :completedAt,
                run.errorMessage = NULL
            WHERE run.id = :runId
              AND run.status = :runningStatus
              AND run.executorId = :executorId
            """)
    int markSuccess(
            @Param("runId") Long runId,
            @Param("executorId") String executorId,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("runningStatus") JobRunStatus runningStatus,
            @Param("successStatus") JobRunStatus successStatus
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE JobRunEntity run
            SET run.status = :failedStatus,
                run.completedAt = :completedAt,
                run.errorMessage = :errorMessage
            WHERE run.id = :runId
              AND run.status = :runningStatus
              AND run.executorId = :executorId
            """)
    int markFailed(
            @Param("runId") Long runId,
            @Param("executorId") String executorId,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("errorMessage") String errorMessage,
            @Param("runningStatus") JobRunStatus runningStatus,
            @Param("failedStatus") JobRunStatus failedStatus
    );
}
