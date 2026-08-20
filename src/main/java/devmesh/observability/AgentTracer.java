package devmesh.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import devmesh.config.ProviderConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-safe local flight recorder using OpenTelemetry GenAI attribute names.
 * Prompt text, tool arguments, tool output and API keys are never captured.
 */
public final class AgentTracer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final BufferedWriter writer;
    private final Path traceFile;
    private final String traceId;
    private final String rootSpanId;
    private final String providerName;
    private final String model;
    private final String agentName;
    private final long rootStartedNanos;
    private final AtomicLong sequence = new AtomicLong();

    private volatile boolean failed;
    private volatile String failureType = "";
    private volatile boolean closed;

    private AgentTracer() {
        this.enabled = false;
        this.writer = null;
        this.traceFile = null;
        this.traceId = "";
        this.rootSpanId = "";
        this.providerName = "";
        this.model = "";
        this.agentName = "";
        this.rootStartedNanos = 0L;
    }

    private AgentTracer(Path directory, ProviderConfig provider, String sessionId,
                        String agentName) throws IOException {
        Files.createDirectories(directory);
        this.traceId = randomHex(32);
        this.rootSpanId = randomHex(16);
        this.providerName = normalizeProvider(provider != null ? provider.getProtocol() : null);
        this.model = provider != null && provider.getModel() != null ? provider.getModel() : "unknown";
        this.agentName = agentName == null || agentName.isBlank() ? "devmesh" : agentName;
        this.rootStartedNanos = System.nanoTime();
        String safeSession = sanitize(sessionId == null || sessionId.isBlank()
                ? Instant.now().toString().replace(':', '-') : sessionId);
        this.traceFile = directory.resolve(safeSession + "-" + traceId.substring(0, 8) + ".jsonl");
        this.writer = Files.newBufferedWriter(traceFile,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        this.enabled = true;
    }

    public static AgentTracer start(String workDir, ProviderConfig provider,
                                    String sessionId, String agentName) {
        if (!isEnabledByEnvironment()) {
            return new AgentTracer();
        }
        String base = workDir == null || workDir.isBlank() ? System.getProperty("user.dir") : workDir;
        String override = System.getenv("DEVMESH_TRACE_DIR");
        Path directory = override == null || override.isBlank()
                ? Path.of(base, ".devmesh", "traces")
                : Path.of(override);
        try {
            return new AgentTracer(directory, provider, sessionId, agentName);
        } catch (IOException | RuntimeException ignored) {
            return new AgentTracer();
        }
    }

    /** Visible for tests and embedders that want an explicit trace directory. */
    public static AgentTracer create(Path directory, ProviderConfig provider,
                                     String sessionId, String agentName) throws IOException {
        return new AgentTracer(directory, provider, sessionId, agentName);
    }

    private static boolean isEnabledByEnvironment() {
        String value = System.getenv("DEVMESH_TRACE");
        if (value == null || value.isBlank()) return true;
        return !value.equalsIgnoreCase("false")
                && !value.equalsIgnoreCase("off")
                && !value.equals("0");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Path traceFile() {
        return traceFile;
    }

    public String rootSpanId() {
        return rootSpanId;
    }

    public long startTimer() {
        return System.nanoTime();
    }

    public void markError(String type) {
        failed = true;
        failureType = type == null ? "unknown" : type;
    }

    public void record(String operation, String spanName, String kind, String parentSpanId,
                       long startedNanos, String status, Map<String, ?> attributes) {
        if (!enabled || closed) return;
        long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
        var attrs = baseAttributes(operation);
        if (attributes != null) {
            for (var entry : attributes.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    attrs.put(entry.getKey(), entry.getValue());
                }
            }
        }
        writeSpan(randomHex(16), parentSpanId, spanName, kind, elapsed,
                status == null ? "UNSET" : status, attrs);
    }

    public void event(String operation, String name, Map<String, ?> attributes) {
        record(operation, name, "INTERNAL", rootSpanId, System.nanoTime(), "OK", attributes);
    }

    private Map<String, Object> baseAttributes(String operation) {
        var attrs = new LinkedHashMap<String, Object>();
        attrs.put("gen_ai.operation.name", operation);
        attrs.put("gen_ai.provider.name", providerName);
        attrs.put("gen_ai.request.model", model);
        attrs.put("gen_ai.agent.name", agentName);
        attrs.put("devmesh.telemetry.content_captured", false);
        return attrs;
    }

    private synchronized void writeSpan(String spanId, String parentSpanId, String name,
                                        String kind, long elapsedNanos, String status,
                                        Map<String, Object> attributes) {
        if (!enabled || closed) return;
        var line = new LinkedHashMap<String, Object>();
        line.put("schema_version", "devmesh.trace.v1");
        line.put("timestamp", Instant.now().toString());
        line.put("sequence", sequence.incrementAndGet());
        line.put("trace_id", traceId);
        line.put("span_id", spanId);
        if (parentSpanId != null && !parentSpanId.isBlank()) line.put("parent_span_id", parentSpanId);
        line.put("name", name);
        line.put("kind", kind);
        line.put("duration_ms", elapsedNanos / 1_000_000.0);
        line.put("status", status);
        line.put("attributes", attributes);
        try {
            writer.write(MAPPER.writeValueAsString(line));
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            failed = true;
            failureType = "trace_io";
        }
    }

    @Override
    public synchronized void close() {
        if (!enabled || closed) return;
        var attrs = baseAttributes("invoke_agent");
        attrs.put("devmesh.trace.span_count", sequence.get());
        if (failed) attrs.put("error.type", failureType);
        writeSpan(rootSpanId, null, "invoke_agent " + agentName, "INTERNAL",
                Math.max(0L, System.nanoTime() - rootStartedNanos), failed ? "ERROR" : "OK", attrs);
        closed = true;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }

    private static String normalizeProvider(String protocol) {
        if (protocol == null || protocol.isBlank()) return "unknown";
        return switch (protocol) {
            case "openai-compat" -> "openai_compatible";
            default -> protocol;
        };
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String randomHex(int length) {
        String value = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        return value.substring(0, length);
    }
}
