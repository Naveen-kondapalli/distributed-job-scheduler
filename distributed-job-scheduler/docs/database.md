# Database Design

## Overview

The Distributed Job Scheduler uses PostgreSQL as its primary database.

The system consists of three core entities:

- Users
- Jobs
- Job Runs
- Outbox Events

Relationship:

```
Users (1) --------< Jobs (N)

Jobs (1) --------< Job_Runs (N)

Outbox_Events store durable messages to publish after database commit.
```

---

# Common Columns

All tables inherit the following columns from a common `BaseEntity`.

| Column | Type | Description |
|---------|------|-------------|
| id | BIGSERIAL | Primary Key (Auto Generated) |
| created_at | TIMESTAMP | Record creation timestamp |
| updated_at | TIMESTAMP | Last updated timestamp |

---

# Users Table

Stores application users.

| Column | Type | Constraints | Description |
|---------|------|------------|-------------|
| id | BIGSERIAL | PK | User Identifier |
| username | VARCHAR(100) | UNIQUE, NOT NULL | Username |
| email | VARCHAR(255) | UNIQUE, NOT NULL | Email Address |
| password | VARCHAR(255) | NOT NULL | Hashed Password |
| created_at | TIMESTAMP | NOT NULL | Created Time |
| updated_at | TIMESTAMP | NOT NULL | Updated Time |

---

# Jobs Table

Stores the job definition.

A job record represents **what should be executed**, not **how many times it has executed**.

| Column | Type | Constraints | Description |
|---------|------|------------|-------------|
| id | BIGSERIAL | PK | Job Identifier |
| user_id | BIGINT | FK | Owner of the Job |
| name | VARCHAR(255) | NOT NULL | Job Name |
| description | TEXT | NULL | Optional Description |
| job_type | ENUM | NOT NULL | Type of Job |
| schedule_type | ENUM | NOT NULL | Scheduling Strategy |
| scheduled_time | TIMESTAMP | NULL | Future Execution Time |
| next_run_at | TIMESTAMP | NULL | Next scheduled occurrence to claim |
| cron_expression | VARCHAR(100) | NULL | Cron Expression |
| payload | JSONB | NOT NULL | Execution Payload |
| status | ENUM | NOT NULL | Job Status |
| max_retries | INTEGER | DEFAULT 3 | Maximum Retry Attempts |
| created_at | TIMESTAMP | NOT NULL | Created Time |
| updated_at | TIMESTAMP | NOT NULL | Updated Time |

---

# Job_Runs Table

Stores every execution of a job.

Each execution generates a new record.

| Column | Type | Constraints | Description |
|---------|------|------------|-------------|
| id | BIGSERIAL | PK | Run Identifier |
| job_id | BIGINT | FK | Parent Job |
| executor_id | VARCHAR(100) | NULL | Executor Instance |
| status | ENUM | NOT NULL | Execution Status |
| scheduled_at | TIMESTAMP | NOT NULL | Scheduled occurrence this run represents |
| retry_count | INTEGER | DEFAULT 0 | Current Retry Attempt |
| started_at | TIMESTAMP | NULL | Execution Start Time |
| completed_at | TIMESTAMP | NULL | Execution Completion Time |
| next_retry_at | TIMESTAMP | NULL | Durable time when the next execution retry becomes eligible |
| error_message | TEXT | NULL | Failure Reason |
| created_at | TIMESTAMP | NOT NULL | Created Time |
| updated_at | TIMESTAMP | NOT NULL | Updated Time |

---

# Outbox_Events Table

Stores durable integration events that must be published to Kafka.

The outbox prevents unsafe direct dual writes where a database commit succeeds but Kafka publishing fails. The scheduler writes the `JobRun`, updates `jobs.next_run_at`, and inserts the `OutboxEvent` in one PostgreSQL transaction. A separate publisher later publishes the durable payload to Kafka and marks the row `PUBLISHED` only after Kafka acknowledgement.

| Column | Type | Constraints | Description |
|---------|------|------------|-------------|
| id | BIGSERIAL | PK | Row Identifier |
| event_id | VARCHAR(36) | UNIQUE, NOT NULL | Stable UUID message identity |
| aggregate_type | VARCHAR(100) | NOT NULL | Aggregate type, e.g. `JOB_RUN` |
| aggregate_id | BIGINT | NOT NULL | Aggregate id, e.g. JobRun id |
| event_type | VARCHAR(100) | NOT NULL | Domain event type, e.g. `JOB_RUN_QUEUED` |
| topic | VARCHAR(100) | NOT NULL | Kafka topic, e.g. `run` |
| message_key | VARCHAR(100) | NOT NULL | Kafka key, e.g. run id |
| payload | JSONB | NOT NULL | Exact JSON payload to publish |
| status | ENUM | NOT NULL | Outbox transport status |
| attempt_count | INTEGER | DEFAULT 0 | Publish attempts made |
| next_attempt_at | TIMESTAMP | NULL | Earliest retry time |
| processing_started_at | TIMESTAMP | NULL | Processing lease timestamp |
| published_at | TIMESTAMP | NULL | Kafka acknowledgement time |
| last_error | TEXT | NULL | Sanitized last publish error |
| created_at | TIMESTAMP | NOT NULL | Created Time |
| updated_at | TIMESTAMP | NOT NULL | Updated Time |

---

# Enums

## JobStatus

Represents the lifecycle of a Job.

```
ACTIVE
PAUSED
CANCELLED
```

---

## ScheduleType

Defines how a job should be scheduled.

```
IMMEDIATE
FUTURE
CRON
```

---

## JobRunStatus

Represents the execution lifecycle.

```
QUEUED
RUNNING
RETRY_SCHEDULED
CANCEL_REQUESTED
SUCCESS
FAILED
CANCELLED
```

---

## OutboxStatus

Represents Kafka transport state for a durable outbox event.

```
PENDING
PROCESSING
PUBLISHED
FAILED
```

---

## JobType

Initial supported job type:

```
HTTP
```

Future versions may support:

```
EMAIL
SCRIPT
WEBHOOK
```

---

# Payload Structure

Execution details are stored in PostgreSQL using **JSONB**.

Example:

```json
{
  "type": "HTTP",
  "method": "POST",
  "url": "https://api.example.com/orders",
  "headers": {
    "Authorization": "Bearer token"
  },
  "body": {
    "orderId": 101
  }
}
```

Using JSONB allows introducing new job types without modifying the database schema.

---

# Indexes

## Users

- UNIQUE(email)
- UNIQUE(username)

---

## Jobs

- INDEX(user_id)
- COMPOSITE INDEX(status, next_run_at)
- INDEX(schedule_type)

---

## Job_Runs

- INDEX(job_id)
- INDEX(status)
- INDEX(executor_id)

---

## Outbox_Events

- UNIQUE(event_id)
- COMPOSITE INDEX(status, next_attempt_at)
- COMPOSITE INDEX(status, created_at)

---

# Constraints

## Users

- Email must be unique.
- Username must be unique.

---

## Jobs

- Every Job belongs to exactly one User.
- Payload cannot be NULL.
- If `schedule_type = FUTURE`, `scheduled_time` must be provided.
- If `schedule_type = CRON`, `cron_expression` must be provided.
- `next_run_at` is system-managed scheduler state and is not supplied by clients.

---

## Job_Runs

- Every JobRun belongs to exactly one Job.

---

## Outbox_Events

- `event_id` is a stable UUID and is reused for every retry.
- `PUBLISHED` is terminal.
- `FAILED` means publishing exhausted configured attempts and requires operational intervention.

---

# Base Entity

Every entity inherits from a common `BaseEntity`.

Inherited fields:

- id
- created_at
- updated_at

This avoids duplication across all entities.

---

# Design Decisions

- PostgreSQL is the primary persistent datastore.
- BIGSERIAL is used for all primary keys.
- Job definitions and execution history are stored separately.
- JSONB is used to support extensible job payloads.
- Job status and Job execution status are intentionally separated.
- Job status (`ACTIVE`, `PAUSED`, `CANCELLED`) describes whether the schedule definition is enabled.
- `CANCELLED` is a terminal Job lifecycle state. Cancelling a Job sets `next_run_at` to `NULL` and prevents future scheduling while preserving the Job row and existing JobRun history.
- JobRun status (`QUEUED`, `RUNNING`, `RETRY_SCHEDULED`, `CANCEL_REQUESTED`, `SUCCESS`, `FAILED`, `CANCELLED`) describes one execution occurrence.
- `RETRY_SCHEDULED` is non-terminal and means the same JobRun is waiting for a future retry attempt.
- `CANCEL_REQUESTED` is non-terminal durable cancellation intent for a running execution. It must not be retried or re-executed; the owning Executor or stale-cancellation recovery moves it to `CANCELLED`.
- `CANCELLED` is terminal for one JobRun and does not imply the parent Job schedule is cancelled.
- `max_retries` means retry executions after the initial attempt. `max_retries = 3` allows up to 4 total HTTP attempts: initial attempt plus retries 1, 2, and 3.
- `retry_count` is the retry attempt number on the same JobRun. Initial execution uses `retry_count = 0`; retry #1 uses `retry_count = 1`.
- `next_retry_at` is job execution retry timing and is separate from Outbox `next_attempt_at`, which is only Kafka publication retry timing.
- Every execution creates a new JobRun record, preserving complete execution history.
- Multiple Executor instances can process jobs concurrently.
- The Watcher service efficiently finds due jobs using a composite index on `(status, next_run_at)`.
- For `FUTURE` jobs, `next_run_at` is initialized from `scheduled_time` and is set to `NULL` after that occurrence is queued.
- For `CRON` jobs, `next_run_at` is initialized to the next cron occurrence after the current application time. When an occurrence is queued, the next occurrence is calculated from the claimed occurrence, not from the delayed polling time.
- When a CRON job is paused, missed occurrences are not replayed on resume; `next_run_at` is recalculated to the next occurrence after resume time.
- Kafka delivery uses the Transactional Outbox pattern.
- Outbox delivery is at-least-once. If Kafka acknowledges a message and the Watcher crashes before marking the row `PUBLISHED`, the recovered event may be published again.
- Future Kafka consumers must process idempotently using `runId` and/or `eventId`.
- Published outbox events are retained temporarily for observability and cleaned up after the configured retention window.
- Executor Service consumes the `run` topic in a shared consumer group and idempotently claims `job_runs` with `runId` using an atomic `QUEUED -> RUNNING` update before HTTP execution.
- Executor Service schedules retryable execution failures by moving the same JobRun to `RETRY_SCHEDULED` with `next_retry_at`; a retry scheduler later creates a durable `JOB_RUN_RETRY_SCHEDULED` outbox event for topic `retry`.
- Terminal failures create a durable `JOB_RUN_DEAD` outbox event for topic `dead`.
