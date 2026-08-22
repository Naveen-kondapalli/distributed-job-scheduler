package com.apigateway.filter;

import com.apigateway.config.GatewayProperties;
import com.apigateway.observability.GatewayMetrics;
import com.apigateway.security.JwtPrincipal;
import com.apigateway.security.JwtValidator;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

@Component
public class GatewayBoundaryFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final Logger log = LoggerFactory.getLogger(GatewayBoundaryFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> SENSITIVE_IDENTITY_HEADERS = List.of("X-User-Id", "X-User-Email", "X-Internal-User");

    private final JwtValidator jwtValidator;
    private final RedisTokenBucketRateLimiter rateLimiter;
    private final GatewayProperties properties;
    private final GatewayErrorWriter errorWriter;
    private final GatewayMetrics metrics;

    public GatewayBoundaryFilter(
            JwtValidator jwtValidator,
            RedisTokenBucketRateLimiter rateLimiter,
            GatewayProperties properties,
            GatewayErrorWriter errorWriter,
            GatewayMetrics metrics
    ) {
        this.jwtValidator = jwtValidator;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.metrics = metrics;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long started = System.nanoTime();
        String path = exchange.getRequest().getPath().value();
        String route = routeName(path);
        String correlationId = resolveCorrelationId(exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER));
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        if (route.equals("internal")) {
            return chain.filter(withCorrelation(exchange, correlationId));
        }
        if (route.equals("unknown")) {
            return chain.filter(withCorrelation(exchange, correlationId));
        }

        ServerWebExchange sanitized = sanitize(withCorrelation(exchange, correlationId));
        Long contentLength = sanitized.getRequest().getHeaders().getContentLength();
        if (contentLength > properties.getMaxRequestSize().toBytes()) {
            return finish(sanitized, route, "payload_too_large", started,
                    errorWriter.write(sanitized, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request body is too large"));
        }

        JwtPrincipal principal = null;
        if (route.equals("jobs")) {
            principal = authenticate(sanitized);
            if (principal == null) {
                return finish(sanitized, route, "unauthorized", started,
                        errorWriter.write(sanitized, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid bearer token"));
            }
        }

        String key = route.equals("auth") ? unauthenticatedKey(sanitized) : "subject:" + principal.subject();
        GatewayProperties.Policy policy = route.equals("auth")
                ? properties.getRateLimit().getAuth()
                : properties.getRateLimit().getAuthenticated();
        return rateLimiter.isAllowed(route, key, policy)
                .flatMap(allowed -> {
                    if (!allowed) {
                        metrics.rateLimited(route);
                        sanitized.getResponse().getHeaders().set("Retry-After", "1");
                        return finish(sanitized, route, "rate_limited", started,
                                errorWriter.write(sanitized, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests"));
                    }
                    return finish(sanitized, route, "success", started,
                            chain.filter(sanitized)
                                    .timeout(properties.getResponseTimeout())
                                    .onErrorResume(TimeoutException.class, exception ->
                                            errorWriter.write(sanitized, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", "Upstream service timed out"))
                                    .onErrorResume(exception ->
                                            errorWriter.write(sanitized, HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Upstream service unavailable")));
                })
                .onErrorResume(exception -> finish(sanitized, route, "rate_limit_unavailable", started,
                        errorWriter.write(sanitized, HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE", "Rate limiter unavailable")));
    }

    private Mono<Void> finish(ServerWebExchange exchange, String route, String result, long started, Mono<Void> work) {
        putMdc(exchange, route);
        return work.doFinally(signalType -> cleanup(signalType, exchange, route, result, started));
    }

    private void cleanup(SignalType signalType, ServerWebExchange exchange, String route, String result, long started) {
        putMdc(exchange, route);
        long durationNanos = System.nanoTime() - started;
        String finalResult = result;
        if (result.equals("success")) {
            HttpStatusCode code = exchange.getResponse().getStatusCode();
            HttpStatus status = HttpStatus.resolve(code == null ? 200 : code.value());
            finalResult = classifyStatus(status);
        }
        metrics.request(route, finalResult, durationNanos);
        log.info("Gateway request completed: route={}, method={}, status={}, durationMs={}",
                route,
                exchange.getRequest().getMethod(),
                exchange.getResponse().getStatusCode(),
                Duration.ofNanos(durationNanos).toMillis());
        MDC.clear();
    }

    private String classifyStatus(HttpStatus status) {
        if (status == null) {
            return "success";
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return "unauthorized";
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return "rate_limited";
        }
        if (status.is5xxServerError()) {
            return "upstream_error";
        }
        return "success";
    }

    private ServerWebExchange withCorrelation(ServerWebExchange exchange, String correlationId) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();
        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange sanitize(ServerWebExchange exchange) {
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate();
        SENSITIVE_IDENTITY_HEADERS.forEach(header -> builder.headers(headers -> headers.remove(header)));
        return exchange.mutate().request(builder.build()).build();
    }

    private JwtPrincipal authenticate(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return jwtValidator.validate(authorization.substring(BEARER_PREFIX.length()).trim());
    }

    private String routeName(String path) {
        if (path.startsWith("/actuator/")) {
            return "internal";
        }
        if (path.startsWith("/api/v1/auth/")) {
            return "auth";
        }
        if (path.startsWith("/api/v1/jobs")) {
            return "jobs";
        }
        return "unknown";
    }

    private String resolveCorrelationId(String incoming) {
        if (incoming != null && incoming.length() <= 64 && incoming.matches("[A-Za-z0-9._:-]+")) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    private String unauthenticatedKey(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "remote:unknown";
        }
        return "remote:" + remoteAddress.getAddress().getHostAddress();
    }

    private void putMdc(ServerWebExchange exchange, String route) {
        MDC.put("correlationId", exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER));
        MDC.put("route", route);
    }
}
