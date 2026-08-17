package com.executorservice.http;

import com.executorservice.config.ExecutorProperties;
import com.executorservice.dto.HttpJobPayload;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
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
                return new HttpExecutionResult(true, statusCode, null, durationMs);
            }
            return new HttpExecutionResult(false, statusCode, "HTTP " + statusCode + " returned by target", durationMs);
        } catch (java.net.http.HttpTimeoutException ex) {
            return failed(started, "Connection timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed(started, "Execution interrupted");
        } catch (Exception ex) {
            return failed(started, safeReason(ex));
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

    private HttpExecutionResult failed(long started, String reason) {
        return new HttpExecutionResult(false, 0, reason, Duration.ofNanos(System.nanoTime() - started).toMillis());
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
