package devmesh.evolution;

import devmesh.observability.TraceAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counterfactual A/B comparison over baseline and candidate trajectories. */
public final class SkillFitnessComparator {

    private SkillFitnessComparator() {}

    public record Check(String metric, boolean passed, double baseline,
                        double candidate, String expectation) {}

    public record Result(boolean passed, List<Check> checks) {
        public Map<String, Object> toMap() {
            var out = new LinkedHashMap<String, Object>();
            out.put("passed", passed);
            out.put("checks", checks);
            return out;
        }
    }

    public static Result compare(TraceAnalyzer.Summary baseline,
                                 TraceAnalyzer.Summary candidate,
                                 SkillEvolutionPolicy policy) {
        var checks = new ArrayList<Check>();
        double baselineFailure = rate(baseline.failedRuns(), baseline.agentRuns());
        double candidateFailure = rate(candidate.failedRuns(), candidate.agentRuns());
        checks.add(maxRegression("agent_failure_rate", baselineFailure, candidateFailure,
                policy.maxFailureRateRegression()));

        double baselineToolError = rate(baseline.toolErrors(), baseline.toolCalls());
        double candidateToolError = rate(candidate.toolErrors(), candidate.toolCalls());
        checks.add(maxRegression("tool_error_rate", baselineToolError, candidateToolError,
                policy.maxToolErrorRateRegression()));

        double baselineTokens = perRun(baseline.inputTokens() + baseline.outputTokens(), baseline.agentRuns());
        double candidateTokens = perRun(candidate.inputTokens() + candidate.outputTokens(), candidate.agentRuns());
        checks.add(maxRatio("tokens_per_run", baselineTokens, candidateTokens, policy.maxTokenRatio()));

        checks.add(maxRatio("model_p95_latency_ms", baseline.modelLatencyP95Ms(),
                candidate.modelLatencyP95Ms(), policy.maxModelP95LatencyRatio()));

        if (policy.requireStrictImprovement()) {
            boolean improved = candidateFailure < baselineFailure
                    || candidateToolError < baselineToolError
                    || candidateTokens < baselineTokens
                    || candidate.modelLatencyP95Ms() < baseline.modelLatencyP95Ms();
            checks.add(new Check("strict_improvement", improved, 0, improved ? 1 : 0,
                    "at least one primary metric must improve"));
        }
        return new Result(checks.stream().allMatch(Check::passed), List.copyOf(checks));
    }

    private static Check maxRegression(String name, double baseline, double candidate, double allowed) {
        boolean passed = candidate <= baseline + allowed + 1e-12;
        return new Check(name, passed, baseline, candidate,
                "candidate <= baseline + " + allowed);
    }

    private static Check maxRatio(String name, double baseline, double candidate, double ratio) {
        boolean passed;
        if (baseline == 0.0) {
            passed = candidate == 0.0;
        } else {
            passed = candidate <= baseline * ratio + 1e-12;
        }
        return new Check(name, passed, baseline, candidate,
                "candidate <= baseline * " + ratio);
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double perRun(long value, int runs) {
        return runs == 0 ? 0.0 : (double) value / runs;
    }
}
