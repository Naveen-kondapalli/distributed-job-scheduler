package com.watcherservice.repository;

import com.watcherservice.entity.JobRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRunRepository extends JpaRepository<JobRunEntity, Long> {
}
