package com.watcherservice.repository;

import com.watcherservice.entity.JobEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    @Query(
            value = """
                    SELECT *
                    FROM jobs
                    WHERE status = :status
                      AND next_run_at IS NOT NULL
                      AND next_run_at <= :now
                    ORDER BY next_run_at ASC, id ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<JobEntity> findDueJobsForUpdateSkipLocked(
            @Param("status") String status,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );
}
