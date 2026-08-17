package com.executorservice.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "executor")
public class ExecutorProperties {

    private String instanceId;
    private Kafka kafka = new Kafka();
    private Http http = new Http();

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

    public static class Kafka {
        private String topic = "run";
        private Integer concurrency = 3;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
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
}
