package com.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.services.job-service-url=http://localhost:18080",
                "spring.data.redis.port=6399",
                "security.jwt.secret=change-this-development-secret-key-minimum-32-characters"
        }
)
@AutoConfigureWebTestClient
class RedisUnavailableGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void redisUnavailableFailsClosedWithoutStackTrace() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("RATE_LIMIT_UNAVAILABLE")
                        .doesNotContain("Exception")
                        .doesNotContain("localhost:18080"));
    }
}
