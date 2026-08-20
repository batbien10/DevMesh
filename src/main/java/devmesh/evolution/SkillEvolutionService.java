package devmesh.evolution;

import devmesh.observability.TraceAnalyzer;
import devmesh.observability.TraceEvaluator;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Orchestrates proposal, trace-gated A/B evaluation, promotion and rollback. */
public final class SkillEvolutionService {

    private final SkillEvolutionStore store;

    public record EvaluationResult(
            String candidateId,
            boolean passed,
            TraceAnalyzer.Summary baseline,
            TraceAnalyzer.Summary candidate,
            boolean baselineSufficient,
            boolean candidateProvenance,
            TraceEvaluator.Result traceGate,
            SkillOutcomeEvaluator.Result outcomeGate,
            SkillFitnessComparator.Result nonRegression
    ) {
        public Map<String, Object> toMap() {
            var out = new LinkedHashMap<String, Object>();
            out.put("candidate_id", candidateId);
            out.put("passed", passed);
            out.put("baseline", baseline.toMap());
            out.put("candidate", candidate.toMap());
            out.put("baseline_sufficient", baselineSufficient);
            out.put("candidate_provenance", candidateProvenance);
            out.put("trace_gate", traceGate.toMap());
            out.put("outcome_gate", outcomeGate.toMap());
            out.put("non_regression", nonRegression.toMap());
            return out;
        }

        public String renderText() {
            var text = new StringBuilder();
            text.append(passed ? "SKILL EVOLUTION EVAL: PASS\n" : "SKILL EVOLUTION EVAL: FAIL\n")
                    .append("  candidate: ").append(candidateId).append('\n')
                    .append("  baseline evidence: ").append(baselineSufficient ? "PASS" : "FAIL").append('\n')
                    .append("  candidate provenance: ").append(candidateProvenance ? "PASS" : "FAIL").append('\n')
                    .append("  trace gate: ").append(traceGate.passed() ? "PASS" : "FAIL").append('\n')
                    .append("  paired outcome gate: ").append(outcomeGate.passed() ? "PASS" : "FAIL")
                    .append(" (").append(outcomeGate.pairedCases()).append(" cases)\n")
                    .append("  non-regression: ").append(nonRegression.passed() ? "PASS" : "FAIL").append('\n');
            for (var check : nonRegression.checks()) {
                text.append(check.passed() ? "    [PASS] " : "    [FAIL] ")
                        .append(check.metric())
                        .append(" baseline=").append("%.4f".formatted(check.baseline()))
                        .append(" candidate=").append("%.4f".formatted(check.candidate()))
                        .append(" (").append(check.expectation()).append(")\n");
            }
            return text.toString();
        }
    }

    public SkillEvolutionService(Path workspace) {
        this.store = new SkillEvolutionStore(workspace);
    }

    public SkillEvolutionStore store() {
        return store;
    }

    public SkillCandidate propose(SkillEvolutionStore.Proposal proposal) throws IOException {
        return store.propose(proposal);
    }

    public EvaluationResult evaluate(String candidateId, Path baselineTraces,
                                     Path candidateTraces, Path baselineOutcomes,
                                     Path candidateOutcomes, Path policyPath) throws IOException {
        SkillCandidate.Status status = store.statusOf(candidateId);
        if (status == SkillCandidate.Status.PROMOTED || status == SkillCandidate.Status.ROLLED_BACK) {
            throw new IOException("Cannot evaluate candidate in status " + status);
        }
        TraceAnalyzer.Summary baseline = TraceAnalyzer.analyze(baselineTraces);
        TraceAnalyzer.Summary candidate = TraceAnalyzer.analyze(candidateTraces);
        TraceEvaluator.Policy tracePolicy = TraceEvaluator.loadPolicy(policyPath);
        SkillEvolutionPolicy evolutionPolicy = SkillEvolutionPolicy.load(policyPath);
        TraceEvaluator.Result traceGate = TraceEvaluator.evaluate(candidate, tracePolicy);
        boolean baselineSufficient = baseline.agentRuns() >= tracePolicy.minAgentRuns()
                && baseline.malformedRecords() <= tracePolicy.maxMalformedRecords()
                && !baseline.canarySkills().contains(candidateId);
        boolean candidateProvenance = candidate.canarySkills().contains(candidateId);
        SkillOutcomeEvaluator.Result outcomeGate = SkillOutcomeEvaluator.compare(
                baselineOutcomes, candidateOutcomes, evolutionPolicy);
        SkillFitnessComparator.Result nonRegression = SkillFitnessComparator.compare(
                baseline, candidate, evolutionPolicy);
        boolean passed = baselineSufficient && candidateProvenance
                && traceGate.passed() && outcomeGate.passed() && nonRegression.passed();

        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("evaluated_at", Instant.now().toString());
        evidence.put("baseline_trace", baselineTraces.toAbsolutePath().normalize().toString());
        evidence.put("candidate_trace", candidateTraces.toAbsolutePath().normalize().toString());
        evidence.put("baseline_outcomes", baselineOutcomes.toAbsolutePath().normalize().toString());
        evidence.put("candidate_outcomes", candidateOutcomes.toAbsolutePath().normalize().toString());
        evidence.put("baseline_outcomes_hash", sha256(Files.readString(baselineOutcomes)));
        evidence.put("candidate_outcomes_hash", sha256(Files.readString(candidateOutcomes)));
        evidence.put("policy_path", policyPath.toAbsolutePath().normalize().toString());
        evidence.put("policy_hash", sha256(Files.readString(policyPath)));
        evidence.put("trace_gate", traceGate.toMap());
        evidence.put("baseline_sufficient", baselineSufficient);
        evidence.put("candidate_provenance", candidateProvenance);
        evidence.put("outcome_gate", outcomeGate.toMap());
        evidence.put("non_regression", nonRegression.toMap());
        evidence.put("baseline_summary", baseline.toMap());
        evidence.put("candidate_summary", candidate.toMap());
        store.recordEvaluation(candidateId, passed, evidence);
        return new EvaluationResult(candidateId, passed, baseline, candidate,
                baselineSufficient, candidateProvenance, traceGate, outcomeGate, nonRegression);
    }

    @SuppressWarnings("unchecked")
    public static SkillEvolutionStore.Proposal loadProposal(Path path) throws IOException {
        Object loaded = new Yaml().load(Files.readString(path));
        Map<String, Object> map = loaded instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        return new SkillEvolutionStore.Proposal(
                string(map, "name"),
                string(map, "description"),
                string(map, "when_to_use"),
                stringList(map.get("tags")),
                string(map, "instructions"),
                stringList(map.get("failure_modes")),
                stringList(map.get("validation_checks")),
                string(map, "source_trace"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(Object::toString).toList();
    }
}
