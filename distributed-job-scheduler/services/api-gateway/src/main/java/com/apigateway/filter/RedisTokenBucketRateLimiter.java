package com.apigateway.filter;

import com.apigateway.config.GatewayProperties.Policy;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisTokenBucketRateLimiter {

    private static final RedisScript<List> SCRIPT = RedisScript.of("""
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])
            local fill_time = capacity / rate
            local last_tokens = tonumber(redis.call("get", tokens_key))
            if last_tokens == nil then
              last_tokens = capacity
            end
            local last_refreshed = tonumber(redis.call("get", timestamp_key))
            if last_refreshed == nil then
              last_refreshed = 0
            end
            local delta = math.max(0, now - last_refreshed)
            local filled_tokens = math.min(capacity, last_tokens + (delta * rate))
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens
            if allowed then
              new_tokens = filled_tokens - requested
            end
            redis.call("setex", tokens_key, ttl, new_tokens)
            redis.call("setex", timestamp_key, ttl, now)
            return { allowed and 1 or 0, new_tokens }
            """, List.class);

    private final ReactiveStringRedisTemplate redisTemplate;

    public RedisTokenBucketRateLimiter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Boolean> isAllowed(String route, String key, Policy policy) {
        int ttl = Math.max(1, (int) Math.ceil((double) policy.getBurstCapacity() / policy.getReplenishRate()) * 2);
        String safeKey = key.replaceAll("[^A-Za-z0-9_.:@-]", "_");
        List<String> keys = List.of(
                "scheduler:gateway:rate_limit:{" + route + ":" + safeKey + "}:tokens",
                "scheduler:gateway:rate_limit:{" + route + ":" + safeKey + "}:timestamp"
        );
        List<String> args = List.of(
                String.valueOf(policy.getReplenishRate()),
                String.valueOf(policy.getBurstCapacity()),
                String.valueOf(Instant.now().getEpochSecond()),
                "1",
                String.valueOf(ttl)
        );
        return redisTemplate.execute(SCRIPT, keys, args)
                .next()
                .map(result -> ((List<?>) result).get(0))
                .map(value -> Long.parseLong(value.toString()) == 1L);
    }
}
