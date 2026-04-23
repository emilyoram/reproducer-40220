package io.camunda.reproducer;

import io.camunda.client.CamundaClient;
import io.camunda.client.CamundaClientBuilder;
import io.camunda.client.api.worker.JobHandler;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.impl.oauth.OAuthCredentialsProviderBuilder;
import java.net.URI;
import java.time.Duration;

/**
 * Reproducer for <a href="https://github.com/camunda/camunda/issues/40220">
 * camunda/camunda#40220</a>: Log pollution due to DEADLINE_EXCEEDED in idle Job Workers.
 *
 * <p>This reproducer registers a job worker for a job type that has no available jobs, then
 * monitors the logs for DEADLINE_EXCEEDED warnings. When the gateway uses gRPC long-polling,
 * the gateway holds the ActivateJobs request open until jobs arrive or the long-polling timeout
 * expires. If the gateway's long-polling timeout exceeds the client's gRPC deadline, the client
 * times out first and logs a WARN-level DEADLINE_EXCEEDED exception -- repeatedly, every
 * polling cycle.</p>
 *
 * <h2>Root cause</h2>
 * <p>In {@code io.camunda.client.impl.worker.JobPollerImpl.logFailure()}, only
 * {@code RESOURCE_EXHAUSTED} is downgraded to TRACE level. {@code DEADLINE_EXCEEDED} -- which
 * is an expected, non-error condition during long-polling with no available jobs -- is logged
 * at WARN level with a full stack trace.</p>
 *
 * <h2>Why this triggers easily with the new client</h2>
 * <p>The new {@code camunda-client-java} uses a {@code defaultRequestTimeoutOffset} of only
 * <b>1 second</b> (vs 10s in the legacy zeebe-client-java). So with the default
 * {@code requestTimeout} of 10s, the gRPC deadline is only 11s -- easily exceeded by
 * the gateway's default long-polling timeout of 10s under any network latency.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Local mode (Docker Compose):
 * docker compose up -d
 * mvn compile exec:java
 *
 * # Watch for WARN lines from io.camunda.client.job.poller:
 * # WARN  Failed to activate jobs for worker default and job type reproducer-40220-no-jobs
 * # io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: ...
 * </pre>
 */
public class Reproducer {

    private static final String IDLE_JOB_TYPE = "reproducer-40220-no-jobs";

    /**
     * The new client's defaultRequestTimeoutOffset (added to requestTimeout for the gRPC
     * deadline). In camunda-client-java this is 1s, vs 10s in the legacy zeebe-client-java.
     */
    private static final int REQUEST_TIMEOUT_OFFSET_S = 1;

    private static final JobHandler NOOP_HANDLER = (client, job) -> {
        System.out.println("[UNEXPECTED] Received job: " + job.getKey());
        client.newCompleteCommand(job.getKey()).send().join();
    };

    public static void main(final String[] args) throws InterruptedException {
        final String clusterId = firstEnv("CAMUNDA_CLIENT_CLOUD_CLUSTERID", "CAMUNDA_CLUSTER_ID");
        final boolean saasMode = clusterId != null;

        // Configurable timeouts
        final int requestTimeoutS = Integer.parseInt(
                envOrDefault("CLIENT_REQUEST_TIMEOUT_S", "10"));
        final int runDurationS = Integer.parseInt(
                envOrDefault("RUN_DURATION_S", "120"));
        final Duration requestTimeout = Duration.ofSeconds(requestTimeoutS);
        final Duration runDuration = Duration.ofSeconds(runDurationS);
        final int grpcDeadlineS = requestTimeoutS + REQUEST_TIMEOUT_OFFSET_S;

        System.out.println("=== Reproducer for camunda/camunda#40220 ===");
        System.out.println("Mode: " + (saasMode ? "SaaS (cloud)" : "Local (Docker)"));
        System.out.println("Client: camunda-client-java (new client)");
        System.out.println("Job type: " + IDLE_JOB_TYPE);
        System.out.println("Client requestTimeout: " + requestTimeoutS + "s");
        System.out.println("Client gRPC deadline:  " + grpcDeadlineS
                + "s (requestTimeout + " + REQUEST_TIMEOUT_OFFSET_S + "s offset)");
        System.out.println("Run duration: " + runDurationS + "s");
        System.out.println();
        System.out.println("Bug triggers when server long-polling timeout > client gRPC deadline (" + grpcDeadlineS + "s)");
        System.out.println("With the new client (1s offset), even the default server timeout (10s) can trigger this.");
        System.out.println("============================================");
        System.out.println();

        final CamundaClientBuilder builder;
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

            builder = CamundaClient.newClientBuilder()
                    .grpcAddress(URI.create("https://" + address))
                    .credentialsProvider(credentialsBuilder.build());
            System.out.println("Connecting to SaaS: " + address);
        } else {
            // Local mode: connect to Docker Compose Zeebe via plaintext gRPC
            final String gatewayAddress = envOrDefault("ZEEBE_ADDRESS", "localhost:26500");
            builder = CamundaClient.newClientBuilder()
                    .grpcAddress(URI.create("http://" + gatewayAddress));
            System.out.println("Connecting to local gateway: " + gatewayAddress);
        }

        // Set the request timeout (gRPC deadline = this + 1s offset)
        builder.defaultRequestTimeout(requestTimeout);
        System.out.println();

        try (final CamundaClient client = builder.build()) {
            // Verify connectivity
            final var topology = client.newTopologyRequest().send().join();
            System.out.println("Connected to cluster: " + topology.getClusterSize()
                    + " node(s), " + topology.getPartitionsCount() + " partition(s)");
            System.out.println();

            // Register an idle worker -- no jobs of this type exist
            try (final JobWorker worker = client.newWorker()
                    .jobType(IDLE_JOB_TYPE)
                    .handler(NOOP_HANDLER)
                    .requestTimeout(requestTimeout)
                    .open()) {

                System.out.println("Job worker registered. Monitoring for log pollution...");
                System.out.println("(Watch for WARN lines from io.camunda.client.job.poller)");
                System.out.println();

                Thread.sleep(runDuration.toMillis());
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
