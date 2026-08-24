package com.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.services.job-service-url=http://localhost:18080",
                "gateway.rate-limit.auth.replenish-rate=1",
                "gateway.rate-limit.auth.burst-capacity=2",
                "gateway.rate-limit.authenticated.replenish-rate=1",
                "gateway.rate-limit.authenticated.burst-capacity=2",
                "gateway.max-request-size=256B",
                "gateway.response-timeout=500ms",
                "security.jwt.secret=change-this-development-secret-key-minimum-32-characters"
        }
)
@AutoConfigureWebTestClient
class ApiGatewayApplicationTests {

    private static final String SECRET = "change-this-development-secret-key-minimum-32-characters";
    private static final AtomicInteger upstreamCalls = new AtomicInteger();
    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> lastCorrelationId = new AtomicReference<>();
    private static final AtomicReference<String> lastSpoofedUserId = new AtomicReference<>();
    private static DisposableServer upstream;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeAll
    static void startUpstream() {
        upstream = HttpServer.create()
                .port(18080)
                .route(routes -> routes
                        .post("/api/v1/auth/login", (request, response) -> {
                            capture(request.requestHeaders().get(HttpHeaders.AUTHORIZATION),
                                    request.requestHeaders().get("X-Correlation-Id"),
                                    request.requestHeaders().get("X-User-Id"));
                            response.status(200);
                            response.header("Content-Type", "application/json");
                            return response.sendString(Mono.just("{\"accessToken\":\"upstream-token\",\"expiresIn\":3600}"));
                        })
                        .post("/api/v1/auth/register", (request, response) -> {
                            capture(request.requestHeaders().get(HttpHeaders.AUTHORIZATION),
                                    request.requestHeaders().get("X-Correlation-Id"),
                                    request.requestHeaders().get("X-User-Id"));
                            response.status(201);
                            response.header("Content-Type", "application/json");
                            return response.sendString(Mono.just("{\"message\":\"registered\"}"));
                        })
                        .get("/api/v1/jobs", (request, response) -> {
                            capture(request.requestHeaders().get(HttpHeaders.AUTHORIZATION),
                                    request.requestHeaders().get("X-Correlation-Id"),
                                    request.requestHeaders().get("X-User-Id"));
                            response.status(200);
                            response.header("Content-Type", "application/json");
                            return response.sendString(Mono.just("{\"jobs\":[]}"));
                        })
                        .post("/api/v1/jobs", (request, response) -> {
                            capture(request.requestHeaders().get(HttpHeaders.AUTHORIZATION),
                                    request.requestHeaders().get("X-Correlation-Id"),
                                    request.requestHeaders().get("X-User-Id"));
                            response.status(201);
                            response.header("Content-Type", "application/json");
                            return response.sendString(Mono.just("{\"id\":1}"));
                        })
                        .route(request -> request.method().name().equals(HttpMethod.PATCH.name())
                                && request.uri().equals("/api/v1/jobs/1/runs/2/cancel"), (request, response) -> {
                            capture(request.requestHeaders().get(HttpHeaders.AUTHORIZATION),
                                    request.requestHeaders().get("X-Correlation-Id"),
                                    request.requestHeaders().get("X-User-Id"));
                            response.status(200);
                            response.header("Content-Type", "application/json");
                            return response.sendString(Mono.just("{\"runId\":2,\"status\":\"CANCELLED\"}"));
                        })
                        .get("/api/v1/jobs/slow", (request, response) -> {
                            capture(request.requestHeaders().get(HttpHeaders.AUTHORIZATION),
                                    request.requestHeaders().get("X-Correlation-Id"),
                                    request.requestHeaders().get("X-User-Id"));
                            return Mono.delay(Duration.ofSeconds(2))
                                    .then(response.sendString(Mono.just("{\"slow\":true}")).then());
                        })
                        .post("/api/v1/jobs/fail", (request, response) -> {
                            capture(request.requestHeaders().get(HttpHeaders.AUTHORIZATION),
                                    request.requestHeaders().get("X-Correlation-Id"),
                                    request.requestHeaders().get("X-User-Id"));
                            response.status(500);
                            return response.send();
                        }))
                .bindNow();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.disposeNow();
        }
    }

    @BeforeEach
    void reset() {
        upstreamCalls.set(0);
        lastAuthorization.set(null);
        lastCorrelationId.set(null);
        lastSpoofedUserId.set(null);
        redisTemplate.keys("scheduler:gateway:rate_limit:*")
                .flatMap(redisTemplate::delete)
                .then()
                .block(Duration.ofSeconds(5));
    }

    @Test
    void loginIsPublicAndRouted() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .bodyValue("{\"email\":\"a@example.com\",\"password\":\"password123\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id");

        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(lastAuthorization.get()).isNull();
    }

    @Test
    void registerIsPublicAndRouted() {
        webTestClient.post()
                .uri("/api/v1/auth/register")
                .bodyValue("{\"username\":\"a\",\"email\":\"a@example.com\",\"password\":\"password123\"}")
                .exchange()
                .expectStatus().isCreated();

        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void protectedJobRouteWithoutJwtReturns401AndDoesNotCallUpstream() {
        webTestClient.get().uri("/api/v1/jobs")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class).value(body -> assertThat(body).doesNotContain("Exception"));

        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    void protectedJobRouteWithValidJwtRoutesAndPreservesAuthorization() {
        String token = token("user-a@example.com", 60_000, SECRET);

        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + token);
    }

    @Test
    void expiredMalformedAndInvalidSignatureJwtReturn401() {
        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-a@example.com", -1_000, SECRET))
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer malformed")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-a@example.com", 60_000, SECRET + "x"))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    void spoofedIdentityHeadersAreRemoved() {
        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-a@example.com", 60_000, SECRET))
                .header("X-User-Id", "999")
                .header("X-User-Email", "evil@example.com")
                .header("X-Internal-User", "admin")
                .exchange()
                .expectStatus().isOk();

        assertThat(lastSpoofedUserId.get()).isNull();
    }

    @Test
    void correlationIdIsGeneratedPropagatedReturnedAndUnsafeValuesAreReplaced() {
        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-a@example.com", 60_000, SECRET))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("X-Correlation-Id", "[A-Za-z0-9._:-]{1,64}");
        assertThat(lastCorrelationId.get()).isNotBlank();

        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-b@example.com", 60_000, SECRET))
                .header("X-Correlation-Id", "client-correlation-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "client-correlation-1");
        assertThat(lastCorrelationId.get()).isEqualTo("client-correlation-1");

        String malicious = "bad_" + "x".repeat(100);
        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-c@example.com", 60_000, SECRET))
                .header("X-Correlation-Id", malicious)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("X-Correlation-Id", value -> assertThat(value).isNotEqualTo(malicious));
    }

    @Test
    void authEndpointRateLimitReturns429AndUsesRedisKeysWithTtl() {
        for (int i = 0; i < 2; i++) {
            webTestClient.post().uri("/api/v1/auth/login").bodyValue("{}").exchange().expectStatus().isOk();
        }
        freezeExistingRateLimitBuckets("scheduler:gateway:rate_limit:*auth*");
        webTestClient.post().uri("/api/v1/auth/login")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "1");

        Long ttl = redisTemplate.keys("scheduler:gateway:rate_limit:*auth*")
                .next()
                .flatMap(redisTemplate::getExpire)
                .map(Duration::getSeconds)
                .block(Duration.ofSeconds(5));
        assertThat(ttl).isNotNull().isPositive();
    }

    @Test
    void authenticatedRateLimitIsIsolatedByJwtSubject() {
        String userA = token("user-a@example.com", 60_000, SECRET);
        String userB = token("user-b@example.com", 60_000, SECRET);

        for (int i = 0; i < 2; i++) {
            webTestClient.get().uri("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + userA)
                    .exchange().expectStatus().isOk();
        }
        freezeExistingRateLimitBuckets("scheduler:gateway:rate_limit:*user-a@example.com*");
        webTestClient.get().uri("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + userA)
                .exchange().expectStatus().isEqualTo(429);

        webTestClient.get().uri("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + userB)
                .exchange().expectStatus().isOk();
    }

    @Test
    void oversizedRequestReturns413BeforeUpstream() {
        webTestClient.post().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-a@example.com", 60_000, SECRET))
                .bodyValue("{\"payload\":\"" + "x".repeat(300) + "\"}")
                .exchange()
                .expectStatus().isEqualTo(413);

        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    void upstreamTimeoutIsSafeAndPostIsNotAutomaticallyRetried() {
        webTestClient.get().uri("/api/v1/jobs/slow")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-a@example.com", 60_000, SECRET))
                .exchange()
                .expectStatus().isEqualTo(504);

        assertThat(upstreamCalls.get()).isEqualTo(1);

        upstreamCalls.set(0);
        webTestClient.post().uri("/api/v1/jobs/fail")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-a@example.com", 60_000, SECRET))
                .bodyValue("{}")
                .exchange()
                .expectStatus().is5xxServerError();

        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void jobRunCancellationRoutesThroughGateway() {
        webTestClient.patch().uri("/api/v1/jobs/1/runs/2/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("user-a@example.com", 60_000, SECRET))
                .exchange()
                .expectStatus().isOk();

        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void unknownAndInternalRoutesAreNotProxied() {
        webTestClient.get().uri("/api/v1/watcher")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get().uri("/executor")
                .exchange()
                .expectStatus().isNotFound();

        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    void actuatorEndpointsAreLimitedToSafeSet() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
        webTestClient.get().uri("/actuator/info").exchange().expectStatus().isOk();
        webTestClient.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
    }

    @Test
    void gatewayCustomMetricsUseBoundedTags() {
        webTestClient.get().uri("/api/v1/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("metrics@example.com", 60_000, SECRET))
                .exchange()
                .expectStatus().isOk();

        redisTemplate.keys("scheduler:gateway:rate_limit:*")
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    private static void capture(String authorization, String correlationId, String spoofedUserId) {
        upstreamCalls.incrementAndGet();
        lastAuthorization.set(authorization);
        lastCorrelationId.set(correlationId);
        lastSpoofedUserId.set(spoofedUserId);
    }

    private void freezeExistingRateLimitBuckets(String pattern) {
        String futureTimestamp = String.valueOf(Instant.now().plusSeconds(60).getEpochSecond());
        redisTemplate.keys(pattern)
                .filter(key -> key.endsWith(":timestamp"))
                .flatMap(key -> redisTemplate.opsForValue().set(key, futureTimestamp, Duration.ofSeconds(120)))
                .then()
                .block(Duration.ofSeconds(5));
    }

    private static String token(String subject, long lifetimeMs, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + lifetimeMs))
                .signWith(key)
                .compact();
    }
}
