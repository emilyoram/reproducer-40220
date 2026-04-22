# Reproducer: DEADLINE_EXCEEDED Log Pollution in Idle Job Workers

**Issue:** [camunda/camunda#40220](https://github.com/camunda/camunda/issues/40220)

## Description

When a Camunda 8 Job Worker is idle (no jobs available for its job type), the gRPC long-polling
mechanism causes repeated `DEADLINE_EXCEEDED` exceptions logged at **WARN** level. This pollutes
application logs with noisy stack traces that are not actionable, making it difficult to spot
genuine errors.

## Steps to Reproduce

### Prerequisites

- Docker & Docker Compose
- Java 21
- Maven

### Run Locally

```bash
# 1. Start Zeebe with long-polling configured to trigger the issue
docker compose up -d

# 2. Wait for Zeebe to be healthy (~30 seconds)
docker compose ps  # wait until status shows "healthy"

# 3. Run the reproducer
mvn compile exec:java
```

### Run via GitHub Actions

1. Fork this repository
2. Go to **Actions** → **Run Reproducer** → **Run workflow**
3. Check the output for DEADLINE_EXCEEDED warnings

## Expected Behavior

An idle job worker should not produce any log output. When no jobs are available, the long-poll
response should be handled silently — it is a normal, expected condition.

## Actual Behavior

Every ~20 seconds, the idle job worker logs a `WARN`-level message with full stack trace:

```
WARN io.camunda.zeebe.client.job.poller - Failed to activate jobs for worker
    default and job type reproducer-40220-no-jobs
io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: deadline exceeded after ...
    at io.grpc.stub.ClientCalls.toStatusRuntimeException(ClientCalls.java:...)
    at io.grpc.stub.ClientCalls$StreamObserverToCallListenerAdapter.onClose(...)
    ...
```

This repeats indefinitely for the lifetime of the worker, polluting logs in any application
that has idle workers.

## Root Cause Analysis

The issue is in `JobPollerImpl.logFailure()` in the Camunda Java client:

**File:** `clients/java/src/main/java/io/camunda/client/impl/worker/JobPollerImpl.java`

```java
private void logFailure(final Throwable error) {
    if (error instanceof StatusRuntimeException statusRuntimeException) {
      // Only RESOURCE_EXHAUSTED is downgraded to TRACE
      if (statusRuntimeException.getStatus().getCode() == Code.RESOURCE_EXHAUSTED) {
        LOG.trace("Failed to activate jobs for worker {} and job type {}", ...);
        return;
      }
    }
    // DEADLINE_EXCEEDED falls through here and is logged at WARN with stack trace
    LOG.warn("Failed to activate jobs for worker {} and job type {}", ..., error);
}
```

**The problem:** Only `RESOURCE_EXHAUSTED` is treated as an expected condition. `DEADLINE_EXCEEDED`
— which is the *normal* result when the gateway's long-polling timeout fires at or after the
client's gRPC deadline — is logged as a warning.

### Timing breakdown

| Component | Default Timeout |
|-----------|----------------|
| Gateway long-polling timeout | 10,000ms |
| Client requestTimeout | 10,000ms |
| Client DEADLINE_OFFSET | 10,000ms |
| **Client gRPC deadline** | **requestTimeout + DEADLINE_OFFSET = 20,000ms** |

In the default configuration, the gateway's 10s long-poll completes before the client's 20s
deadline, so the issue may not appear. However, in self-managed deployments where the long-polling
timeout is configured closer to or exceeding the client deadline (or with network latency), the
`DEADLINE_EXCEEDED` fires and pollutes logs.

### Suggested Fix

Add `DEADLINE_EXCEEDED` to the suppression logic in `logFailure()`:

```java
if (statusRuntimeException.getStatus().getCode() == Code.RESOURCE_EXHAUSTED
    || statusRuntimeException.getStatus().getCode() == Code.DEADLINE_EXCEEDED) {
    LOG.trace("Failed to activate jobs for worker {} and job type {}", ...);
    return;
}
```

## Environment

- **Zeebe version:** 8.6.7 (via Docker)
- **Java client:** `zeebe-client-java` 8.6.7
- **Java:** 21
- **OS:** Linux (GitHub Actions) / Any (local)

## Reproducer Configuration

This reproducer uses Docker Compose with `ZEEBE_BROKER_GATEWAY_LONGPOLLING_TIMEOUT=20000ms` to
ensure the gateway holds the long-poll request long enough to trigger the client-side
`DEADLINE_EXCEEDED`. The reproducer registers a worker for a job type with no existing jobs and
runs for 2 minutes, during which DEADLINE_EXCEEDED warnings are observed every ~20 seconds.
