# Executor Service

The Executor Service consumes `run`, schedules durable retries, consumes `retry`, and emits terminal failures to `dead`.

`maxRetries` means retry executions after the initial attempt. Example: `maxRetries = 3` allows 4 total HTTP attempts: initial `retryCount = 0`, then retries `1`, `2`, and `3`.

## Lifecycle

```text
QUEUED -> RUNNING -> SUCCESS
QUEUED -> RUNNING -> RETRY_SCHEDULED -> RUNNING -> SUCCESS
QUEUED -> RUNNING -> RETRY_SCHEDULED -> RUNNING -> FAILED
QUEUED -> RUNNING -> FAILED
```

`RETRY_SCHEDULED` is non-terminal. Retries use the same `job_runs.id`; no new JobRun is created per attempt. `started_at` is the latest attempt start time. `completed_at` is only terminal.

## Events

- `JOB_RUN_QUEUED`, version `1`, topic `run`, key `runId`
- `JOB_RUN_RETRY_SCHEDULED`, version `1`, topic `retry`, key `runId`, includes `retryCount`
- `JOB_RUN_DEAD`, version `1`, topic `dead`, key `runId`

Retry event uniqueness is deterministic by `runId + retryCount`. Dead event uniqueness is deterministic by `runId`, using the existing unique `outbox_events.event_id`.

## Retry policy

Retryable:

- HTTP `408`, `429`, and `5xx`
- connection/read timeout
- connect failure
- identifiable DNS failure

Non-retryable:

- malformed payload
- missing/invalid URL
- unsupported method/job type
- SSRF/allowlist rejection
- HTTP `4xx` except `429`

`429` and `503` support `Retry-After`, capped by `executor.retry.max-delay-ms`.

Backoff:

```text
baseDelay * 2^(retryCount - 1)
then +/- jitterFactor
then capped at maxDelay
```

## Durable retry timing

On retryable failure with retries remaining, Executor transactionally updates the same JobRun:

```text
status = RETRY_SCHEDULED
retry_count = retry_count + 1
next_retry_at = calculated retry time
error_message = latest safe failure
completed_at = null
```

A retry scheduler polls PostgreSQL with `FOR UPDATE SKIP LOCKED`:

```sql
status = 'RETRY_SCHEDULED'
AND next_retry_at <= now
```

It clears `next_retry_at` and creates one retry outbox event. Watcher’s existing outbox publisher publishes that event to Kafka.

On non-retryable failure or retry exhaustion, Executor transactionally marks `FAILED`, clears `next_retry_at`, and creates one dead outbox event.

## Idempotency

Retry consumer executes only when DB still says:

```text
status = RETRY_SCHEDULED
retry_count = event.retryCount
next_retry_at IS NULL
```

Duplicate/stale retry events, terminal runs, and `CANCELLED` runs are acknowledged and ignored.

Fresh `RUNNING` redeliveries are not acknowledged as terminal duplicates. If a claimed execution remains `RUNNING`
longer than `executor.execution.running-timeout-ms`, a later run/retry redelivery may atomically reclaim it by updating
`executor_id` and `started_at`. This preserves at-least-once recovery after an Executor crash, but it can repeat an
external HTTP side effect. Outbound requests continue to send `Idempotency-Key: <runId>` so cooperative targets can
deduplicate.

## Manual Kafka inspection

```powershell
docker exec -it job-scheduler-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic retry --from-beginning
docker exec -it job-scheduler-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic dead --from-beginning
```

## SQL verification

```sql
SELECT
  id,
  job_id,
  status,
  retry_count,
  next_retry_at,
  executor_id,
  started_at,
  completed_at,
  error_message
FROM job_runs
ORDER BY id DESC;
```

```sql
SELECT id, event_id, aggregate_id, event_type, topic, message_key, status, attempt_count, next_attempt_at, published_at, last_error
FROM outbox_events
WHERE topic = 'retry'
ORDER BY id DESC;
```

```sql
SELECT id, event_id, aggregate_id, event_type, topic, message_key, status, attempt_count, next_attempt_at, published_at, last_error
FROM outbox_events
WHERE topic = 'dead'
ORDER BY id DESC;
```

## Manual retry success test

Use a controllable dev endpoint that returns `500` once per `Idempotency-Key`, then `200`. If local, explicitly allowlist the host only in local env, for example:

```powershell
$env:EXECUTOR_HTTP_ALLOWED_HOSTS='host.docker.internal'
$env:EXECUTOR_RETRY_BASE_DELAY_MS='2000'
$env:EXECUTOR_RETRY_MAX_DELAY_MS='5000'
```

Create a FUTURE HTTP job with `maxRetries = 2`. After first failure expect `RETRY_SCHEDULED`, `retry_count = 1`, and `next_retry_at` populated. After retry expect `SUCCESS`, `retry_count = 1`, and `next_retry_at = null`.

## Manual retry exhaustion test

Create a FUTURE HTTP job with `maxRetries = 2` pointing to an allowlisted endpoint that always returns `500`.

Expected:

```text
initial attempt retryCount=0
retry #1 retryCount=1
retry #2 retryCount=2
terminal FAILED
one JOB_RUN_DEAD outbox event
```

## Multiple Executors

All Executor instances must use the same consumer group, for example `${EXECUTOR_CONSUMER_GROUP:executor-service}`. Local Docker Compose creates `run` and `retry` with 3 partitions, and `dead` with 1 partition.

## Limitations deferred

No Redis heartbeat, running cancellation, executor liveness registry, notification service, frontend, Eureka, API Gateway, dead-topic business consumer, manual retry API, attempt-history table, or exactly-once external execution was implemented.
