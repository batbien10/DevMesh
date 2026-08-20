package devmesh.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillOutcomeEvaluatorTest {

    @Test
    void requiresSamePairedCohortAndRejectsOutcomeRegression(@TempDir Path dir) throws Exception {
        Path baseline = dir.resolve("baseline.jsonl");
        Path candidate = dir.resolve("candidate.jsonl");
        Files.writeString(baseline, """
                {"case_id":"case-a","passed":true,"score":1.0}
                {"case_id":"case-b","passed":true,"score":0.8}
                """);
        Files.writeString(candidate, """
                {"case_id":"case-a","passed":true,"score":1.0}
                {"case_id":"case-b","passed":false,"score":0.2}
                """);
        var policy = new SkillEvolutionPolicy(0, 0, 1.2, 1.5,
                false, 2, 0, 0);

        assertFalse(SkillOutcomeEvaluator.compare(baseline, candidate, policy).passed());

        Files.writeString(candidate, """
                {"case_id":"case-a","passed":true,"score":1.0}
                {"case_id":"case-b","passed":true,"score":0.9}
                """);
        assertTrue(SkillOutcomeEvaluator.compare(baseline, candidate, policy).passed());

        Files.writeString(candidate, """
                {"case_id":"case-a","passed":true,"score":1.0}
                {"case_id":"case-c","passed":true,"score":1.0}
                """);
        assertFalse(SkillOutcomeEvaluator.compare(baseline, candidate, policy).passed());
    }
}
