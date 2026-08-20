package devmesh.tool.impl;

import devmesh.evolution.SkillEvolutionStore;
import devmesh.tool.Tool;
import devmesh.tool.ToolCategory;
import devmesh.tool.ToolResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Lets an Agent distill reusable procedural knowledge without activating it. */
public final class ProposeSkillCandidateTool implements Tool {

    private final SkillEvolutionStore store;

    public ProposeSkillCandidateTool(Path workspace) {
        this.store = new SkillEvolutionStore(workspace);
    }

    @Override
    public String name() {
        return "ProposeSkillCandidate";
    }

    @Override
    public String description() {
        return "Propose a reusable Skill learned from concrete task experience. Use only when a workflow, "
                + "failure pattern, or validation procedure is likely to recur. The proposal is written to "
                + "a quarantine store and is NOT activated automatically. It must pass baseline-vs-candidate "
                + "trace evaluation and explicit promotion before any Agent can load it.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string",
                                        "description", "Lowercase kebab-case Skill name"),
                                "description", Map.of("type", "string",
                                        "description", "What reusable capability this Skill provides"),
                                "when_to_use", Map.of("type", "string",
                                        "description", "Specific trigger and preconditions; also state when not to use it"),
                                "tags", Map.of("type", "array", "items", Map.of("type", "string")),
                                "instructions", Map.of("type", "string",
                                        "description", "Generalized procedure, not a copy of task-specific values"),
                                "failure_modes", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Observed ways this procedure can fail"),
                                "validation_checks", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Deterministic checks required before declaring success"),
                                "source_trace", Map.of("type", "string",
                                        "description", "Optional trace or session identifier for provenance")
                        ),
                        "required", List.of("name", "description", "when_to_use", "instructions",
                                "validation_checks")
                ));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            var proposal = new SkillEvolutionStore.Proposal(
                    string(args, "name"), string(args, "description"),
                    string(args, "when_to_use"), strings(args.get("tags")),
                    string(args, "instructions"), strings(args.get("failure_modes")),
                    strings(args.get("validation_checks")), string(args, "source_trace"));
            var candidate = store.propose(proposal);
            return ToolResult.success("Proposed Skill candidate '%s' (version %d). Status: QUARANTINED. "
                    .formatted(candidate.id(), candidate.version())
                    + "It is not active. Collect baseline and candidate traces, run Skill evolution evaluation, "
                    + "then explicitly promote it if all gates pass.");
        } catch (Exception e) {
            return ToolResult.error("Skill candidate proposal failed: " + e.getMessage());
        }
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : value.toString();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(Object::toString).toList();
    }
}
