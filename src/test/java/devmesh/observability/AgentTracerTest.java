package devmesh.observability;

import devmesh.config.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentTracerTest {

    @Test
    void recordsAndAnalyzesContentSafeTrajectory(@TempDir Path dir) throws Exception {
        var provider = new ProviderConfig();
        provider.setProtocol("anthropic");
        provider.setModel("test-model");

        Path traceFile;
        try (var tracer = AgentTracer.create(dir, provider, "session-1", "test-agent")) {
            traceFile = tracer.traceFile();
            tracer.event("context_budget", "context_budget", Map.of(
                    "devmesh.context.pressure", 0.75));
            tracer.record("chat", "chat test-model", "CLIENT", tracer.rootSpanId(),
                    tracer.startTimer(), "OK", Map.of(
                            "gen_ai.usage.input_tokens", 100,
                            "gen_ai.usage.output_tokens", 25,
                            "gen_ai.usage.cache_read.input_tokens", 40,
                            "gen_ai.usage.cache_creation.input_tokens", 10));
            tracer.record("execute_tool", "execute_tool ReadFile", "INTERNAL", tracer.rootSpanId(),
                    tracer.startTimer(), "OK", Map.of(
                            "gen_ai.tool.name", "ReadFile",
                            "devmesh.tool.argument_keys", List.of("file_path")));
        }

        assertTrue(Files.exists(traceFile));
        String raw = Files.readString(traceFile);
        assertTrue(raw.contains("invoke_agent"));
        assertTrue(raw.contains("execute_tool"));
        assertFalse(raw.contains("api_key"));

        var summary = TraceAnalyzer.analyze(traceFile);
        assertEquals(1, summary.agentRuns());
        assertEquals(1, summary.modelCalls());
        assertEquals(1, summary.toolCalls());
        assertEquals(100, summary.inputTokens());
        assertEquals(25, summary.outputTokens());
        assertEquals(0.75, summary.peakContextPressure(), 0.0001);
        assertTrue(summary.tools().contains("ReadFile"));
    }

    @Test
    void evaluatesTrajectoryAgainstYamlPolicy(@TempDir Path dir) throws Exception {
        var summary = new TraceAnalyzer.Summary(
                1, 1, 0, 2, 3, 0, 1, 0,
                100, 30, 20, 0,
                10, 20, 5, 8, 0.80,
                java.util.Set.of("ReadFile", "Grep"), java.util.Set.of(), 0);
        Path policyPath = dir.resolve("policy.yaml");
        Files.writeString(policyPath, """
                min_agent_runs: 1
                max_failed_runs: 0
                max_tool_errors: 0
                max_retries: 2
                max_compactions: 1
                max_malformed_records: 0
                min_tool_success_rate: 0.99
                max_peak_context_pressure: 0.90
                required_tools: [ReadFile]
                forbidden_tools: [WriteFile]
                """);

        var policy = TraceEvaluator.loadPolicy(policyPath);
        var result = TraceEvaluator.evaluate(summary, policy);
        assertTrue(result.passed());
        assertTrue(result.checks().stream().allMatch(TraceEvaluator.Check::passed));

        var empty = new TraceAnalyzer.Summary(
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0, 0,
                java.util.Set.of(), java.util.Set.of(), 0);
        assertFalse(TraceEvaluator.evaluate(empty, policy).passed(),
                "an empty trace directory must never pass a quality gate");
    }
}
