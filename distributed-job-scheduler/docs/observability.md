# Production-grade observability

Applications, including the API Gateway, expose `/actuator/prometheus`; Prometheus scrapes those endpoints; Grafana queries Prometheus. Logs remain application console output with correlation identifiers.

```text
Applications
   │ /actuator/prometheus
   ▼
Prometheus
   ▼
Grafana
```

All services expose only `health`, `info`, and `prometheus`, with Spring Boot liveness/readiness probes enabled where supported.

Local URLs:

- Job Service: `http://localhost:8080/actuator/prometheus`
- Watcher Service: `http://localhost:8081/actuator/prometheus`
- Executor Service: `http://localhost:8082/actuator/prometheus`
- API Gateway: `http://localhost:8085/actuator/prometheus`

Custom metrics use bounded tags only: `schedule_type`, `resulting_status`, `topic`, `result`, `status`, `job_type`, `outcome`, `failure_category`, and `route`. Metrics do not use `jobId`, `runId`, `eventId`, `executorId`, URL, user ID, email, cron expression, payload, error message, correlation ID, or exception message as tags.

Prometheus:

- Image: `prom/prometheus:v2.54.1`
- Port: `9090`
- Config: `observability/prometheus/prometheus.yml`
- Scrape interval: `15s`
- Targets: `host.docker.internal:8085`, `:8080`, `:8081`, `:8082`

Grafana:

- Image: `grafana/grafana:11.2.0`
- Port: `3000`
- Datasource: provisioned Prometheus at `http://prometheus:9090`
- Dashboard: `Distributed Job Scheduler Overview`
- Local credentials: `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`, defaulting to `admin` / `admin` for development only

Key metrics:

| Service | Metric | Type | Tags |
| --- | --- | --- | --- |
| job-service | `scheduler.jobs.created` | counter | `schedule_type` |
| job-service | `scheduler.jobs.updated` | counter | `schedule_type` |
| job-service | `scheduler.jobs.paused` | counter | none |
| job-service | `scheduler.jobs.resumed` | counter | none |
| job-service | `scheduler.jobs.cancelled` | counter | none |
| job-service | `scheduler.job_runs.cancel.requested` | counter | `resulting_status` |
| watcher-service | `scheduler.watcher.jobs.claimed` | counter | `schedule_type` |
| watcher-service | `scheduler.watcher.poll.duration` | timer | none |
| watcher-service | `scheduler.outbox.publish` | counter | `topic`, `result` |
| watcher-service | `scheduler.outbox.publish.duration` | timer | `topic` |
| watcher-service | `scheduler.outbox.events` | gauge | `status` |
| executor-service | `scheduler.executor.execution` | counter | `job_type`, `result` |
| executor-service | `scheduler.executor.execution.duration` | timer | `job_type`, `result` |
| executor-service | `scheduler.executor.http.requests` | counter | `outcome` |
| executor-service | `scheduler.executor.retry.scheduled` | counter | `failure_category` |
| executor-service | `scheduler.executor.retry.exhausted` | counter | `failure_category` |
| executor-service | `scheduler.executor.retry.success` | counter | none |
| executor-service | `scheduler.executor.dead.created` | counter | `failure_category` |
| executor-service | `scheduler.executor.cancellation.completed` | counter | none |
| executor-service | `scheduler.executor.cancellation.signal.failure` | counter | none |
| executor-service | `scheduler.executor.heartbeat` | counter | `result` |
| executor-service | `scheduler.executor.kafka.duplicate` | counter | `topic` |
| api-gateway | `scheduler.gateway.requests` | counter | `route`, `result` |
| api-gateway | `scheduler.gateway.request.duration` | timer | `route`, `result` |
| api-gateway | `scheduler.gateway.rate_limited` | counter | `route` |

MDC fields in the console pattern are `correlationId`, `jobId`, `runId`, `eventId`, `executorId`, and `retryCount`. MDC is applied and cleared around API Gateway requests, Job Service HTTP requests, watcher claims, watcher outbox publication, and executor Kafka consumers.

Start local monitoring:

```powershell
docker compose up -d
docker compose ps
```

Windows-friendly checks:

```powershell
docker compose config
Invoke-WebRequest http://localhost:9090/-/healthy
Invoke-WebRequest http://localhost:3000/api/health
Invoke-WebRequest http://localhost:8080/actuator/prometheus | Select-Object -ExpandProperty Content
Invoke-WebRequest http://localhost:8081/actuator/prometheus | Select-Object -ExpandProperty Content
Invoke-WebRequest http://localhost:8082/actuator/prometheus | Select-Object -ExpandProperty Content
Invoke-WebRequest http://localhost:8085/actuator/prometheus | Select-Object -ExpandProperty Content
```

Useful PromQL:

```promql
up{job=~"api-gateway|job-service|watcher-service|executor-service"}
sum by (route) (rate(scheduler_gateway_requests_total[5m]))
sum by (route) (rate(scheduler_gateway_rate_limited_total[5m]))
sum by (schedule_type) (rate(scheduler_jobs_created_total[5m]))
sum by (schedule_type) (rate(scheduler_watcher_jobs_claimed_total[5m]))
sum by (status) (scheduler_outbox_events{status=~"PENDING|PROCESSING|FAILED"})
sum by (topic, result) (rate(scheduler_outbox_publish_total[5m]))
sum by (result) (rate(scheduler_executor_execution_total[5m]))
sum by (result) (rate(scheduler_executor_execution_duration_seconds_sum[5m])) / sum by (result) (rate(scheduler_executor_execution_duration_seconds_count[5m]))
sum by (failure_category) (rate(scheduler_executor_retry_scheduled_total[5m]))
sum by (failure_category) (rate(scheduler_executor_retry_exhausted_total[5m]))
sum(rate(scheduler_executor_heartbeat_total{result="failure"}[5m]))
```

Manual workload:

1. Create a successful `FUTURE` HTTP job; job creation, watcher claim, outbox publish, and executor success metrics should move.
2. Create a `CRON` HTTP job; `schedule_type="CRON"` should appear.
3. Trigger a controlled retryable failure if a safe test endpoint is available; retry scheduled/exhausted and dead-created metrics should move.
4. Cancel a queued/running JobRun if practical; cancellation request/completion metrics should move.

Troubleshooting DOWN targets:

- Confirm each Spring Boot service is running on the expected host port.
- Confirm `/actuator/prometheus` works from the host.
- Prometheus runs in Docker, so it uses `host.docker.internal` rather than `localhost`.
- Docker Desktop/Rancher Desktop on Windows and Docker Desktop on macOS support `host.docker.internal`; Linux environments may need an equivalent host-gateway mapping if the hostname is not available.

Production security: `/actuator/prometheus` can reveal operational data and should be restricted to internal monitoring networks or authenticated paths. Metrics and logs added in this phase do not include secrets, payloads, authorization headers, JWTs, Redis ownership tokens, or database/Kafka credentials.

Prometheus and Grafana are monitoring systems only. Their outage has zero effect on scheduler durability or execution. OpenTelemetry, distributed tracing, Loki/ELK, Alertmanager, Eureka, Kubernetes, frontend, and business notifications are intentionally deferred.
