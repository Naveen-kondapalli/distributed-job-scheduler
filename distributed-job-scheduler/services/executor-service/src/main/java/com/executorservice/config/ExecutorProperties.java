package com.executorservice.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "executor")
public class ExecutorProperties implements InitializingBean {

    private String instanceId;
    private Kafka kafka = new Kafka();
    private Http http = new Http();
    private Retry retry = new Retry();
    private Execution execution = new Execution();
    private Heartbeat heartbeat = new Heartbeat();

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(Heartbeat heartbeat) {
        this.heartbeat = heartbeat;
    }

    @Override
    public void afterPropertiesSet() {
        heartbeat.validate();
    }

    public static class Kafka {
        private String topic = "run";
        private String retryTopic = "retry";
        private String deadTopic = "dead";
        private Integer concurrency = 3;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getRetryTopic() {
            return retryTopic;
        }

        public void setRetryTopic(String retryTopic) {
            this.retryTopic = retryTopic;
        }

        public String getDeadTopic() {
            return deadTopic;
        }

        public void setDeadTopic(String deadTopic) {
            this.deadTopic = deadTopic;
        }

        public Integer getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(Integer concurrency) {
            this.concurrency = concurrency;
        }
    }

    public static class Http {
        private long connectTimeoutMs = 3000;
        private long readTimeoutMs = 10000;
        private List<String> allowedHosts = new ArrayList<>(List.of("jsonplaceholder.typicode.com"));
        private String idempotencyKeyHeader = "Idempotency-Key";

        public Duration connectTimeout() {
            return Duration.ofMillis(connectTimeoutMs);
        }

        public Duration readTimeout() {
            return Duration.ofMillis(readTimeoutMs);
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
        }

        public String getIdempotencyKeyHeader() {
            return idempotencyKeyHeader;
        }

        public void setIdempotencyKeyHeader(String idempotencyKeyHeader) {
            this.idempotencyKeyHeader = idempotencyKeyHeader;
        }
    }

    public static class Retry {
        private long baseDelayMs = 2000;
        private long maxDelayMs = 60000;
        private double jitterFactor = 0.20;
        private long schedulerIntervalMs = 500;
        private int batchSize = 100;

        public long getBaseDelayMs() {
            return baseDelayMs;
        }

        public void setBaseDelayMs(long baseDelayMs) {
            this.baseDelayMs = baseDelayMs;
        }

        public long getMaxDelayMs() {
            return maxDelayMs;
        }

        public void setMaxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
        }

        public double getJitterFactor() {
            return jitterFactor;
        }

        public void setJitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
        }

        public long getSchedulerIntervalMs() {
            return schedulerIntervalMs;
        }

        public void setSchedulerIntervalMs(long schedulerIntervalMs) {
            this.schedulerIntervalMs = schedulerIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class Execution {
        private long runningTimeoutMs = 60000;

        public long getRunningTimeoutMs() {
            return runningTimeoutMs;
        }

        public void setRunningTimeoutMs(long runningTimeoutMs) {
            this.runningTimeoutMs = runningTimeoutMs;
        }
    }

    public static class Heartbeat {
        private boolean enabled = true;
        private long intervalMs = 10000;
        private long ttlSeconds = 30;

        public Duration interval() {
            return Duration.ofMillis(intervalMs);
        }

        public Duration ttl() {
            return Duration.ofSeconds(ttlSeconds);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        private void validate() {
            if (intervalMs <= 0) {
                throw new IllegalStateException("executor.heartbeat.interval-ms must be greater than 0");
            }
            if (ttlSeconds <= 0) {
                throw new IllegalStateException("executor.heartbeat.ttl-seconds must be greater than 0");
            }
            if (ttl().compareTo(interval().multipliedBy(2)) <= 0) {
                throw new IllegalStateException("executor.heartbeat.ttl-seconds must be safely greater than executor.heartbeat.interval-ms");
            }
        }
    }
}
