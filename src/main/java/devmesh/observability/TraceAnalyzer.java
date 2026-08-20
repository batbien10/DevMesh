package devmesh.observability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Aggregates one trace file or a directory of trace JSONL files. */
public final class TraceAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private TraceAnalyzer() {}

    public record Summary(
            int traceFiles,
            int agentRuns,
            int failedRuns,
            int modelCalls,
            int toolCalls,
            int toolErrors,
            int retries,
            int compactions,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            double modelLatencyP50Ms,
            double modelLatencyP95Ms,
            double toolLatencyP50Ms,
            double toolLatencyP95Ms,
            double peakContextPressure,
            Set<String> tools,
            Set<String> canarySkills,
            int malformedRecords
    ) {
        public double toolSuccessRate() {
            return toolCalls == 0 ? 1.0 : (double) (toolCalls - toolErrors) / toolCalls;
        }

        public double cacheReadRatio() {
            // gen_ai.usage.input_tokens already includes cached input tokens.
            return inputTokens == 0 ? 0.0 : (double) cacheReadTokens / inputTokens;
        }

        public Map<String, Object> toMap() {
            var out = new LinkedHashMap<String, Object>();
            out.put("trace_files", traceFiles);
            out.put("agent_runs", agentRuns);
            out.put("failed_runs", failedRuns);
            out.put("model_calls", modelCalls);
            out.put("tool_calls", toolCalls);
            out.put("tool_errors", toolErrors);
            out.put("tool_success_rate", toolSuccessRate());
            out.put("retries", retries);
            out.put("compactions", compactions);
            out.put("input_tokens", inputTokens);
            out.put("output_tokens", outputTokens);
            out.put("cache_read_tokens", cacheReadTokens);
            out.put("cache_creation_tokens", cacheCreationTokens);
            out.put("cache_read_ratio", cacheReadRatio());
            out.put("model_latency_p50_ms", modelLatencyP50Ms);
            out.put("model_latency_p95_ms", modelLatencyP95Ms);
            out.put("tool_latency_p50_ms", toolLatencyP50Ms);
            out.put("tool_latency_p95_ms", toolLatencyP95Ms);
            out.put("peak_context_pressure", peakContextPressure);
            out.put("tools", tools);
            out.put("canary_skills", canarySkills);
            out.put("malformed_records", malformedRecords);
            return out;
        }

        public String renderText() {
            return """
                    Agent trace report
                      traces/runs       %d / %d
                      failed runs       %d
                      model calls       %d  (p50 %.1f ms, p95 %.1f ms)
                      tool calls        %d  (success %.1f%%, p50 %.1f ms, p95 %.1f ms)
                      tokens            input %,d / output %,d
                      prompt cache      read %,d / created %,d (read ratio %.1f%%)
                      retries/compacts  %d / %d
                      peak context      %.1f%%
                      tools             %s
                      malformed lines   %d
                    """.formatted(
                    traceFiles, agentRuns, failedRuns,
                    modelCalls, modelLatencyP50Ms, modelLatencyP95Ms,
                    toolCalls, toolSuccessRate() * 100, toolLatencyP50Ms, toolLatencyP95Ms,
                    inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens,
                    cacheReadRatio() * 100, retries, compactions, peakContextPressure * 100,
                    tools.isEmpty() ? "(none)" : String.join(", ", tools), malformedRecords);
        }
    }

    public static Summary analyze(Path path) throws IOException {
        List<Path> files = traceFiles(path);
        int runs = 0, failedRuns = 0, modelCalls = 0, toolCalls = 0, toolErrors = 0;
        int retries = 0, compactions = 0, malformed = 0;
        long input = 0, output = 0, cacheRead = 0, cacheCreation = 0;
        double peakPressure = 0.0;
        var modelLatency = new ArrayList<Double>();
        var toolLatency = new ArrayList<Double>();
        var tools = new LinkedHashSet<String>();
        var canarySkills = new LinkedHashSet<String>();

        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> span = MAPPER.readValue(line, MAP_TYPE);
                    Map<String, Object> attrs = attributes(span);
                    String operation = string(attrs.get("gen_ai.operation.name"));
                    String status = string(span.get("status"));
                    double duration = number(span.get("duration_ms"));
                    switch (operation) {
                        case "invoke_agent" -> {
                            runs++;
                            if ("ERROR".equals(status)) failedRuns++;
                        }
                        case "chat" -> {
                            modelCalls++;
                            modelLatency.add(duration);
                            input += longNumber(attrs.get("gen_ai.usage.input_tokens"));
                            output += longNumber(attrs.get("gen_ai.usage.output_tokens"));
                            cacheRead += longNumber(attrs.get("gen_ai.usage.cache_read.input_tokens"));
                            cacheCreation += longNumber(attrs.get("gen_ai.usage.cache_creation.input_tokens"));
                        }
                        case "execute_tool" -> {
                            toolCalls++;
                            toolLatency.add(duration);
                            if ("ERROR".equals(status)) toolErrors++;
                            String tool = string(attrs.get("gen_ai.tool.name"));
                            if (!tool.isBlank()) tools.add(tool);
                        }
                        case "retry" -> retries++;
                        case "compact_context" -> compactions++;
                        case "context_budget" -> peakPressure = Math.max(peakPressure,
                                number(attrs.get("devmesh.context.pressure")));
                        case "skill_canary" -> {
                            String candidateId = string(attrs.get("devmesh.skill.candidate_id"));
                            if (!candidateId.isBlank()) canarySkills.add(candidateId);
                        }
                        default -> { }
                    }
                } catch (Exception ignored) {
                    malformed++;
                }
            }
        }

        return new Summary(files.size(), runs, failedRuns, modelCalls, toolCalls, toolErrors,
                retries, compactions, input, output, cacheRead, cacheCreation,
                percentile(modelLatency, 0.50), percentile(modelLatency, 0.95),
                percentile(toolLatency, 0.50), percentile(toolLatency, 0.95),
                peakPressure, Set.copyOf(tools), Set.copyOf(canarySkills), malformed);
    }

    private static List<Path> traceFiles(Path path) throws IOException {
        if (!Files.exists(path)) throw new IOException("Trace path does not exist: " + path);
        if (Files.isRegularFile(path)) return List.of(path);
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> span) {
        Object attrs = span.get("attributes");
        return attrs instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return 0.0;
        values.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static long longNumber(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
