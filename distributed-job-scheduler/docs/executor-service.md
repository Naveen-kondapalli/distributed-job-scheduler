# Executor Service

The Executor Service consumes `JOB_RUN_QUEUED` events from Kafka topic `run` and executes already-created `JobRun` rows.

## Configuration

- Application name: `executor-service`
- Port: `${SERVER_PORT:8082}`
- Database: same `job_scheduler` PostgreSQL database as Job Service and Watcher
- JPA schema mode: `validate`; the Executor does not own schema creation
- Timezone: `Asia/Kolkata` through Hibernate JDBC timezone and Maven JVM args
- Kafka bootstrap: `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- Consumer group: `${EXECUTOR_CONSUMER_GROUP:executor-service}`
- Listener concurrency: `${EXECUTOR_KAFKA_CONCURRENCY:3}`

If running from IntelliJ, set VM options for `ExecutorServiceApplication`:

```text
-Duser.timezone=Asia/Kolkata
```

## Event contract

The Executor deserializes the existing Watcher event payload:

```json
{
  "eventId": "uuid",
  "eventVersion": 1,
  "runId": 50,
  "jobId": 10,
  "scheduledAt": "2026-08-12T15:45:00",
  "eventType": "JOB_RUN_QUEUED",
  "occurredAt": "2026-08-12T15:45:30"
}
```

Kafka key is the run id as a string. Watcher also publishes headers `eventId`, `eventType`, and `eventVersion`.

Unsupported event versions or event types are logged and skipped in this first phase to avoid poison-message loops.

## Execution lifecycle

Implemented phase:

```text
QUEUED -> RUNNING -> SUCCESS
QUEUED -> RUNNING -> FAILED
```

The Executor does not update `jobs.status`. A CRON job can remain `ACTIVE` while individual `job_runs` become `SUCCESS` or `FAILED`.

Already queued runs are executed even if the parent Job is later cancelled; cancellation of queued/running runs is intentionally deferred.

## Idempotency and ownership

`runId` is the execution identity.

Before execution, the Executor validates that the event `runId` exists and that `event.jobId` matches the loaded `job_runs.job_id`.

Claiming is atomic:

```sql
UPDATE job_runs
SET status = 'RUNNING',
    executor_id = ?,
    started_at = ?
WHERE id = ?
  AND status = 'QUEUED';
```

Only the instance that wins this update executes HTTP. Terminal completion checks the same `executor_id`:

```sql
WHERE id = ?
  AND status = 'RUNNING'
  AND executor_id = ?
```

Duplicate events for `SUCCESS`, `FAILED`, `CANCELLED`, or `RUNNING` runs are acknowledged and not executed again. Stale `RUNNING` recovery, heartbeats, retries, and DLQ are future phases.

## Kafka acknowledgement and offset commits

Auto-commit is disabled. The listener uses manual immediate acknowledgement.

Messages are acknowledged after:

- the run reaches `SUCCESS` or `FAILED`;
- the run is already terminal or already `RUNNING`;
- the event is malformed, unsupported, or inconsistent and cannot safely be processed.

If a database/infrastructure failure happens before durable handling, the listener does not acknowledge so Kafka can redeliver.

There is no DLQ in this phase; malformed records are logged with metadata only and skipped.

## HTTP semantics

Only `JobType.HTTP` is implemented.

Payload contract:

```json
{
  "method": "POST",
  "url": "https://jsonplaceholder.typicode.com/posts",
  "headers": {
    "Content-Type": "application/json"
  },
  "body": {
    "example": true
  }
}
```

Supported methods: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`.

Success is any 2xx response. Non-2xx responses become `FAILED`. The Executor stores only a bounded safe `error_message`, not full payloads, secrets, stack traces, or large response bodies.

HTTP timeouts:

- connect timeout: `${EXECUTOR_HTTP_CONNECT_TIMEOUT_MS:3000}`
- request/read timeout: `${EXECUTOR_HTTP_READ_TIMEOUT_MS:10000}`

Outbound requests include `Idempotency-Key: <runId>` unless the job payload already supplies that header. The header name is configurable with `EXECUTOR_HTTP_IDEMPOTENCY_KEY_HEADER`.

## SSRF policy

Targets must be explicitly allowlisted:

```yaml
executor:
  http:
    allowed-hosts:
      - jsonplaceholder.typicode.com
```

Loopback, link-local, private/site-local, multicast, and cloud metadata addresses are blocked after DNS resolution. This is a first-phase allowlist architecture, not a full enterprise egress proxy.

## Exactly-once limitation

The system provides at-least-once execution with run-level idempotency support, not exactly-once external side effects.

Crash window:

```text
Executor sends HTTP request
target processes request
Executor crashes before SUCCESS is committed
Kafka redelivers
HTTP request may be sent again
```

Downstream targets should use the `Idempotency-Key` header to deduplicate.

## Running multiple Executors locally

Use the same consumer group for every instance:

```powershell
$env:SERVER_PORT='8082'
$env:EXECUTOR_INSTANCE_ID='executor-1'
$env:EXECUTOR_CONSUMER_GROUP='executor-service'
.\mvnw.cmd spring-boot:run
```

Second terminal:

```powershell
$env:SERVER_PORT='8083'
$env:EXECUTOR_INSTANCE_ID='executor-2'
$env:EXECUTOR_CONSUMER_GROUP='executor-service'
.\mvnw.cmd spring-boot:run
```

Kafka distributes work only up to the number of partitions in topic `run`. Local Docker Compose creates `run` with 3 partitions for development.

## Manual end-to-end test

1. Start infrastructure:

   ```powershell
   docker compose up -d
   ```

2. Start Job Service on 8080, Watcher on 8081, and Executor on 8082.

3. Register and login:

   ```powershell
   Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/register -ContentType application/json -Body '{"username":"john","email":"john@example.com","password":"password123"}'
   $login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login -ContentType application/json -Body '{"email":"john@example.com","password":"password123"}'
   $token = $login.accessToken
   ```

4. Create a future HTTP job. Use a `scheduledTime` a minute or two in the future in `Asia/Kolkata` local time:

   ```powershell
   $headers = @{ Authorization = "Bearer $token" }
   $body = @{
     name = "Executor success test"
     description = "Calls jsonplaceholder"
     jobType = "HTTP"
     scheduleType = "FUTURE"
     scheduledTime = "2026-08-17T12:10:00"
     maxRetries = 3
     payload = @{
       method = "GET"
       url = "https://jsonplaceholder.typicode.com/posts/1"
     }
   } | ConvertTo-Json -Depth 10
   Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/jobs -Headers $headers -ContentType application/json -Body $body
   ```

5. Wait for Watcher to create a `JobRun`, publish outbox to Kafka, and Executor to consume it.

6. Verify:

   ```sql
   SELECT
     id,
     job_id,
     status,
     executor_id,
     scheduled_at,
     started_at,
     completed_at,
     retry_count,
     error_message
   FROM job_runs
   ORDER BY id DESC;
   ```

   Expected successful run status: `SUCCESS`.

For a safe non-2xx failure test, allowlist `jsonplaceholder.typicode.com` and create a job with:

```json
{
  "method": "GET",
  "url": "https://jsonplaceholder.typicode.com/invalid-path-for-404"
}
```

Expected run status: `FAILED`, with a safe `HTTP 404 returned by target` error.

## Deferred intentionally

This phase does not implement retry topics, DLQ/dead topics, Redis, execution retries, cancellation of running executions, heartbeat, executor registry, notification service, WebSockets, frontend, SCRIPT jobs, EMAIL jobs, WEBHOOK-specific execution, or automatic retry after `FAILED`.
