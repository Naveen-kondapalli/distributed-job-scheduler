package com.jobservice.service.interfaces;

import com.jobservice.dto.request.CreateJobRequest;
import com.jobservice.dto.request.UpdateJobRequest;
import com.jobservice.dto.response.JobResponse;
import com.jobservice.dto.response.JobRunStatusResponse;
import com.jobservice.dto.response.JobStatusResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface JobServiceInterface {

    JobResponse createJob(CreateJobRequest request, Long userId);

    JobResponse getJob(Long jobId, Long userId);

    Page<JobResponse> getAllJobs(Long userId, Pageable pageable);

    JobResponse updateJob(Long jobId, UpdateJobRequest request, Long userId);

    void deleteJob(Long jobId, Long userId);

    JobStatusResponse cancelJob(Long jobId, Long userId);

    JobRunStatusResponse cancelJobRun(Long jobId, Long runId, Long userId);

    JobStatusResponse pauseJob(Long jobId, Long userId);

    JobStatusResponse resumeJob(Long jobId, Long userId);

    JobStatusResponse getJobStatus(Long jobId, Long userId);
}
