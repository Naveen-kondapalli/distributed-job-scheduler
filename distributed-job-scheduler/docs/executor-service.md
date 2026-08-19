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

Each Executor instance must use a unique `EXECUTOR_INSTANCE_ID`. This value is reused both for `job_runs.executor_id`
and for Redis heartbeat identity, so duplicate IDs are unsafe.

## Redis heartbeat

Executor publishes ephemeral liveness metadata to Redis:

```text
scheduler:executor:heartbeat:{executorId}
```

Example:

```text
scheduler:executor:heartbeat:executor-1
```

The value is JSON so it can be inspected with `redis-cli`. It contains small operational metadata:

- `executorId`
- `serviceName`
- `startedAt`
- `lastHeartbeatAt`
- `hostname`
- `applicationVersion`
- `instanceToken`

The heartbeat does not contain credentials, JWTs, Kafka data, job payloads, authorization headers, or running job
details.

Default configuration:

```yaml
executor:
  heartbeat:
    enabled: true
    interval-ms: 10000
    ttl-seconds: 30
```

Environment variables:

```powershell
$env:EXECUTOR_HEARTBEAT_ENABLED='true'
$env:EXECUTOR_HEARTBEAT_INTERVAL_MS='10000'
$env:EXECUTOR_HEARTBEAT_TTL_SECONDS='30'
$env:REDIS_HOST='localhost'
$env:REDIS_PORT='6379'
```

Every write refreshes the value and TTL atomically. A heartbeat key should never normally return Redis TTL `-1`
because that means the key exists without expiration.

Heartbeat writes are independent of Kafka listener threads and job execution. Redis is not used as execution
durability; PostgreSQL remains the source of truth for `JobRun` state, retry state, and terminal outcomes. Kafka
consumer group membership is also separate from Redis heartbeat state.

### Duplicate Executor ID behavior

Executor heartbeat uses a per-process `instanceToken`. Registration/refresh is guarded by Redis atomic Lua scripts:

- if no heartbeat exists, the process claims `scheduler:executor:heartbeat:{executorId}` with TTL
- if the heartbeat exists and has the same `instanceToken`, the process refreshes it
- if the heartbeat exists with a different `instanceToken`, startup/refresh fails with a strong duplicate-ID error
- graceful shutdown deletes the heartbeat only when the token still matches

If Redis is unavailable during startup, the Executor logs the heartbeat failure and continues so job execution is not
coupled to Redis liveness metadata. When Redis recovers, the scheduled heartbeat attempts registration again.

### Failure semantics

```text
key exists  -> Executor has reported recently
key absent  -> Executor unavailable OR Redis unavailable/unhealthy
```

Do not treat a missing heartbeat as absolute proof of process death unless Redis itself is known to be healthy.

On abrupt crash, no shutdown cleanup runs. The key remains until Redis TTL expires, then Redis removes it. On graceful
shutdown, Executor attempts to remove only its owned heartbeat key immediately. If Redis is unavailable during shutdown,
Executor logs and continues shutdown.

Actuator `health` and `info` remain separate from Redis heartbeat. Actuator reports application/dependency health;
heartbeat lets other components observe that a specific Executor identity has reported recently. Heartbeat is not a
replacement for Kubernetes liveness/readiness probes.

### Manual Redis inspection

Development key listing:

```powershell
docker exec -it job-scheduler-redis redis-cli KEYS "scheduler:executor:heartbeat:*"
```

Inspect one Executor:

```powershell
docker exec -it job-scheduler-redis redis-cli GET scheduler:executor:heartbeat:executor-1
docker exec -it job-scheduler-redis redis-cli TTL scheduler:executor:heartbeat:executor-1
```

Redis TTL return values:

- positive value: seconds remaining
- `-1`: key exists with no expiration; heartbeat keys should not normally do this
- `-2`: key does not exist

### Manual heartbeat tests

Start infrastructure:

```powershell
docker compose up -d
```

Single Executor:

```powershell
$env:EXECUTOR_INSTANCE_ID='executor-1'
$env:SERVER_PORT='8082'
.\mvnw.cmd spring-boot:run
```

Then inspect:

```powershell
docker exec -it job-scheduler-redis redis-cli GET scheduler:executor:heartbeat:executor-1
docker exec -it job-scheduler-redis redis-cli TTL scheduler:executor:heartbeat:executor-1
```

TTL refresh should look roughly like:

```text
TTL key -> 27
wait
TTL key -> 20
heartbeat occurs
TTL key -> 29
```

Two Executors:

```powershell
# terminal 1
$env:SERVER_PORT='8082'
$env:EXECUTOR_INSTANCE_ID='executor-1'
.\mvnw.cmd spring-boot:run

# terminal 2
$env:SERVER_PORT='8083'
$env:EXECUTOR_INSTANCE_ID='executor-2'
.\mvnw.cmd spring-boot:run
```

Redis should show both keys:

```powershell
docker exec -it job-scheduler-redis redis-cli KEYS "scheduler:executor:heartbeat:*"
docker exec -it job-scheduler-redis redis-cli TTL scheduler:executor:heartbeat:executor-1
docker exec -it job-scheduler-redis redis-cli TTL scheduler:executor:heartbeat:executor-2
```

Duplicate ID:

```powershell
# while executor-1 is already running
$env:SERVER_PORT='8083'
$env:EXECUTOR_INSTANCE_ID='executor-1'
.\mvnw.cmd spring-boot:run
```

The second process must not silently take over the active heartbeat.

Crash TTL expiry:

```powershell
docker exec -it job-scheduler-redis redis-cli TTL scheduler:executor:heartbeat:executor-1
```

Terminate the Executor abruptly. Keep checking TTL until Redis returns `-2`.

Graceful shutdown:

Start the Executor again, verify the key exists, then stop normally from IntelliJ or Ctrl+C. The owned key should be
removed immediately when shutdown hooks run.

Redis outage/recovery:

```powershell
docker stop job-scheduler-redis
# observe Executor heartbeat warning logs; process should remain running
docker start job-scheduler-redis
# heartbeat should re-register/recover automatically
docker exec -it job-scheduler-redis redis-cli KEYS "scheduler:executor:heartbeat:*"
```

## Limitations deferred

Running cancellation, Redis cancellation keys, Redis Pub/Sub, heartbeat-based JobRun stealing/recovery, new Job Service
Executor APIs, executor dashboard, notification service, frontend, Eureka, API Gateway, dead-topic business consumer,
manual retry API, attempt-history table, and exactly-once external execution are still deferred.
