package com.executorservice.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecutorHeartbeatKeysTest {

    @Test
    void buildsNamespacedHeartbeatKey() {
        assertThat(new ExecutorHeartbeatKeys().heartbeatKey("executor-1"))
                .isEqualTo("scheduler:executor:heartbeat:executor-1");
    }
}
