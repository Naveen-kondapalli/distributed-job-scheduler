package com.watcherservice.service;

import static org.mockito.Mockito.verify;

import com.watcherservice.observability.WatcherMetrics;
import com.watcherservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JobPollingServiceTest {

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-12T10:15:30Z");
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 12, 15, 45, 30);

    @Mock
    private JobClaimService jobClaimService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private JobPollingService jobPollingService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, APPLICATION_ZONE);
        jobPollingService = new JobPollingService(jobClaimService, fixedClock, new WatcherMetrics(new SimpleMeterRegistry(), outboxEventRepository));
        ReflectionTestUtils.setField(jobPollingService, "batchSize", 100);
    }

    @Test
    void pollDueFutureJobsUsesInjectedClockAndConfiguredBatchSize() {
        ReflectionTestUtils.setField(jobPollingService, "batchSize", 25);

        jobPollingService.pollDueFutureJobs();

        verify(jobClaimService).claimDueOccurrences(FIXED_NOW, 25);
    }
}
