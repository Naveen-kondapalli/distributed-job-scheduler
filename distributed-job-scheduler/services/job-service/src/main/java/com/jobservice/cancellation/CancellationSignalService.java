package com.jobservice.cancellation;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CancellationSignalService {

    private final StringRedisTemplate redisTemplate;
    private final CancellationSignalKeys keys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${job-run.cancellation.signal-ttl-seconds:60}")
    private long signalTtlSeconds;

    public void createSignal(Long runId, Long requestedByUserId) {
        try {
            JobRunCancellationSignal signal = new JobRunCancellationSignal(
                    runId,
                    LocalDateTime.now(clock),
                    "user:" + requestedByUserId
            );
            redisTemplate.opsForValue().set(
                    keys.key(runId),
                    objectMapper.writeValueAsString(signal),
                    Duration.ofSeconds(signalTtlSeconds)
            );
        } catch (Exception ex) {
            throw new CancellationSignalException("Cancellation intent was saved, but Redis signal delivery failed", ex);
        }
    }
}
