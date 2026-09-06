package devmesh.compact;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextControlPlaneTest {
    @Test
    void criticalStateIsRetainedWhenBudgetOverflows() throws Exception {
        var root = Files.createTempDirectory("devmesh-context");
        var plane = new ContextControlPlane(root, "session", 100, 20);
        plane.put(new ContextItem("task", ContextLayer.TASK, ContextPriority.P0_CRITICAL,
                "user task", new TokenEstimate(80, TokenEstimate.Confidence.ESTIMATED), true));
        plane.put(new ContextItem("old-output", ContextLayer.TOOL, ContextPriority.P4_DISCARDABLE,
                "old output", new TokenEstimate(80, TokenEstimate.Confidence.ESTIMATED), false));
        var selected = plane.assemble();
        assertTrue(selected.stream().anyMatch(item -> item.key().equals("task")));
        assertEquals(1, plane.budget().retainedItems());
        assertEquals(1, plane.budget().discardedItems());
    }

    @Test
    void persistentSnapshotSurvivesNewControlPlaneInstance() throws Exception {
        var root = Files.createTempDirectory("devmesh-context");
        var first = new ContextControlPlane(root, "session", 1_000, 100);
        first.setSnapshot(new ContextSnapshot("Fix authentication", "Run tests",
                List.of("Do not change API"), List.of("Inspect repository"), List.of("Run tests"),
                List.of("src/Auth.java"), List.of("AuthService.login"), List.of("compile error repaired"),
                List.of("unit tests pending"), List.of("Keep public API stable"), Map.of("attempt", "1")));
        var restored = new ContextControlPlane(root, "session", 1_000, 100);
        assertEquals("Fix authentication", restored.snapshot().task());
        assertEquals("Run tests", restored.snapshot().currentObjective());
        assertEquals(List.of("src/Auth.java"), restored.snapshot().importantFiles());
        first.markCompaction();
        var afterCompaction = new ContextControlPlane(root, "session", 1_000, 100);
        assertEquals(1, afterCompaction.compactions());
    }

    @Test
    void duplicateKeysReplaceDisposableContextWithoutDuplicatingIt() throws Exception {
        var plane = new ContextControlPlane(Files.createTempDirectory("devmesh-context"), "session", 1_000, 100);
        plane.put(new ContextItem("error", ContextLayer.REPAIR, ContextPriority.P1_HIGH,
                "old", new TokenEstimate(5, TokenEstimate.Confidence.ESTIMATED), false));
        plane.put(new ContextItem("error", ContextLayer.REPAIR, ContextPriority.P1_HIGH,
                "new", new TokenEstimate(5, TokenEstimate.Confidence.ESTIMATED), false));
        assertEquals(1, plane.assemble().size());
        assertEquals("new", plane.assemble().getFirst().content());
    }

        @Test
        void repositoryCacheInvalidatesAfterFileChange() throws Exception {
                var root = Files.createTempDirectory("devmesh-context");
                var file = root.resolve("source.txt");
                Files.writeString(file, "one");
                var cache = new RepositoryContextCache();
                assertEquals("one", cache.read(file));
                assertEquals("one", cache.read(file));
                assertEquals(1, cache.hits());
                Thread.sleep(5);
                Files.writeString(file, "two-two");
                assertEquals("two-two", cache.read(file));
                assertEquals(2, cache.misses());
        }
}