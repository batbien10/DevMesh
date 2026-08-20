package devmesh.evolution;

import devmesh.observability.TraceAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillFitnessComparatorTest {

    @Test
    void acceptsNonRegressingCandidateAndRejectsCostExplosion() {
        var baseline = summary(10, 1, 20, 1, 10_000, 1_000, 1_000);
        var better = summary(10, 0, 20, 1, 8_000, 900, 900);
        var policy = new SkillEvolutionPolicy(0.0, 0.0, 1.2, 1.5,
                true, 1, 0.0, 0.0);

        assertTrue(SkillFitnessComparator.compare(baseline, better, policy).passed());

        var expensive = summary(10, 0, 20, 1, 20_000, 900, 900);
        var result = SkillFitnessComparator.compare(baseline, expensive, policy);
        assertFalse(result.passed());
        assertTrue(result.checks().stream()
                .anyMatch(c -> c.metric().equals("tokens_per_run") && !c.passed()));
    }

    private static TraceAnalyzer.Summary summary(int runs, int failedRuns,
                                                  int toolCalls, int toolErrors,
                                                  long inputTokens, long outputTokens,
                                                  double p95) {
        return new TraceAnalyzer.Summary(
                runs, runs, failedRuns, runs, toolCalls, toolErrors, 0, 0,
                inputTokens, outputTokens, 0, 0,
                p95 / 2, p95, 5, 10, 0.5,
                Set.of("ReadFile"), Set.of(), 0);
    }
}
