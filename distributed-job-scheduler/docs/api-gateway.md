# API Gateway External Boundary

The API Gateway is the client-facing HTTP boundary for the distributed job scheduler.

```text
Client
  |
  | HTTP :8085
  v
API Gateway
  |
  | configured upstream: JOB_SERVICE_URL
  v
Job Service :8080
```

Watcher, Executor, PostgreSQL, Redis, Kafka, Prometheus, and Grafana are not exposed through the Gateway.

## Local URLs

- Gateway: `http://localhost:8085`
- Job Service upstream default: `http://localhost:8080`
- Actuator health: `http://localhost:8085/actuator/health`
- Prometheus scrape: `http://localhost:8085/actuator/prometheus`

## Routed endpoints

The Gateway preserves the existing Job Service paths:

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `/api/v1/jobs/**`

JobRun cancellation remains:

```text
PATCH /api/v1/jobs/{jobId}/runs/{runId}/cancel
```

Unknown paths and internal-service paths are not proxied.

## Security model

Authentication endpoints are public because clients need them to register and log in.

Protected job APIs require:

```text
Authorization: Bearer <JWT>
```

The Gateway validates JWT signature, expiration, malformed tokens, and Bearer format using the same `JWT_SECRET` semantics as Job Service. The Authorization header is forwarded so Job Service validates the JWT again and continues to enforce business ownership. The Gateway does not become the only authorization boundary.

Client-supplied identity headers are removed before proxying:

- `X-User-Id`
- `X-User-Email`
- `X-Internal-User`

The Gateway does not log JWTs, passwords, request bodies, job payloads, or Authorization headers.

## Redis-backed rate limiting

Rate limiting is Redis-backed and safe for multiple Gateway instances. It is not JVM-local.

Development defaults:

- Auth endpoints: `5` requests/second, burst `10`
- Authenticated job APIs: `20` requests/second, burst `40`

Configuration:

```yaml
gateway:
  rate-limit:
    auth:
      replenish-rate: ${GATEWAY_AUTH_REPLENISH_RATE:5}
      burst-capacity: ${GATEWAY_AUTH_BURST_CAPACITY:10}
    authenticated:
      replenish-rate: ${GATEWAY_AUTHENTICATED_REPLENISH_RATE:20}
      burst-capacity: ${GATEWAY_AUTHENTICATED_BURST_CAPACITY:40}
```

Authenticated traffic is keyed by JWT subject. Auth endpoints are keyed by remote address for local development. The Gateway does not trust arbitrary `X-Forwarded-For` headers from external clients.

If Redis cannot determine limiter state, the Gateway fails closed for rate-limited routes with `503 RATE_LIMIT_UNAVAILABLE`. This does not mutate Job Service state.

Rate-limit excess returns `429 Too Many Requests` with `Retry-After: 1`.

## Request size, timeout, and retry policy

Default request body protection is `1 MB`:

```yaml
gateway:
  max-request-size: ${GATEWAY_MAX_REQUEST_SIZE:1MB}
```

Oversized requests return `413 Payload Too Large` before proxying.

Gateway → Job Service calls use bounded timeouts:

- Connect timeout default: `3000ms`
- Response timeout default: `10s`

The Gateway does not automatically retry writes such as registration or job creation.

## Correlation ID

The Gateway uses:

```text
X-Correlation-Id
```

If absent, the Gateway generates a UUID. If present, the value must be short and contain only safe header/log characters; otherwise it is replaced. The ID is:

- added to Gateway log context
- forwarded to Job Service
- returned in the response header

Job Service accepts the same header into MDC and clears it after the request.

## Actuator and metrics

Exposed Gateway endpoints:

- `/actuator/health`
- `/actuator/info`
- `/actuator/prometheus`

Custom bounded metrics:

- `scheduler.gateway.requests`
  - tags: `route=auth|jobs`, `result=success|unauthorized|rate_limited|upstream_error|rate_limit_unavailable|payload_too_large`
- `scheduler.gateway.request.duration`
  - tags: `route`, `result`
- `scheduler.gateway.rate_limited`
  - tags: `route`

No user IDs, JWTs, IPs, job IDs, run IDs, correlation IDs, or full URI paths are used as metric labels.

## Production notes

Local development still leaves Job Service reachable on `:8080`; external clients should use Gateway `:8085`. In production, networking should prevent direct public access to Job Service and keep Watcher/Executor private.

The Gateway currently uses a configured Job Service location rather than service discovery:

```yaml
gateway:
  services:
    job-service-url: ${JOB_SERVICE_URL:http://localhost:8080}
```

Eureka is intentionally not required yet. Rate limits are development defaults and require capacity-specific tuning for production.
