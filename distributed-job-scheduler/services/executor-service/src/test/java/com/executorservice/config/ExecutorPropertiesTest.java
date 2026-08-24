package com.executorservice.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExecutorPropertiesTest {

    @Test
    void heartbeatTtlMustBeSafelyGreaterThanInterval() {
        ExecutorProperties properties = new ExecutorProperties();
        properties.getHeartbeat().setIntervalMs(30000);
        properties.getHeartbeat().setTtlSeconds(10);

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ttl-seconds");
    }
}
