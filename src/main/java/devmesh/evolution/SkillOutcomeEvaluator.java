package devmesh.evolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Paired, deterministic outcome evaluation over the same benchmark cases. */
public final class SkillOutcomeEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SkillOutcomeEvaluator() {}

    public record Outcome(String caseId, boolean passed, double score) {}

    public record Summary(int cases, int passed, double passRate,
                          double meanScore, Set<String> caseIds) {
        public Map<String, Object> toMap() {
            var out = new LinkedHashMap<String, Object>();
            out.put("cases", cases);
            out.put("passed", passed);
            out.put("pass_rate", passRate);
            out.put("mean_score", meanScore);
            out.put("case_ids", caseIds);
            return out;
        }
    }

    public record Check(String metric, boolean passed, double baseline,
                        double candidate, String expectation) {}

    public record Result(boolean passed, int pairedCases, Summary baseline,
                         Summary candidate, List<Check> checks) {
        public Map<String, Object> toMap() {
            var out = new LinkedHashMap<String, Object>();
            out.put("passed", passed);
            out.put("paired_cases", pairedCases);
            out.put("baseline", baseline.toMap());
            out.put("candidate", candidate.toMap());
            out.put("checks", checks);
            return out;
        }
    }

    public static Result compare(Path baselinePath, Path candidatePath,
                                 SkillEvolutionPolicy policy) throws IOException {
        Map<String, Outcome> baseline = load(baselinePath);
        Map<String, Outcome> candidate = load(candidatePath);
        var pairedIds = new LinkedHashSet<>(baseline.keySet());
        pairedIds.retainAll(candidate.keySet());

        Summary baselineSummary = summarize(baseline, pairedIds);
        Summary candidateSummary = summarize(candidate, pairedIds);
        var checks = new ArrayList<Check>();
        boolean sameCohort = baseline.keySet().equals(candidate.keySet());
        checks.add(new Check("same_case_cohort", sameCohort,
                baseline.size(), candidate.size(), "baseline and candidate case IDs must match"));
        checks.add(new Check("minimum_paired_cases",
                pairedIds.size() >= policy.minPairedOutcomeCases(),
                policy.minPairedOutcomeCases(), pairedIds.size(),
                "paired cases >= configured minimum"));
        checks.add(new Check("outcome_failure_rate",
                (1.0 - candidateSummary.passRate())
                        <= (1.0 - baselineSummary.passRate())
                        + policy.maxOutcomeFailureRateRegression() + 1e-12,
                1.0 - baselineSummary.passRate(), 1.0 - candidateSummary.passRate(),
                "candidate <= baseline + " + policy.maxOutcomeFailureRateRegression()));
        checks.add(new Check("outcome_mean_score",
                candidateSummary.meanScore() + policy.maxOutcomeScoreRegression() + 1e-12
                        >= baselineSummary.meanScore(),
                baselineSummary.meanScore(), candidateSummary.meanScore(),
                "candidate >= baseline - " + policy.maxOutcomeScoreRegression()));
        return new Result(checks.stream().allMatch(Check::passed), pairedIds.size(),
                baselineSummary, candidateSummary, List.copyOf(checks));
    }

    private static Map<String, Outcome> load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Outcome file does not exist: " + path);
        var outcomes = new LinkedHashMap<String, Outcome>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            if (line.isBlank()) continue;
            try {
                Map<String, Object> value = MAPPER.readValue(line, MAP_TYPE);
                String caseId = String.valueOf(value.getOrDefault("case_id", "")).strip();
                Object passedValue = value.get("passed");
                Object scoreValue = value.get("score");
                if (caseId.isBlank() || !(passedValue instanceof Boolean passed)
                        || !(scoreValue instanceof Number score)) {
                    throw new IllegalArgumentException("case_id, boolean passed, and numeric score are required");
                }
                if (!Double.isFinite(score.doubleValue())) {
                    throw new IllegalArgumentException("score must be finite");
                }
                Outcome previous = outcomes.putIfAbsent(caseId,
                        new Outcome(caseId, passed, score.doubleValue()));
                if (previous != null) throw new IllegalArgumentException("duplicate case_id " + caseId);
            } catch (Exception e) {
                throw new IOException("Malformed outcome at line " + lineNumber + " in " + path
                        + ": " + e.getMessage(), e);
            }
        }
        if (outcomes.isEmpty()) throw new IOException("Outcome file is empty: " + path);
        return outcomes;
    }

    private static Summary summarize(Map<String, Outcome> outcomes, Set<String> pairedIds) {
        int passed = 0;
        double score = 0.0;
        for (String id : pairedIds) {
            Outcome outcome = outcomes.get(id);
            if (outcome.passed()) passed++;
            score += outcome.score();
        }
        int cases = pairedIds.size();
        return new Summary(cases, passed, cases == 0 ? 0.0 : (double) passed / cases,
                cases == 0 ? 0.0 : score / cases, Set.copyOf(pairedIds));
    }
}
