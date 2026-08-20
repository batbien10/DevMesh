package devmesh.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillEvolutionServiceTest {

    @Test
    void verifiesCandidateUsingTraceGateAndCounterfactualBaseline(@TempDir Path workspace) throws Exception {
        Path baseline = workspace.resolve("baseline");
        Path candidateTraces = workspace.resolve("candidate-traces");
        Path baselineOutcomes = workspace.resolve("baseline-outcomes.jsonl");
        Path candidateOutcomes = workspace.resolve("candidate-outcomes.jsonl");
        writeTrace(baseline, 100, 20, 100, null);
        writeOutcomes(baselineOutcomes);
        writeOutcomes(candidateOutcomes);
        Path policy = workspace.resolve("policy.yaml");
        Files.writeString(policy, """
                min_agent_runs: 1
                max_failed_runs: 0
                max_tool_errors: 0
                max_retries: 1
                max_compactions: 1
                max_malformed_records: 0
                min_tool_success_rate: 1.0
                max_peak_context_pressure: 0.9
                required_tools: [ReadFile]
                forbidden_tools: []
                evolution:
                  max_failure_rate_regression: 0.0
                  max_tool_error_rate_regression: 0.0
                  max_token_ratio: 1.1
                  max_model_p95_latency_ratio: 1.1
                  require_strict_improvement: true
                  min_paired_outcome_cases: 2
                  max_outcome_failure_rate_regression: 0.0
                  max_outcome_score_regression: 0.0
                """);

        var service = new SkillEvolutionService(workspace);
        var proposed = service.propose(new SkillEvolutionStore.Proposal(
                "trace-learned-skill", "desc", "when", List.of("test"),
                "Follow the verified workflow.", List.of(), List.of("Run tests"), "trace"));
        writeTrace(candidateTraces, 70, 20, 80, proposed.id());
        var result = service.evaluate(proposed.id(), baseline, candidateTraces,
                baselineOutcomes, candidateOutcomes, policy);

        assertTrue(result.passed());
        assertEquals(SkillCandidate.Status.VERIFIED, service.store().statusOf(proposed.id()));
        assertEquals(2, service.store().readEvents().size());
    }

    @Test
    void rejectsTraceThatDidNotActuallyLoadTheCandidate(@TempDir Path workspace) throws Exception {
        Path baseline = workspace.resolve("baseline");
        Path candidateTraces = workspace.resolve("candidate-traces");
        Path baselineOutcomes = workspace.resolve("baseline-outcomes.jsonl");
        Path candidateOutcomes = workspace.resolve("candidate-outcomes.jsonl");
        writeTrace(baseline, 100, 20, 100, null);
        writeTrace(candidateTraces, 70, 20, 80, "another-candidate");
        writeOutcomes(baselineOutcomes);
        writeOutcomes(candidateOutcomes);
        Path policy = workspace.resolve("policy.yaml");
        Files.writeString(policy, """
                min_agent_runs: 1
                max_failed_runs: 0
                max_tool_errors: 0
                max_retries: 1
                max_compactions: 1
                max_malformed_records: 0
                min_tool_success_rate: 1.0
                max_peak_context_pressure: 0.9
                required_tools: [ReadFile]
                forbidden_tools: []
                evolution:
                  max_failure_rate_regression: 0.0
                  max_tool_error_rate_regression: 0.0
                  max_token_ratio: 1.1
                  max_model_p95_latency_ratio: 1.1
                  require_strict_improvement: true
                  min_paired_outcome_cases: 2
                  max_outcome_failure_rate_regression: 0.0
                  max_outcome_score_regression: 0.0
                """);

        var service = new SkillEvolutionService(workspace);
        var proposed = service.propose(new SkillEvolutionStore.Proposal(
                "provenance-guard", "desc", "when", List.of("test"),
                "Follow the verified workflow.", List.of(), List.of("Run tests"), "trace"));
        var result = service.evaluate(proposed.id(), baseline, candidateTraces,
                baselineOutcomes, candidateOutcomes, policy);

        assertFalse(result.passed());
        assertFalse(result.candidateProvenance());
        assertEquals(SkillCandidate.Status.REJECTED, service.store().statusOf(proposed.id()));
    }

    private static void writeOutcomes(Path path) throws Exception {
        Files.writeString(path, """
                {"case_id":"refactor-01","passed":true,"score":1.0}
                {"case_id":"refactor-02","passed":true,"score":0.9}
                """);
    }

    private static void writeTrace(Path dir, int input, int output, int latency,
                                   String candidateId) throws Exception {
        Files.createDirectories(dir);
        String traceId = "0".repeat(32);
        String root = "0".repeat(16);
        String canary = candidateId == null ? "" : """
                {"trace_id":"%s","span_id":"canary","parent_span_id":"%s","name":"skill_canary","kind":"INTERNAL","duration_ms":0,"status":"OK","attributes":{"gen_ai.operation.name":"skill_canary","devmesh.skill.candidate_id":"%s"}}
                """.formatted(traceId, root, candidateId);
        String content = canary + """
                {"trace_id":"%s","span_id":"1","parent_span_id":"%s","name":"context_budget","kind":"INTERNAL","duration_ms":0,"status":"OK","attributes":{"gen_ai.operation.name":"context_budget","devmesh.context.pressure":0.5}}
                {"trace_id":"%s","span_id":"2","parent_span_id":"%s","name":"chat demo","kind":"CLIENT","duration_ms":%d,"status":"OK","attributes":{"gen_ai.operation.name":"chat","gen_ai.usage.input_tokens":%d,"gen_ai.usage.output_tokens":%d}}
                {"trace_id":"%s","span_id":"3","parent_span_id":"%s","name":"execute_tool ReadFile","kind":"INTERNAL","duration_ms":5,"status":"OK","attributes":{"gen_ai.operation.name":"execute_tool","gen_ai.tool.name":"ReadFile"}}
                {"trace_id":"%s","span_id":"%s","name":"invoke_agent demo","kind":"INTERNAL","duration_ms":%d,"status":"OK","attributes":{"gen_ai.operation.name":"invoke_agent"}}
                """.formatted(traceId, root, traceId, root, latency, input, output,
                traceId, root, traceId, root, latency + 10);
        Files.writeString(dir.resolve("trace.jsonl"), content);
    }
}
