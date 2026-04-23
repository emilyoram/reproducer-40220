package io.camunda.reproducer;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientBuilder;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import io.camunda.zeebe.client.impl.oauth.OAuthCredentialsProviderBuilder;
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
 * # Start Zeebe with Docker Compose (long-polling enabled by default):
 * docker compose up -d
 *
 * # Wait for Zeebe to be ready (~30s), then run:
 * mvn compile exec:java
 *
 * # Watch the output for ~60 seconds. You should see repeated WARN lines like:
 * # WARN io.camunda.client.job.poller - Failed to activate jobs for worker default and job type reproducer-40220-no-jobs
 * # io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: ...
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
        final String clusterId = firstEnv("CAMUNDA_CLIENT_CLOUD_CLUSTERID", "CAMUNDA_CLUSTER_ID");
        final boolean saasMode = clusterId != null;

        System.out.println("=== Reproducer for camunda/camunda#40220 ===");
        System.out.println("Mode: " + (saasMode ? "SaaS (cloud)" : "Local (Docker)"));
        System.out.println("Job type: " + IDLE_JOB_TYPE);
        System.out.println("Run duration: " + RUN_DURATION);
        System.out.println();
        System.out.println("Expecting WARN-level 'Failed to activate jobs' with");
        System.out.println("DEADLINE_EXCEEDED status every ~20s if the bug is present.");
        System.out.println("============================================");
        System.out.println();

        final ZeebeClientBuilder builder;
        if (saasMode) {
            final String region = firstEnv(
                    "CAMUNDA_CLIENT_CLOUD_REGION", "CAMUNDA_CLUSTER_REGION");
            final String address = firstEnv("ZEEBE_ADDRESS") != null
                    ? firstEnv("ZEEBE_ADDRESS")
                    : clusterId + "." + (region != null ? region : "bru-2")
                            + ".zeebe.camunda.io:443";
            final String clientId = firstEnv(
                    "ZEEBE_CLIENT_ID", "CAMUNDA_CLIENT_ID", "CAMUNDA_CLIENT_AUTH_CLIENTID");
            final String clientSecret = firstEnv(
                    "ZEEBE_CLIENT_SECRET", "CAMUNDA_CLIENT_SECRET", "CAMUNDA_CLIENT_AUTH_CLIENTSECRET");
            final String authUrl = firstEnv(
                    "ZEEBE_AUTHORIZATION_SERVER_URL", "CAMUNDA_OAUTH_URL");

            final var credentialsBuilder = new OAuthCredentialsProviderBuilder()
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .audience("zeebe.camunda.io");
            if (authUrl != null) {
                credentialsBuilder.authorizationServerUrl(authUrl);
            }

            builder = ZeebeClient.newClientBuilder()
                    .gatewayAddress(address)
                    .credentialsProvider(credentialsBuilder.build());
            System.out.println("Connecting to SaaS: " + address);
        } else {
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
            final var topology = client.newTopologyRequest().send().join();
            System.out.println("Connected to cluster: " + topology.getClusterSize()
                    + " node(s), " + topology.getPartitionsCount() + " partition(s)");
            System.out.println();

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
            System.out.println("Check above for DEADLINE_EXCEEDED warnings.");
            System.out.println("If present, the bug is confirmed. If absent, the gateway");
            System.out.println("responded before the client deadline expired.");
        }
    }

    private static String envOrDefault(final String key, final String defaultValue) {
        final String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }

    /** Return the first non-null env value, or null if none set. */
    private static String firstEnv(final String... keys) {
        for (final String key : keys) {
            final String value = System.getenv(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
