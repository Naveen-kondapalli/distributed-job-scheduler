package com.executorservice.heartbeat;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.observability.ExecutorMetrics;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class ExecutorHeartbeatService implements ApplicationListener<ApplicationReadyEvent> {

    private static final String REFRESH_IF_OWNED_SCRIPT = """
            if redis.call('GET', KEYS[1]) == false then
              return redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX') and 1 or 0
            end
            local current = cjson.decode(redis.call('GET', KEYS[1]))
            if current['instanceToken'] == ARGV[3] then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return -1
            """;

    private static final String DELETE_IF_OWNED_SCRIPT = """
            if redis.call('GET', KEYS[1]) == false then
              return 0
            end
            local current = cjson.decode(redis.call('GET', KEYS[1]))
            if current['instanceToken'] == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return -1
            """;

    private final StringRedisTemplate redisTemplate;
    private final ExecutorHeartbeatKeys keys;
    private final ExecutorProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String executorInstanceId;
    private final String serviceName;
    private final String applicationVersion;
    private final LocalDateTime startedAt;
    private final String hostname;
    private final String instanceToken = UUID.randomUUID().toString();
    private final ExecutorMetrics metrics;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public ExecutorHeartbeatService(
            StringRedisTemplate redisTemplate,
            ExecutorHeartbeatKeys keys,
            ExecutorProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            String executorInstanceId,
            @Value("${spring.application.name:executor-service}") String serviceName,
            java.util.Optional<BuildProperties> buildProperties,
            ExecutorMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.keys = keys;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.executorInstanceId = executorInstanceId;
        this.serviceName = serviceName;
        this.applicationVersion = buildProperties.map(BuildProperties::getVersion).orElse("unknown");
        this.startedAt = LocalDateTime.now(clock);
        this.hostname = hostname();
        this.metrics = metrics;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        registerOrRefresh();
    }

    public boolean registerOrRefresh() {
        if (!properties.getHeartbeat().isEnabled()) {
            return false;
        }
        String key = heartbeatKey();
        try {
            String value = heartbeatValue();
            Long result = redisTemplate.execute(
                    new DefaultRedisScript<>(REFRESH_IF_OWNED_SCRIPT, Long.class),
                    List.of(key),
                    value,
                    String.valueOf(properties.getHeartbeat().ttl().toMillis()),
                    instanceToken
            );
            if (result != null && result == 1L) {
                metrics.heartbeat("success");
                if (registered.compareAndSet(false, true)) {
                    log.info("Executor heartbeat registered: executorId={}", executorInstanceId);
                } else {
                    log.debug("Executor heartbeat refreshed: executorId={}", executorInstanceId);
                }
                return true;
            }
            if (result != null && result == -1L) {
                metrics.heartbeat("failure");
                throw new DuplicateExecutorInstanceException("Active heartbeat already exists for executorId=" + executorInstanceId);
            }
            metrics.heartbeat("failure");
            log.warn("Executor heartbeat update failed: executorId={}, reason={}", executorInstanceId, "identity claim was not applied");
            return false;
        } catch (DuplicateExecutorInstanceException ex) {
            log.error("Duplicate Executor instance ID detected: executorId={}, reason={}", executorInstanceId, ex.getMessage());
            throw ex;
        } catch (DataAccessException ex) {
            metrics.heartbeat("failure");
            log.warn("Executor heartbeat update failed: executorId={}, reason={}", executorInstanceId, ex.getMessage());
            return false;
        } catch (Exception ex) {
            metrics.heartbeat("failure");
            log.warn("Executor heartbeat update failed: executorId={}, reason={}", executorInstanceId, ex.getMessage(), ex);
            return false;
        }
    }

    @PreDestroy
    public void removeHeartbeat() {
        if (!properties.getHeartbeat().isEnabled()) {
            return;
        }
        try {
            Long result = redisTemplate.execute(
                    new DefaultRedisScript<>(DELETE_IF_OWNED_SCRIPT, Long.class),
                    List.of(heartbeatKey()),
                    instanceToken
            );
            if (result != null && result > 0) {
                log.info("Executor heartbeat removed: executorId={}", executorInstanceId);
            } else if (result != null && result == -1L) {
                log.warn("Executor heartbeat not removed because it is owned by another process: executorId={}", executorInstanceId);
            }
        } catch (Exception ex) {
            log.warn("Executor heartbeat removal failed: executorId={}, reason={}", executorInstanceId, ex.getMessage());
        }
    }

    public String heartbeatKey() {
        return keys.heartbeatKey(executorInstanceId);
    }

    public String instanceToken() {
        return instanceToken;
    }

    private String heartbeatValue() throws Exception {
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(
                executorInstanceId,
                serviceName,
                startedAt,
                LocalDateTime.now(clock),
                hostname,
                applicationVersion,
                instanceToken
        );
        return objectMapper.writeValueAsString(heartbeat);
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "unknown";
        }
    }
}
