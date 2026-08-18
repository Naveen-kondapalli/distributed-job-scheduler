package com.executorservice.http;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.dto.HttpJobPayload;
import com.executorservice.enums.FailureCategory;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class HttpJobExecutor {

    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorProperties properties;
    private final OutboundUrlPolicy outboundUrlPolicy;

    public HttpExecutionResult execute(HttpJobPayload payload, Long runId) {
        long started = System.nanoTime();
        try {
            ValidatedPayload validated = validate(payload);
            HttpRequest request = buildRequest(validated, runId);
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode <= 299) {
                return new HttpExecutionResult(true, statusCode, null, null, null, durationMs);
            }
            return new HttpExecutionResult(
                    false,
                    statusCode,
                    classifyStatus(statusCode),
                    "HTTP " + statusCode + " returned by target",
                    retryAfter(response),
                    durationMs
            );
        } catch (HttpTimeoutException ex) {
            return failed(started, FailureCategory.RETRYABLE, "Connection timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed(started, FailureCategory.RETRYABLE, "Execution interrupted");
        } catch (Exception ex) {
            return failed(started, classifyException(ex), safeReason(ex));
        }
    }

    private ValidatedPayload validate(HttpJobPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Invalid HTTP job payload");
        }
        if (payload.method() == null || payload.method().isBlank()) {
            throw new IllegalArgumentException("HTTP method is required");
        }
        String method = payload.method().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(method)) {
            throw new IllegalArgumentException("Unsupported HTTP method");
        }
        if (payload.url() == null || payload.url().isBlank()) {
            throw new IllegalArgumentException("HTTP URL is required");
        }
        URI uri = URI.create(payload.url());
        outboundUrlPolicy.validate(uri);
        return new ValidatedPayload(method, uri, payload.headers(), payload.body());
    }

    private HttpRequest buildRequest(ValidatedPayload payload, Long runId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(payload.uri())
                .timeout(properties.getHttp().readTimeout());

        addHeaders(builder, payload.headers());
        addIdempotencyKey(builder, payload.headers(), runId);

        if (Set.of("GET", "DELETE", "HEAD", "OPTIONS").contains(payload.method())) {
            builder.method(payload.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            String body = payload.body() == null ? "" : objectMapper.writeValueAsString(payload.body());
            builder.method(payload.method(), HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            builder.header("Content-Type", "application/json");
        }
        return builder.build();
    }

    private void addHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null) {
                throw new IllegalArgumentException("HTTP headers must be string name/value pairs");
            }
            builder.header(name, value);
        });
    }

    private void addIdempotencyKey(HttpRequest.Builder builder, Map<String, String> headers, Long runId) {
        String headerName = properties.getHttp().getIdempotencyKeyHeader();
        if (headerName == null || headerName.isBlank()) {
            return;
        }
        boolean alreadyPresent = headers != null && headers.keySet().stream().anyMatch(headerName::equalsIgnoreCase);
        if (!alreadyPresent) {
            builder.header(headerName, String.valueOf(runId));
        }
    }

    private HttpExecutionResult failed(long started, FailureCategory category, String reason) {
        return new HttpExecutionResult(false, 0, category, reason, null, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private FailureCategory classifyStatus(int statusCode) {
        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return FailureCategory.RETRYABLE;
        }
        return FailureCategory.NON_RETRYABLE;
    }

    private Duration retryAfter(HttpResponse<?> response) {
        if (response.statusCode() != 429 && response.statusCode() != 503) {
            return null;
        }
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isEmpty()) {
            return null;
        }
        String value = retryAfter.get().trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds <= 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                ZonedDateTime retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration delay = Duration.between(ZonedDateTime.now(), retryAt);
                return delay.isNegative() || delay.isZero() ? null : delay;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    FailureCategory classifyException(Throwable ex) {
        if (containsCause(ex, IllegalArgumentException.class)) {
            return FailureCategory.NON_RETRYABLE;
        }
        if (containsCause(ex, HttpTimeoutException.class)
                || containsCause(ex, ConnectException.class)
                || containsCause(ex, UnknownHostException.class)
                || containsCause(ex, SocketException.class)
                || containsCause(ex, ClosedChannelException.class)) {
            return FailureCategory.RETRYABLE;
        }
        return FailureCategory.NON_RETRYABLE;
    }

    private boolean containsCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeReason(Exception ex) {
        if (ex instanceof IllegalArgumentException) {
            return ex.getMessage();
        }
        return "HTTP execution failed";
    }

    private record ValidatedPayload(String method, URI uri, Map<String, String> headers, Object body) {
    }
}
