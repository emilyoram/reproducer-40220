# Reproducer: DEADLINE_EXCEEDED Log Pollution in Idle Job Workers

**Issue:** [camunda/camunda#40220](https://github.com/camunda/camunda/issues/40220)

## Description

When a Camunda 8 Job Worker is idle (no jobs available for its job type), the gRPC long-polling
mechanism can produce repeated `DEADLINE_EXCEEDED` exceptions logged at **WARN** level with full
stack traces. This pollutes application logs with noisy output that is not actionable, making it
difficult to spot genuine errors.

## How gRPC Long-Polling Works

When a job worker polls for jobs, the client sends an `ActivateJobs` gRPC call with two relevant
parameters:

1. **Protobuf `requestTimeout`** — tells the gateway how long to hold the request open
2. **gRPC deadline** — set to `requestTimeout + DEADLINE_OFFSET` (hardcoded to **10 seconds** in
   `ActivateJobsCommandImpl`, in both the old `zeebe-client-java` and the new `camunda-client-java`)

The gateway decides how long to hold the request:

- **If `requestTimeout > 0`** → gateway uses the client's value as its hold time → the 10s
  `DEADLINE_OFFSET` buffer means the server always responds before the client's gRPC deadline →
  **no DEADLINE_EXCEEDED**
- **If `requestTimeout == 0`** → gateway falls back to its own configured `longPollingTimeout`
  (default: 10s) → if that exceeds the 10s gRPC deadline → **DEADLINE_EXCEEDED**

### Key Insight: `DEADLINE_OFFSET` vs `defaultRequestTimeoutOffset`

The 10s `DEADLINE_OFFSET` in `ActivateJobsCommandImpl` is **not the same** as the
`defaultRequestTimeoutOffset` in `CamundaClientBuilderImpl` (1s in the new client, 10s in the old).
The latter only affects the HTTP/REST response timeout, not the gRPC deadline for `ActivateJobs`.

### Key Insight: REST vs gRPC

The new `camunda-client-java` defaults to `preferRestOverGrpc = true`. The `DEADLINE_EXCEEDED`
issue **only occurs on the gRPC code path**. This reproducer explicitly sets
`.preferRestOverGrpc(false)` to use gRPC.

## Root Cause

The issue is in `JobPollerImpl.logFailure()` in the Camunda Java client:

**File:** `clients/java/src/main/java/io/camunda/client/impl/worker/JobPollerImpl.java`

```java
private void logFailure(final Throwable throwable) {
    final String errorMsg = "Failed to activate jobs for worker {} and job type {}";

    if (throwable instanceof StatusRuntimeException) {
      final StatusRuntimeException statusRuntimeException = (StatusRuntimeException) throwable;
      if (statusRuntimeException.getStatus().getCode() == Status.RESOURCE_EXHAUSTED.getCode()) {
        // Only RESOURCE_EXHAUSTED is downgraded to TRACE
        LOG.trace(errorMsg, workerName, jobType, throwable);
        return;
      }
    }
    // DEADLINE_EXCEEDED falls through here and is logged at WARN with stack trace
    LOG.warn(errorMsg, workerName, jobType, throwable);
}
```

**The problem:** Only `RESOURCE_EXHAUSTED` is treated as an expected condition. `DEADLINE_EXCEEDED`
— which is equally expected and harmless during idle long-polling — falls through to `LOG.warn()`
with a full stack trace.

## Who's Affected

1. **Users setting `requestTimeout = 0` (or `Duration.ZERO`)** on the job worker — the gateway
   falls back to its server-side `longPollingTimeout`, which commonly exceeds 10s
2. **Users on gRPC transport** — the new `camunda-client-java` defaults to REST, so only users who
   explicitly use gRPC or the legacy `zeebe-client-java` are affected
3. **Users in high-latency environments** — even with `requestTimeout > 0`, network latency, GC
   pauses, or proxy/load balancer timeouts can eat into the 10s buffer

## Suggested Fix

Add `DEADLINE_EXCEEDED` to the same TRACE-level downgrade as `RESOURCE_EXHAUSTED`:

```java
if (statusRuntimeException.getStatus().getCode() == Status.RESOURCE_EXHAUSTED.getCode()
    || statusRuntimeException.getStatus().getCode() == Status.DEADLINE_EXCEEDED.getCode()) {
  LOG.trace(errorMsg, workerName, jobType, throwable);
  return;
}
```

## Reproducer Strategy

This reproducer sets the worker's `requestTimeout` to `Duration.ZERO`, which causes the gateway to
fall back to its own `longPollingTimeout`. We configure the server's `longPollingTimeout` to exceed
the 10s `DEADLINE_OFFSET`, guaranteeing that the client's gRPC deadline expires before the server
responds. This reliably produces the `DEADLINE_EXCEEDED` warnings.

## Steps to Reproduce

### Prerequisites

- Docker & Docker Compose
- Java 21
- Maven

### Run Locally

```bash
# 1. Start Zeebe with long-polling timeout > 10s to trigger the issue
docker compose up -d

# 2. Wait for Zeebe to be healthy (~30 seconds)
docker compose ps  # wait until status shows "healthy"

# 3. Run the reproducer (defaults: requestTimeout=0, server longPollingTimeout=15s)
mvn compile exec:java
```

You'll see WARN lines every ~10 seconds:
```
WARN io.camunda.client.job.poller - Failed to activate jobs for worker default and job type reproducer-40220-no-jobs
io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: deadline exceeded after ...
    at io.grpc.stub.ClientCalls.toStatusRuntimeException(ClientCalls.java:...)
    ...
```

### Run via GitHub Actions

1. Fork this repository
2. Go to **Actions** → **Run Reproducer** → **Run workflow**
3. Select mode:
   - **`local`** — single run with default settings
   - **`local-matrix`** — 10 timeout combinations proving when the bug triggers vs doesn't
   - **`saas`** — run against Camunda SaaS (requires secrets)

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CLIENT_REQUEST_TIMEOUT_S` | `0` | Worker requestTimeout in seconds. 0 = gateway uses its own longPollingTimeout |
| `RUN_DURATION_S` | `120` | How long to run the reproducer |
| `LONGPOLLING_TIMEOUT` | `15000` | Server longPollingTimeout in ms (docker-compose env var) |

## Matrix Results

The `local-matrix` workflow runs 10 combinations confirming the exact conditions:

| # | requestTimeout | Server longPolling | gRPC Deadline | Expected | Result |
|---|---|---|---|---|---|
| 1 | 0s | 15s | 10s | BUG | DEADLINE_EXCEEDED |
| 2 | 0s | 20s | 10s | BUG | DEADLINE_EXCEEDED |
| 3 | 0s | 30s | 10s | BUG | DEADLINE_EXCEEDED |
| 4 | 0s | 12s | 10s | BUG | DEADLINE_EXCEEDED |
| 5 | 0s | 8s | 10s | no bug | No warnings |
| 6 | 0s | 5s | 10s | no bug | No warnings |
| 7 | 10s | 30s | 20s | no bug | No warnings |
| 8 | 5s | 30s | 15s | no bug | No warnings |
| 9 | 15s | 30s | 25s | no bug | No warnings |
| 10 | 1s | 30s | 11s | no bug | No warnings |

**Key takeaway:** With `requestTimeout > 0`, the server ignores its own `longPollingTimeout` and
uses the client's value instead, always completing within the 10s DEADLINE_OFFSET buffer. The bug
only fires reliably when `requestTimeout = 0` and the server's `longPollingTimeout > 10s`.

## Environment

- **Zeebe version:** 8.6.7 (via Docker)
- **Java client:** `camunda-client-java` 8.8.22 (with `.preferRestOverGrpc(false)` to force gRPC)
- **Java:** 21
- **OS:** Linux (GitHub Actions) / Any (local)
