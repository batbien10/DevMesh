package devmesh.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillEvolutionCliTest {

    @Test
    void runsOfflineLifecycleThroughCli(@TempDir Path workspace) throws Exception {
        Path proposal = workspace.resolve("proposal.yaml");
        Files.writeString(proposal, """
                name: cli-evolved-skill
                description: Skill used to verify the offline CLI lifecycle
                when_to_use: Use only in the deterministic CLI test
                tags: [test]
                instructions: Follow the benchmark procedure and run validation.
                failure_modes: [A benchmark can be too small]
                validation_checks: [Run the deterministic checks]
                source_trace: cli-test
                """);
        assertEquals(0, SkillEvolutionCli.tryRun(new String[]{
                "--skill-evolution-propose", proposal.toString(),
                "--workspace", workspace.toString()
        }));

        var store = new SkillEvolutionStore(workspace);
        String candidateId = store.listViews().getFirst().candidate().id();
        Path baselineTraces = workspace.resolve("baseline-traces");
        Path candidateTraces = workspace.resolve("candidate-traces");
        writeTrace(baselineTraces, 100, 100, null);
        writeTrace(candidateTraces, 70, 80, candidateId);
        Path baselineOutcomes = workspace.resolve("baseline-outcomes.jsonl");
        Path candidateOutcomes = workspace.resolve("candidate-outcomes.jsonl");
        String outcomes = """
                {"case_id":"cli-01","passed":true,"score":1.0}
                {"case_id":"cli-02","passed":true,"score":0.9}
                """;
        Files.writeString(baselineOutcomes, outcomes);
        Files.writeString(candidateOutcomes, outcomes);
        Path policy = workspace.resolve("policy.yaml");
        Files.writeString(policy, """
                min_agent_runs: 1
                max_failed_runs: 0
                max_tool_errors: 0
                max_retries: 0
                max_compactions: 0
                max_malformed_records: 0
                min_tool_success_rate: 1.0
                max_peak_context_pressure: 0.9
                required_tools: [ReadFile]
                forbidden_tools: []
                evolution:
                  max_failure_rate_regression: 0.0
                  max_tool_error_rate_regression: 0.0
                  max_token_ratio: 1.0
                  max_model_p95_latency_ratio: 1.0
                  require_strict_improvement: true
                  min_paired_outcome_cases: 2
                  max_outcome_failure_rate_regression: 0.0
                  max_outcome_score_regression: 0.0
                """);

        assertEquals(0, SkillEvolutionCli.tryRun(new String[]{
                "--skill-evolution-evaluate", candidateId,
                "--baseline-traces", baselineTraces.toString(),
                "--candidate-traces", candidateTraces.toString(),
                "--baseline-outcomes", baselineOutcomes.toString(),
                "--candidate-outcomes", candidateOutcomes.toString(),
                "--evolution-policy", policy.toString(),
                "--workspace", workspace.toString()
        }));
        assertEquals(SkillCandidate.Status.VERIFIED, store.statusOf(candidateId));

        assertEquals(0, SkillEvolutionCli.tryRun(new String[]{
                "--skill-evolution-promote", candidateId,
                "--workspace", workspace.toString()
        }));
        Path activeSkill = workspace.resolve(".devmesh/skills/cli-evolved-skill/SKILL.md");
        assertTrue(Files.isRegularFile(activeSkill));

        assertEquals(0, SkillEvolutionCli.tryRun(new String[]{
                "--skill-evolution-rollback", candidateId,
                "--workspace", workspace.toString()
        }));
        assertFalse(Files.exists(activeSkill));
        assertEquals(SkillCandidate.Status.ROLLED_BACK, store.statusOf(candidateId));
    }

    private static void writeTrace(Path dir, int inputTokens, int latency,
                                   String candidateId) throws Exception {
        Files.createDirectories(dir);
        String traceId = "a".repeat(32);
        String root = "b".repeat(16);
        String canary = candidateId == null ? "" : """
                {"trace_id":"%s","span_id":"canary","parent_span_id":"%s","name":"skill_canary","kind":"INTERNAL","duration_ms":0,"status":"OK","attributes":{"gen_ai.operation.name":"skill_canary","devmesh.skill.candidate_id":"%s"}}
                """.formatted(traceId, root, candidateId);
        String trace = canary + """
                {"trace_id":"%s","span_id":"chat","parent_span_id":"%s","name":"chat demo","kind":"CLIENT","duration_ms":%d,"status":"OK","attributes":{"gen_ai.operation.name":"chat","gen_ai.usage.input_tokens":%d,"gen_ai.usage.output_tokens":10}}
                {"trace_id":"%s","span_id":"tool","parent_span_id":"%s","name":"execute_tool ReadFile","kind":"INTERNAL","duration_ms":5,"status":"OK","attributes":{"gen_ai.operation.name":"execute_tool","gen_ai.tool.name":"ReadFile"}}
                {"trace_id":"%s","span_id":"%s","name":"invoke_agent demo","kind":"INTERNAL","duration_ms":%d,"status":"OK","attributes":{"gen_ai.operation.name":"invoke_agent"}}
                """.formatted(traceId, root, latency, inputTokens,
                traceId, root, traceId, root, latency + 10);
        Files.writeString(dir.resolve("trace.jsonl"), trace);
    }
}
