package com.jobservice.controller;

import com.jobservice.dto.request.CreateJobRequest;
import com.jobservice.dto.request.UpdateJobRequest;
import com.jobservice.dto.response.JobResponse;
import com.jobservice.dto.response.JobStatusResponse;
import com.jobservice.security.UserPrincipal;
import com.jobservice.service.interfaces.JobServiceInterface;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobServiceInterface jobService;

    @PostMapping
    public ResponseEntity<JobResponse> createJob(
            @Valid @RequestBody CreateJobRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobService.createJob(request, principal.getId()));
    }

    @GetMapping
    public ResponseEntity<Page<JobResponse>> getAllJobs(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        return ResponseEntity.ok(jobService.getAllJobs(principal.getId(), pageable));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobResponse> getJob(
            @PathVariable Long jobId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(jobService.getJob(jobId, principal.getId()));
    }

    @PutMapping("/{jobId}")
    public ResponseEntity<JobResponse> updateJob(
            @PathVariable Long jobId,
            @Valid @RequestBody UpdateJobRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(jobService.updateJob(jobId, request, principal.getId()));
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> deleteJob(
            @PathVariable Long jobId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        jobService.deleteJob(jobId, principal.getId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{jobId}/cancel")
    public ResponseEntity<JobStatusResponse> cancelJob(
            @PathVariable Long jobId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(jobService.cancelJob(jobId, principal.getId()));
    }

    @PatchMapping("/{jobId}/pause")
    public ResponseEntity<JobStatusResponse> pauseJob(
            @PathVariable Long jobId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(jobService.pauseJob(jobId, principal.getId()));
    }

    @PatchMapping("/{jobId}/resume")
    public ResponseEntity<JobStatusResponse> resumeJob(
            @PathVariable Long jobId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(jobService.resumeJob(jobId, principal.getId()));
    }

    @GetMapping("/{jobId}/status")
    public ResponseEntity<JobStatusResponse> getJobStatus(
            @PathVariable Long jobId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(jobService.getJobStatus(jobId, principal.getId()));
    }
}
