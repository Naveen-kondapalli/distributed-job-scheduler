package com.jobservice.observability;

import com.jobservice.enums.JobRunStatus;
import com.jobservice.enums.ScheduleType;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobServiceMetrics {

    private final MeterRegistry meterRegistry;

    public void jobCreated(ScheduleType scheduleType) {
        meterRegistry.counter("scheduler.jobs.created", "schedule_type", scheduleType.name()).increment();
    }

    public void jobUpdated(ScheduleType scheduleType) {
        meterRegistry.counter("scheduler.jobs.updated", "schedule_type", scheduleType.name()).increment();
    }

    public void jobPaused() {
        meterRegistry.counter("scheduler.jobs.paused").increment();
    }

    public void jobResumed() {
        meterRegistry.counter("scheduler.jobs.resumed").increment();
    }

    public void jobCancelled() {
        meterRegistry.counter("scheduler.jobs.cancelled").increment();
    }

    public void jobRunCancellationRequested(JobRunStatus resultingStatus) {
        meterRegistry.counter("scheduler.job_runs.cancel.requested", "resulting_status", resultingStatus.name()).increment();
    }
}
