# Database Design

## Overview

The Distributed Job Scheduler uses PostgreSQL as its primary database.

The system consists of three core entities:

- Users
- Jobs
- Job Runs

Relationship:

```
Users (1) --------< Jobs (N)

Jobs (1) --------< Job_Runs (N)
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
| retry_count | INTEGER | DEFAULT 0 | Current Retry Attempt |
| started_at | TIMESTAMP | NULL | Execution Start Time |
| completed_at | TIMESTAMP | NULL | Execution Completion Time |
| error_message | TEXT | NULL | Failure Reason |
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
SUCCESS
FAILED
CANCELLED
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
- COMPOSITE INDEX(status, scheduled_time)
- INDEX(schedule_type)

---

## Job_Runs

- INDEX(job_id)
- INDEX(status)
- INDEX(executor_id)

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

---

## Job_Runs

- Every JobRun belongs to exactly one Job.

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
- Every execution creates a new JobRun record, preserving complete execution history.
- Multiple Executor instances can process jobs concurrently.
- The Watcher service efficiently finds due jobs using a composite index on `(status, scheduled_time)`.