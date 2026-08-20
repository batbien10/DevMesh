package devmesh.evolution;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Non-regression limits used to compare a candidate Skill against its baseline. */
public record SkillEvolutionPolicy(
        double maxFailureRateRegression,
        double maxToolErrorRateRegression,
        double maxTokenRatio,
        double maxModelP95LatencyRatio,
        boolean requireStrictImprovement,
        int minPairedOutcomeCases,
        double maxOutcomeFailureRateRegression,
        double maxOutcomeScoreRegression
) {
    public SkillEvolutionPolicy {
        if (maxFailureRateRegression < 0 || maxToolErrorRateRegression < 0
                || maxOutcomeFailureRateRegression < 0 || maxOutcomeScoreRegression < 0) {
            throw new IllegalArgumentException("regression allowances must be non-negative");
        }
        if (maxTokenRatio <= 0 || maxModelP95LatencyRatio <= 0) {
            throw new IllegalArgumentException("cost and latency ratios must be positive");
        }
        if (minPairedOutcomeCases < 1) {
            throw new IllegalArgumentException("min_paired_outcome_cases must be at least 1");
        }
    }

    @SuppressWarnings("unchecked")
    public static SkillEvolutionPolicy load(Path path) throws IOException {
        Object loaded = new Yaml().load(Files.readString(path));
        Map<String, Object> root = loaded instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        Object evolution = root.get("evolution");
        Map<String, Object> map = evolution instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : root;
        return new SkillEvolutionPolicy(
                doubleValue(map, "max_failure_rate_regression", 0.0),
                doubleValue(map, "max_tool_error_rate_regression", 0.0),
                doubleValue(map, "max_token_ratio", 1.20),
                doubleValue(map, "max_model_p95_latency_ratio", 1.50),
                booleanValue(map, "require_strict_improvement", false),
                intValue(map, "min_paired_outcome_cases", 1),
                doubleValue(map, "max_outcome_failure_rate_regression", 0.0),
                doubleValue(map, "max_outcome_score_regression", 0.0));
    }

    private static double doubleValue(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean booleanValue(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static int intValue(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
