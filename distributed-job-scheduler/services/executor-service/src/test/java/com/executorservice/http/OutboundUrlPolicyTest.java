package com.executorservice.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.executorservice.config.ExecutorProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboundUrlPolicyTest {

    @Test
    void allowlistedPublicAddressIsAccepted() {
        ExecutorProperties properties = new ExecutorProperties();
        properties.getHttp().setAllowedHosts(List.of("203.0.113.10"));
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

        assertThatCode(() -> policy.validate(URI.create("https://203.0.113.10/test")))
                .doesNotThrowAnyException();
    }

    @Test
    void localhostIsBlockedEvenWhenAllowlisted() {
        ExecutorProperties properties = new ExecutorProperties();
        properties.getHttp().setAllowedHosts(List.of("localhost"));
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

        assertThatThrownBy(() -> policy.validate(URI.create("http://localhost:8080/test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked address");
    }
}
