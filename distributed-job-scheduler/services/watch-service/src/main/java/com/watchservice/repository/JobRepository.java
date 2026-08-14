package com.watchservice.repository;

import com.watcherservice.entity.JobEntity;
import com.watcherservice.enums.JobStatus;
import com.watcherservice.enums.ScheduleType;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    Page<JobEntity> findByStatusAndScheduleTypeAndScheduledTimeIsNotNullAndScheduledTimeLessThanEqual(
            JobStatus status,
            ScheduleType scheduleType,
            LocalDateTime scheduledTime,
            Pageable pageable
    );
}
