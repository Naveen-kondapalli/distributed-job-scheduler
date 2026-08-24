package com.executorservice.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.enums.FailureCategory;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpJobExecutorTest {

    private HttpJobExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new HttpJobExecutor(
                mock(HttpClient.class),
                new ObjectMapper(),
                new ExecutorProperties(),
                mock(OutboundUrlPolicy.class)
        );
    }

    @Test
    void connectionResetIsRetryable() {
        RuntimeException wrapped = new RuntimeException(new SocketException("Connection reset"));

        assertThat(executor.classifyException(wrapped)).isEqualTo(FailureCategory.RETRYABLE);
    }

    @Test
    void connectTimeoutIsRetryable() {
        RuntimeException wrapped = new RuntimeException(new HttpConnectTimeoutException("connect timeout"));

        assertThat(executor.classifyException(wrapped)).isEqualTo(FailureCategory.RETRYABLE);
    }

    @Test
    void readTimeoutIsRetryable() {
        RuntimeException wrapped = new RuntimeException(new HttpTimeoutException("request timeout"));

        assertThat(executor.classifyException(wrapped)).isEqualTo(FailureCategory.RETRYABLE);
    }

    @Test
    void malformedUriIsNonRetryable() {
        assertThat(executor.classifyException(new IllegalArgumentException("invalid URI")))
                .isEqualTo(FailureCategory.NON_RETRYABLE);
    }

    @Test
    void ssrfRejectionIsNonRetryable() {
        assertThat(executor.classifyException(new IllegalArgumentException("HTTP target resolves to a blocked address")))
                .isEqualTo(FailureCategory.NON_RETRYABLE);
    }
}
