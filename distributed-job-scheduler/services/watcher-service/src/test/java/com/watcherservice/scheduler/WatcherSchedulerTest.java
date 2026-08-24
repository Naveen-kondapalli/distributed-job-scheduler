package com.watcherservice.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.watcherservice.service.JobPollingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WatcherSchedulerTest {

    @Mock
    private JobPollingService jobPollingService;

    @InjectMocks
    private WatcherScheduler watcherScheduler;

    @Test
    void pollDueFutureJobsDelegatesToPollingService() {
        watcherScheduler.pollDueFutureJobs();

        verify(jobPollingService).pollDueFutureJobs();
    }

    @Test
    void pollDueFutureJobsHandlesPollingExceptions() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(jobPollingService)
                .pollDueFutureJobs();

        watcherScheduler.pollDueFutureJobs();

        verify(jobPollingService).pollDueFutureJobs();
    }
}
