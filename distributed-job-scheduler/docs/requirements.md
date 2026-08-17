# Distributed Job Scheduler

# Requirements

## 1. Functional Requirements

### Job Management
- Users can create a job.
- Users can schedule a job:
    - Immediate execution
    - Future execution
    - Cron-based recurring execution
- Users can update a scheduled job.
- Users can cancel a scheduled job.
- Users can pause a scheduled job.
- Users can resume a paused job.
- Users can trigger a job for immediate execution.

### Job Monitoring
- Users can view the current status of a job.
- Users can view the execution history of a job.
- Users can monitor running jobs in near real time.

### Retry Handling
- Failed jobs should be retried based on the configured retry policy.
- Jobs that exceed the maximum retry limit should be moved to the Dead Letter Queue (DLQ).

---

## 2. Non-Functional Requirements

### Scalability
- The system should support approximately **10,000 jobs per second**.

### Availability
- The system should prioritize **Availability** over **Consistency** (CAP Theorem).

### Reliability
- The system should provide **At-Least-Once Execution** semantics.

### Performance
- Scheduled jobs should begin execution within **2 seconds** of their scheduled execution time.

### Fault Tolerance
- Failure of an Executor instance should not result in permanent job loss.
- Jobs should be recoverable after service restarts.

### Extensibility
- The system should allow adding new job types (HTTP, Script, Email, etc.) with minimal changes.

---

## Assumptions

- Each job belongs to exactly one user.
- Authentication is required before accessing the APIs.
- PostgreSQL is used as the primary database.
- Kafka is used as the messaging system.
- Redis is used for caching and distributed coordination.
- Multiple Executor Service instances can run simultaneously.
- Initially, only HTTP jobs will be supported.