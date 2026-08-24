package com.jobservice.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(properties = "job-run.cancellation.signal-ttl-seconds=5")
class CancellationSignalServiceRedisIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    @Autowired
    private CancellationSignalService cancellationSignalService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CancellationSignalKeys keys;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(keys.key(9001L));
    }

    @Test
    void realRedisCancellationSignalContainsSafePayloadAndTtl() {
        cancellationSignalService.createSignal(9001L, 44L);

        String key = keys.key(9001L);
        String payload = redisTemplate.opsForValue().get(key);
        Long ttl = redisTemplate.getExpire(key);

        assertThat(payload).contains("\"runId\":9001");
        assertThat(payload).contains("\"requestedBy\":\"user:44\"");
        assertThat(ttl).isNotNull();
        assertThat(ttl).isPositive();
        assertThat(ttl).isNotEqualTo(-1L);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }
}
