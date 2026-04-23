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
 * <h2>How gRPC long-polling works</h2>
 * <p>When a job worker polls for jobs, the client sends an ActivateJobs gRPC call. The gateway
 * holds the request open (long-polling) until either jobs become available or a timeout expires.
 * The timeout the gateway uses is determined by:</p>
 * <ol>
 *   <li>The client's {@code requestTimeout} field in the protobuf message (if &gt; 0)</li>
 *   <li>The server's configured {@code longPollingTimeout} (if client sends 0)</li>
 * </ol>
 *
 * <p>Meanwhile, the client sets a gRPC deadline of {@code requestTimeout + DEADLINE_OFFSET}.
 * The {@code DEADLINE_OFFSET} is <b>hardcoded to 10 seconds</b> in
 * {@code ActivateJobsCommandImpl} (both old and new clients). This gives the server a 10s
 * buffer to respond after its long-polling timer fires.</p>
 *
 * <h2>How to trigger DEADLINE_EXCEEDED</h2>
 * <p>Under normal conditions (client requestTimeout &gt; 0), the gateway uses the client's
 * requestTimeout as its own hold time. With the 10s DEADLINE_OFFSET buffer, the client almost
 * never times out -- the gateway completes well within the gRPC deadline.</p>
 *
 * <p>However, if the worker sets {@code requestTimeout = Duration.ZERO}, the protobuf field
 * is 0, so the gateway falls back to its own configured {@code longPollingTimeout}. The gRPC
 * deadline is then just {@code 0 + 10s = 10s}. If the server's longPollingTimeout exceeds
 * 10 seconds, the client's gRPC deadline expires first, producing DEADLINE_EXCEEDED.</p>
 *
 * <p>In production, DEADLINE_EXCEEDED can also occur with non-zero requestTimeout due to:</p>
 * <ul>
 *   <li>Network latency or proxy/load balancer timeouts eating into the 10s buffer</li>
 *   <li>Gateway GC pauses delaying the response after the long-polling timer fires</li>
 *   <li>Server overload causing scheduling delays for the long-polling timer</li>
 * </ul>
 *
 * <h2>Important: REST vs gRPC</h2>
 * <p>The new {@code camunda-client-java} defaults to {@code preferRestOverGrpc = true}. The
 * DEADLINE_EXCEEDED issue only occurs on the <b>gRPC</b> code path. This reproducer must
 * explicitly set {@code .preferRestOverGrpc(false)} to use gRPC for ActivateJobs.</p>
 *
 * <h2>Root cause (the logging bug)</h2>
 * <p>In {@code JobPollerImpl.logFailure()}, only {@code RESOURCE_EXHAUSTED} is downgraded to
 * TRACE level. {@code DEADLINE_EXCEEDED} -- which is equally expected and harmless during
 * idle long-polling -- falls through to {@code LOG.warn()} with a full stack trace. This
 * pollutes logs on every polling cycle when the condition occurs.</p>
 *
 * <h2>Reproducer strategy</h2>
 * <p>This reproducer sets the worker's {@code requestTimeout} to {@code Duration.ZERO}, which
 * causes the gateway to fall back to its own {@code longPollingTimeout}. We configure the
 * server's {@code longPollingTimeout} to exceed the 10s DEADLINE_OFFSET, guaranteeing that
 * the client's gRPC deadline expires before the server responds. This reliably produces the
 * DEADLINE_EXCEEDED warnings that demonstrate the logging bug.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Local mode (Docker Compose -- server longPollingTimeout = 15s by default):
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
     * Hardcoded DEADLINE_OFFSET in ActivateJobsCommandImpl. The gRPC deadline for ActivateJobs
     * is {@code requestTimeout + DEADLINE_OFFSET}. This is 10s in BOTH the old zeebe-client-java
     * and the new camunda-client-java.
     *
     * <p>Note: This is NOT the same as {@code defaultRequestTimeoutOffset} in the client builder
     * (which is 1s in the new client). That offset only affects the HTTP/REST response timeout,
     * not the gRPC deadline for ActivateJobs.</p>
     */
    private static final int GRPC_DEADLINE_OFFSET_S = 10;

    private static final JobHandler NOOP_HANDLER = (client, job) -> {
        System.out.println("[UNEXPECTED] Received job: " + job.getKey());
        client.newCompleteCommand(job.getKey()).send().join();
    };

    public static void main(final String[] args) throws InterruptedException {
        final String clusterId = firstEnv("CAMUNDA_CLIENT_CLOUD_CLUSTERID", "CAMUNDA_CLUSTER_ID");
        final boolean saasMode = clusterId != null;

        // When CLIENT_REQUEST_TIMEOUT_S=0 (default), the protobuf requestTimeout is 0.
        // The gateway then falls back to its own longPollingTimeout, which we configure
        // to exceed the 10s DEADLINE_OFFSET. This guarantees DEADLINE_EXCEEDED.
        final int requestTimeoutS = Integer.parseInt(
                envOrDefault("CLIENT_REQUEST_TIMEOUT_S", "0"));
        final int runDurationS = Integer.parseInt(
                envOrDefault("RUN_DURATION_S", "120"));
        final Duration requestTimeout = Duration.ofSeconds(requestTimeoutS);
        final Duration runDuration = Duration.ofSeconds(runDurationS);
        final int grpcDeadlineS = requestTimeoutS + GRPC_DEADLINE_OFFSET_S;

        System.out.println("=== Reproducer for camunda/camunda#40220 ===");
        System.out.println("Mode: " + (saasMode ? "SaaS (cloud)" : "Local (Docker)"));
        System.out.println("Client: camunda-client-java (gRPC transport forced)");
        System.out.println("Job type: " + IDLE_JOB_TYPE);
        System.out.println();
        System.out.println("Client requestTimeout:     " + requestTimeoutS + "s"
                + (requestTimeoutS == 0 ? " (gateway will use its own longPollingTimeout)" : ""));
        System.out.println("gRPC DEADLINE_OFFSET:      " + GRPC_DEADLINE_OFFSET_S
                + "s (hardcoded in ActivateJobsCommandImpl)");
        System.out.println("Effective gRPC deadline:   " + grpcDeadlineS + "s");
        System.out.println("Run duration:              " + runDurationS + "s");
        System.out.println();
        if (requestTimeoutS == 0) {
            System.out.println("With requestTimeout=0, the gateway falls back to its configured");
            System.out.println("longPollingTimeout. If that exceeds " + grpcDeadlineS
                    + "s, DEADLINE_EXCEEDED occurs.");
        } else {
            System.out.println("With requestTimeout=" + requestTimeoutS
                    + "s, the gateway uses this as its hold time.");
            System.out.println("The 10s DEADLINE_OFFSET buffer should prevent DEADLINE_EXCEEDED");
            System.out.println("unless network latency or GC pauses consume the buffer.");
        }
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
                    .credentialsProvider(credentialsBuilder.build())
                    .preferRestOverGrpc(false);
            System.out.println("Connecting to SaaS: " + address);
        } else {
            // Local mode: connect to Docker Compose Zeebe via plaintext gRPC
            final String gatewayAddress = envOrDefault("ZEEBE_ADDRESS", "localhost:26500");
            builder = CamundaClient.newClientBuilder()
                    .grpcAddress(URI.create("http://" + gatewayAddress))
                    .preferRestOverGrpc(false);
            System.out.println("Connecting to local gateway (gRPC): " + gatewayAddress);
        }

        // Use a generous default request timeout for non-ActivateJobs calls (e.g., topology)
        builder.defaultRequestTimeout(Duration.ofSeconds(20));
        System.out.println();

        try (final CamundaClient client = builder.build()) {
            // Verify connectivity
            final var topology = client.newTopologyRequest().send().join();
            System.out.println("Connected to cluster: " + topology.getClusterSize()
                    + " node(s), " + topology.getPartitionsCount() + " partition(s)");
            System.out.println();

            // Register an idle worker with the configured requestTimeout.
            // When requestTimeout=0, the protobuf field is 0, so the gateway falls back to
            // its own longPollingTimeout. The gRPC deadline is only 10s (DEADLINE_OFFSET).
            // If the server's longPollingTimeout > 10s, the client times out first.
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
            System.out.println("If present, the bug is confirmed: DEADLINE_EXCEEDED should be");
            System.out.println("downgraded to TRACE in JobPollerImpl.logFailure(), just like");
            System.out.println("RESOURCE_EXHAUSTED already is.");
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
