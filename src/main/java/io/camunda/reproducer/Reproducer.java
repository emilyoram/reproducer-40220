package io.camunda.reproducer;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientBuilder;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import java.time.Duration;

/**
 * Reproducer for <a href="https://github.com/camunda/camunda/issues/40220">
 * camunda/camunda#40220</a>: Log pollution due to DEADLINE_EXCEEDED in idle Job Workers.
 *
 * <p>This reproducer registers a job worker for a job type that has no available jobs, then
 * monitors the logs for DEADLINE_EXCEEDED warnings. When the gateway uses gRPC long-polling,
 * the gateway holds the ActivateJobs request open until jobs arrive or the long-polling timeout
 * expires. If the gateway's long-polling timeout is close to or exceeds the client's gRPC
 * deadline, the client times out first and logs a WARN-level DEADLINE_EXCEEDED exception —
 * repeatedly, every polling cycle.</p>
 *
 * <h2>Root cause</h2>
 * <p>In {@code JobPollerImpl.logFailure()}, only {@code RESOURCE_EXHAUSTED} is downgraded to
 * TRACE level. {@code DEADLINE_EXCEEDED} — which is an expected, non-error condition during
 * long-polling with no available jobs — is logged at WARN level with a full stack trace.</p>
 *
 * <h2>Expected behavior</h2>
 * <p>An idle job worker should not produce any warnings. The DEADLINE_EXCEEDED from an empty
 * long-poll response should be treated as a normal "no jobs available" condition, not a failure.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Local mode (Docker Compose):
 * docker compose up -d
 * mvn compile exec:java
 *
 * # SaaS mode (set env vars):
 * export ZEEBE_CLIENT_CLOUD_CLUSTER_ID=your-cluster-id
 * export ZEEBE_CLIENT_CLOUD_REGION=bru-2
 * export ZEEBE_CLIENT_ID=your-client-id
 * export ZEEBE_CLIENT_SECRET=your-client-secret
 * mvn compile exec:java
 * </pre>
 */
public class Reproducer {

    private static final String IDLE_JOB_TYPE = "reproducer-40220-no-jobs";
    private static final Duration RUN_DURATION = Duration.ofMinutes(2);

    private static final JobHandler NOOP_HANDLER = (client, job) -> {
        System.out.println("[UNEXPECTED] Received job: " + job.getKey());
        client.newCompleteCommand(job.getKey()).send().join();
    };

    public static void main(final String[] args) throws InterruptedException {
        final boolean saasMode = System.getenv("ZEEBE_CLIENT_CLOUD_CLUSTER_ID") != null;

        System.out.println("=== Reproducer for camunda/camunda#40220 ===");
        System.out.println("Mode: " + (saasMode ? "SaaS (cloud)" : "Local (Docker)"));
        System.out.println("Job type: " + IDLE_JOB_TYPE);
        System.out.println("Run duration: " + RUN_DURATION);
        System.out.println();
        System.out.println("Expecting WARN-level 'Failed to activate jobs' with");
        System.out.println("StatusRuntimeException: DEADLINE_EXCEEDED every ~20s if the bug is present.");
        System.out.println("============================================");
        System.out.println();

        final ZeebeClientBuilder builder;
        if (saasMode) {
            // SaaS mode: uses ZEEBE_CLIENT_CLOUD_CLUSTER_ID, ZEEBE_CLIENT_CLOUD_REGION,
            // ZEEBE_CLIENT_ID, ZEEBE_CLIENT_SECRET env vars
            final String clusterId = System.getenv("ZEEBE_CLIENT_CLOUD_CLUSTER_ID");
            final String region = envOrDefault("ZEEBE_CLIENT_CLOUD_REGION", "bru-2");
            final String clientId = System.getenv("ZEEBE_CLIENT_ID");
            final String clientSecret = System.getenv("ZEEBE_CLIENT_SECRET");
            builder = ZeebeClient.newCloudClientBuilder()
                    .withClusterId(clusterId)
                    .withRegion(region)
                    .withClientId(clientId)
                    .withClientSecret(clientSecret);
            System.out.println("Connecting to SaaS cluster: " + clusterId + "." + region);
        } else {
            // Local mode: connect to Docker Compose Zeebe
            final String gatewayAddress = envOrDefault("ZEEBE_ADDRESS", "localhost:26500");
            final boolean usePlaintext = Boolean.parseBoolean(
                    envOrDefault("ZEEBE_PLAINTEXT", "true"));
            builder = ZeebeClient.newClientBuilder().gatewayAddress(gatewayAddress);
            if (usePlaintext) {
                builder.usePlaintext();
            }
            System.out.println("Connecting to local gateway: " + gatewayAddress);
        }
        System.out.println();

        try (final ZeebeClient client = builder.build()) {
            // Verify connectivity
            final var topology = client.newTopologyRequest().send().join();
            System.out.println("Connected to cluster: " + topology.getClusterSize()
                    + " node(s), " + topology.getPartitionsCount() + " partition(s)");
            System.out.println();

            // Register an idle worker — no jobs of this type exist
            try (final JobWorker worker = client.newWorker()
                    .jobType(IDLE_JOB_TYPE)
                    .handler(NOOP_HANDLER)
                    .open()) {

                System.out.println("Job worker registered. Monitoring for log pollution...");
                System.out.println("(Watch for WARN lines from io.camunda.client.job.poller)");
                System.out.println();

                Thread.sleep(RUN_DURATION.toMillis());
            }

            System.out.println();
            System.out.println("=== Reproducer complete ===");
            System.out.println("Check above for StatusRuntimeException: DEADLINE_EXCEEDED warnings.");
            System.out.println("If present, the bug is confirmed. If absent, the gateway");
            System.out.println("responded before the client deadline expired.");
        }
    }

    private static String envOrDefault(final String key, final String defaultValue) {
        final String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
}
