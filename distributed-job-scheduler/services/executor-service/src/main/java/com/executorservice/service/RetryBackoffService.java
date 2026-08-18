package com.executorservice.service;

import com.executorservice.config.ExecutorProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetryBackoffService {

    private final ExecutorProperties properties;

    public Duration calculateDelay(int retryCount, Duration retryAfter) {
        long maxDelayMs = properties.getRetry().getMaxDelayMs();
        if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()) {
            return Duration.ofMillis(Math.min(retryAfter.toMillis(), maxDelayMs));
        }

        int exponent = Math.max(retryCount - 1, 0);
        long multiplier = 1L << Math.min(exponent, 30);
        long baseDelay = properties.getRetry().getBaseDelayMs();
        long exponential = baseDelay * multiplier;
        if (exponential < 0) {
            exponential = maxDelayMs;
        }
        long capped = Math.min(exponential, maxDelayMs);
        double jitterFactor = Math.max(0, properties.getRetry().getJitterFactor());
        long jitterRange = Math.round(capped * jitterFactor);
        long jitter = jitterRange == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);
        return Duration.ofMillis(Math.max(0, Math.min(maxDelayMs, capped + jitter)));
    }
}
