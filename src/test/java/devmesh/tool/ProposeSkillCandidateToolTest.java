package devmesh.tool;

import devmesh.evolution.SkillEvolutionStore;
import devmesh.tool.impl.ProposeSkillCandidateTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProposeSkillCandidateToolTest {

    @Test
    void agentCanOnlyCreateQuarantinedCandidate(@TempDir Path workspace) throws Exception {
        var tool = new ProposeSkillCandidateTool(workspace);
        var result = tool.execute(Map.of(
                "name", "learned-review",
                "description", "Review workflow learned from a concrete task",
                "when_to_use", "Use for Java review tasks",
                "instructions", "Read diffs, identify invariants, and verify findings.",
                "validation_checks", List.of("Every finding has file evidence")
        ));

        assertFalse(result.isError(), result.output());
        var views = new SkillEvolutionStore(workspace).listViews();
        assertEquals(1, views.size());
        assertEquals(devmesh.evolution.SkillCandidate.Status.QUARANTINED, views.getFirst().status());
        assertFalse(workspace.resolve(".devmesh/skills/learned-review").toFile().exists(),
                "proposal must never activate a Skill");
    }
}
