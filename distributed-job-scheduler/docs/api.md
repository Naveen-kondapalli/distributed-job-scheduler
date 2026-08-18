# API Design

## Base URL

```
/api/v1
```

---

# Authentication

All APIs (except Register and Login) require JWT authentication.

Request Header:

```
Authorization: Bearer <JWT_TOKEN>
```

---

# Authentication APIs

## Register User

**POST**

```
/auth/register
```

### Request

```json
{
  "username": "john",
  "email": "john@example.com",
  "password": "password123"
}
```

### Response

```json
{
  "message": "User registered successfully"
}
```

---

## Login

**POST**

```
/auth/login
```

### Request

```json
{
  "email": "john@example.com",
  "password": "password123"
}
```

### Response

```json
{
  "accessToken": "<JWT_TOKEN>"
}
```

---

# Job APIs

## Create Job

**POST**

```
/jobs
```

### Request

```json
{
  "name": "Daily Sales Report",
  "description": "Runs every morning",
  "jobType": "HTTP",
  "scheduleType": "CRON",
  "cronExpression": "0 0 10 * * *",
  "maxRetries": 3,
  "payload": {
    "method": "POST",
    "url": "https://api.example.com/report",
    "headers": {
      "Authorization": "Bearer token"
    },
    "body": {
      "reportType": "sales"
    }
  }
}
```

### Response

```json
{
  "id": 101,
  "name": "Daily Sales Report",
  "description": "Runs every morning",
  "jobType": "HTTP",
  "scheduleType": "CRON",
  "scheduledTime": null,
  "nextRunAt": "2026-08-15T10:00:00",
  "cronExpression": "0 0 10 * * *",
  "payload": {
    "method": "POST",
    "url": "https://api.example.com/report"
  },
  "status": "ACTIVE",
  "maxRetries": 3,
  "createdAt": "2026-08-14T09:30:00",
  "updatedAt": "2026-08-14T09:30:00"
}
```

---

## Get All Jobs

**GET**

```
/jobs
```

Returns all jobs created by the authenticated user.

---

## Get Job

**GET**

```
/jobs/{jobId}
```

Returns complete job details.

---

## Update Job

**PUT**

```
/jobs/{jobId}
```

Updates an existing job.

---

## Delete Job

**DELETE**

```
/jobs/{jobId}
```

Deletes a job.

---

## Pause Job

**PATCH**

```
/jobs/{jobId}/pause
```

Pauses future executions.

### Response

```json
{
  "message": "Job paused successfully"
}
```

---

## Cancel Job

**PATCH**

```
/jobs/{jobId}/cancel
```

Stops all future scheduled occurrences while preserving the Job and its execution history. Already queued or running JobRuns are not cancelled in this phase; execution cancellation will be handled by the future execution pipeline.

### Response

```json
{
  "jobId": 101,
  "jobStatus": "CANCELLED"
}
```

---

## Resume Job

**PATCH**

```
/jobs/{jobId}/resume
```

Resumes a paused job.

### Response

```json
{
  "message": "Job resumed successfully"
}
```

---

## Run Job Immediately

**POST**

```
/jobs/{jobId}/run
```

Queues the job for immediate execution.

### Response

```json
{
  "message": "Job queued successfully"
}
```

---

# Job Monitoring APIs

## Get Job Status

**GET**

```
/jobs/{jobId}/status
```

Returns both the job lifecycle state and the latest execution result.

### Response

```json
{
  "jobId": 101,
  "jobStatus": "ACTIVE",
  "lastRunStatus": "SUCCESS",
  "lastExecutedAt": "2026-08-01T10:00:05"
}
```

---

## Get Job Execution History

**GET**

```
/jobs/{jobId}/runs
```

### Response

```json
[
  {
    "runId": 501,
    "status": "SUCCESS",
    "startedAt": "2026-08-01T10:00:00",
    "completedAt": "2026-08-01T10:00:04"
  },
  {
    "runId": 502,
    "status": "FAILED",
    "startedAt": "2026-08-02T10:00:00",
    "completedAt": "2026-08-02T10:00:02",
    "errorMessage": "Connection timeout"
  }
]
```

---

# Enums

## Job Status

Represents the lifecycle of a job.

```
ACTIVE
PAUSED
CANCELLED
```

---

## Job Run Status

Represents an individual execution.

```
QUEUED
RUNNING
RETRY_SCHEDULED
SUCCESS
FAILED
CANCELLED
```

`RETRY_SCHEDULED` means the same JobRun is waiting for a future retry. `FAILED` is terminal.

`maxRetries` is retries after the initial execution. Example: `maxRetries = 3` means up to 4 total HTTP attempts: initial attempt with `retryCount = 0`, then retries with `retryCount = 1`, `2`, and `3`.

---

## Schedule Type

```
IMMEDIATE
FUTURE
CRON
```

---

## Job Type

Current supported type:

```
HTTP
```

Future versions:

```
EMAIL
SCRIPT
WEBHOOK
```

---

# Standard HTTP Response Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 201 | Resource Created |
| 204 | Resource Deleted |
| 400 | Bad Request |
| 401 | Unauthorized |
| 403 | Forbidden |
| 404 | Resource Not Found |
| 409 | Conflict |
| 500 | Internal Server Error |
