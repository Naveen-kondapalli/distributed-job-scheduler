package com.jobservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobservice.dto.response.JobResponse;
import com.jobservice.dto.response.JobRunStatusResponse;
import com.jobservice.dto.response.JobStatusResponse;
import com.jobservice.enums.JobRunStatus;
import com.jobservice.enums.JobStatus;
import com.jobservice.enums.JobType;
import com.jobservice.enums.ScheduleType;
import com.jobservice.exception.GlobalExceptionHandler;
import com.jobservice.security.CustomUserDetailsService;
import com.jobservice.security.JwtAuthenticationFilter;
import com.jobservice.security.JwtService;
import com.jobservice.security.RestAccessDeniedHandler;
import com.jobservice.security.RestAuthenticationEntryPoint;
import com.jobservice.security.SecurityConfig;
import com.jobservice.security.SecurityErrorResponseWriter;
import com.jobservice.security.UserPrincipal;
import com.jobservice.service.interfaces.JobServiceInterface;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JobController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        GlobalExceptionHandler.class
})
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobServiceInterface jobService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @Test
    void createJobReturnsCreatedForAuthenticatedUser() throws Exception {
        when(jobService.createJob(any(), eq(1L))).thenReturn(jobResponse());

        mockMvc.perform(post("/api/v1/jobs")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validImmediateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getAllJobsReturnsPageForAuthenticatedUser() throws Exception {
        when(jobService.getAllJobs(eq(1L), any())).thenReturn(new PageImpl<>(java.util.List.of(jobResponse())));

        mockMvc.perform(get("/api/v1/jobs?page=0&size=1")
                        .with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(10));
    }

    @Test
    void getJobReturnsOk() throws Exception {
        when(jobService.getJob(10L, 1L)).thenReturn(jobResponse());

        mockMvc.perform(get("/api/v1/jobs/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void updateJobReturnsOk() throws Exception {
        when(jobService.updateJob(eq(10L), any(), eq(1L))).thenReturn(jobResponse());

        mockMvc.perform(put("/api/v1/jobs/10")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validImmediateJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void pauseJobReturnsStatus() throws Exception {
        when(jobService.pauseJob(10L, 1L)).thenReturn(new JobStatusResponse(10L, JobStatus.PAUSED));

        mockMvc.perform(patch("/api/v1/jobs/10/pause").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobStatus").value("PAUSED"));
    }

    @Test
    void resumeJobReturnsStatus() throws Exception {
        when(jobService.resumeJob(10L, 1L)).thenReturn(new JobStatusResponse(10L, JobStatus.ACTIVE));

        mockMvc.perform(patch("/api/v1/jobs/10/resume").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobStatus").value("ACTIVE"));
    }

    @Test
    void cancelJobReturnsStatus() throws Exception {
        when(jobService.cancelJob(10L, 1L)).thenReturn(new JobStatusResponse(10L, JobStatus.CANCELLED));

        mockMvc.perform(patch("/api/v1/jobs/10/cancel").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(10))
                .andExpect(jsonPath("$.jobStatus").value("CANCELLED"));
    }

    @Test
    void cancelJobRunReturnsStatus() throws Exception {
        when(jobService.cancelJobRun(10L, 100L, 1L)).thenReturn(new JobRunStatusResponse(10L, 100L, JobRunStatus.CANCELLED));

        mockMvc.perform(patch("/api/v1/jobs/10/runs/100/cancel").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(10))
                .andExpect(jsonPath("$.runId").value(100))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void getJobStatusReturnsStatus() throws Exception {
        when(jobService.getJobStatus(10L, 1L)).thenReturn(new JobStatusResponse(10L, JobStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/jobs/10/status").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(10))
                .andExpect(jsonPath("$.jobStatus").value("ACTIVE"));
    }

    @Test
    void deleteJobReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/jobs/10").with(user(principal())))
                .andExpect(status().isNoContent());

        verify(jobService).deleteJob(10L, 1L);
    }

    @Test
    void jobsWithoutJwtReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private UserPrincipal principal() {
        return new UserPrincipal(1L, "naveen", "naveen@example.com", "$2a$hash");
    }

    private JobResponse jobResponse() {
        return new JobResponse(
                10L,
                "job",
                "description",
                JobType.HTTP,
                ScheduleType.IMMEDIATE,
                null,
                null,
                null,
                Map.of("url", "https://example.com"),
                JobStatus.ACTIVE,
                3,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private String validImmediateJson() {
        return """
                {
                  "name": "job",
                  "description": "description",
                  "jobType": "HTTP",
                  "scheduleType": "IMMEDIATE",
                  "payload": {
                    "url": "https://example.com"
                  }
                }
                """;
    }
}
