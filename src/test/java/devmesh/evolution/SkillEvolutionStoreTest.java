package devmesh.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SkillEvolutionStoreTest {

    @Test
    void candidateLifecyclePromotesAndRollsBack(@TempDir Path workspace) throws Exception {
        var store = new SkillEvolutionStore(workspace);
        var candidate = store.propose(proposal("safe-refactor"));

        assertEquals(SkillCandidate.Status.QUARANTINED, store.statusOf(candidate.id()));
        assertTrue(Files.readString(store.root().resolve("candidates")
                .resolve(candidate.id()).resolve("SKILL.md")).contains("evolved-skill"));

        Path oldSkill = workspace.resolve(".devmesh/skills/safe-refactor");
        Files.createDirectories(oldSkill);
        Files.writeString(oldSkill.resolve("SKILL.md"), "old stable skill");

        store.recordEvaluation(candidate.id(), true, Map.of("gate", "pass"));
        assertEquals(SkillCandidate.Status.VERIFIED, store.statusOf(candidate.id()));
        Path promoted = store.promote(candidate.id());
        assertEquals(SkillCandidate.Status.PROMOTED, store.statusOf(candidate.id()));
        assertTrue(Files.readString(promoted.resolve("SKILL.md")).contains("safe-refactor"));

        store.rollback(candidate.id());
        assertEquals(SkillCandidate.Status.ROLLED_BACK, store.statusOf(candidate.id()));
        assertEquals("old stable skill", Files.readString(oldSkill.resolve("SKILL.md")));
        assertEquals(List.of(
                        SkillEvolutionEvent.Type.PROPOSED,
                        SkillEvolutionEvent.Type.EVALUATION_PASSED,
                        SkillEvolutionEvent.Type.PROMOTED,
                        SkillEvolutionEvent.Type.ROLLED_BACK),
                store.readEvents().stream().map(SkillEvolutionEvent::type).toList());
    }

    @Test
    void rejectedCandidateCannotBePromoted(@TempDir Path workspace) throws Exception {
        var store = new SkillEvolutionStore(workspace);
        var candidate = store.propose(proposal("guarded-skill"));
        store.recordEvaluation(candidate.id(), false, Map.of("reason", "regression"));

        assertEquals(SkillCandidate.Status.REJECTED, store.statusOf(candidate.id()));
        assertThrows(Exception.class, () -> store.promote(candidate.id()));
    }

    @Test
    void concurrentProposalsReceiveDistinctVersions(@TempDir Path workspace) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> new SkillEvolutionStore(workspace)
                    .propose(proposal("concurrent-skill")));
            var second = executor.submit(() -> new SkillEvolutionStore(workspace)
                    .propose(proposal("concurrent-skill")));
            Set<Integer> versions = Set.of(first.get().version(), second.get().version());
            assertEquals(Set.of(1, 2), versions);
        }
        assertEquals(2, new SkillEvolutionStore(workspace).listViews().size());
    }

    private static SkillEvolutionStore.Proposal proposal(String name) {
        return new SkillEvolutionStore.Proposal(
                name,
                "A verified reusable refactoring workflow",
                "Use for behavior-preserving refactors; do not use for feature changes",
                List.of("java", "refactor"),
                "Read the affected code, identify invariants, make the smallest change, then verify behavior.",
                List.of("Tests may miss externally visible behavior"),
                List.of("Run focused tests", "Run the full test suite"),
                "trace-demo");
    }
}
