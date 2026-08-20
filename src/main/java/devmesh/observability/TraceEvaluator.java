package devmesh.observability;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic quality gates over recorded agent trajectories. */
public final class TraceEvaluator {

    private TraceEvaluator() {}

    public record Policy(
            int minAgentRuns,
            int maxFailedRuns,
            int maxToolErrors,
            int maxRetries,
            int maxCompactions,
            int maxMalformedRecords,
            double minToolSuccessRate,
            double maxPeakContextPressure,
            List<String> requiredTools,
            List<String> forbiddenTools
    ) {}

    public record Check(String name, boolean passed, String actual, String expected) {}

    public record Result(boolean passed, List<Check> checks) {
        public Map<String, Object> toMap() {
            var out = new LinkedHashMap<String, Object>();
            out.put("passed", passed);
            out.put("checks", checks);
            return out;
        }

        public String renderText() {
            var text = new StringBuilder(passed ? "TRACE EVAL: PASS\n" : "TRACE EVAL: FAIL\n");
            for (var check : checks) {
                text.append(check.passed() ? "  [PASS] " : "  [FAIL] ")
                        .append(check.name()).append(": actual=").append(check.actual())
                        .append(", expected=").append(check.expected()).append('\n');
            }
            return text.toString();
        }
    }

    @SuppressWarnings("unchecked")
    public static Policy loadPolicy(Path path) throws IOException {
        Object loaded = new Yaml().load(Files.readString(path));
        Map<String, Object> map = loaded instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        return new Policy(
                intValue(map, "min_agent_runs", 1),
                intValue(map, "max_failed_runs", 0),
                intValue(map, "max_tool_errors", 0),
                intValue(map, "max_retries", 3),
                intValue(map, "max_compactions", 3),
                intValue(map, "max_malformed_records", 0),
                doubleValue(map, "min_tool_success_rate", 0.95),
                doubleValue(map, "max_peak_context_pressure", 0.95),
                stringList(map.get("required_tools")),
                stringList(map.get("forbidden_tools"))
        );
    }

    public static Result evaluate(TraceAnalyzer.Summary summary, Policy policy) {
        var checks = new ArrayList<Check>();
        checks.add(minCheck("agent runs", summary.agentRuns(), policy.minAgentRuns()));
        checks.add(maxCheck("failed runs", summary.failedRuns(), policy.maxFailedRuns()));
        checks.add(maxCheck("tool errors", summary.toolErrors(), policy.maxToolErrors()));
        checks.add(maxCheck("retries", summary.retries(), policy.maxRetries()));
        checks.add(maxCheck("compactions", summary.compactions(), policy.maxCompactions()));
        checks.add(maxCheck("malformed records", summary.malformedRecords(), policy.maxMalformedRecords()));
        checks.add(minCheck("tool success rate", summary.toolSuccessRate(), policy.minToolSuccessRate()));
        checks.add(maxCheck("peak context pressure", summary.peakContextPressure(),
                policy.maxPeakContextPressure()));
        for (String required : policy.requiredTools()) {
            checks.add(new Check("required tool " + required, summary.tools().contains(required),
                    Boolean.toString(summary.tools().contains(required)), "true"));
        }
        for (String forbidden : policy.forbiddenTools()) {
            checks.add(new Check("forbidden tool " + forbidden, !summary.tools().contains(forbidden),
                    Boolean.toString(summary.tools().contains(forbidden)), "false"));
        }
        return new Result(checks.stream().allMatch(Check::passed), List.copyOf(checks));
    }

    private static Check maxCheck(String name, double actual, double maximum) {
        return new Check(name, actual <= maximum, format(actual), "<= " + format(maximum));
    }

    private static Check minCheck(String name, double actual, double minimum) {
        return new Check(name, actual >= minimum, format(actual), ">= " + format(minimum));
    }

    private static String format(double value) {
        return Math.rint(value) == value ? Long.toString((long) value) : "%.4f".formatted(value);
    }

    private static int intValue(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static double doubleValue(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(Object::toString).toList();
    }
}
