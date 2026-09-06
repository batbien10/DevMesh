package devmesh.compact;

import java.util.List;
import java.util.Map;

/** Persistent execution facts that compaction must not discard. */
public record ContextSnapshot(
        String task,
        String currentObjective,
        List<String> constraints,
        List<String> completedObjectives,
        List<String> pendingObjectives,
        List<String> importantFiles,
        List<String> importantSymbols,
        List<String> repairHistory,
        List<String> verificationEvidence,
        List<String> decisions,
        Map<String, String> metadata
) {
    public ContextSnapshot {
        constraints = List.copyOf(constraints == null ? List.of() : constraints);
        completedObjectives = List.copyOf(completedObjectives == null ? List.of() : completedObjectives);
        pendingObjectives = List.copyOf(pendingObjectives == null ? List.of() : pendingObjectives);
        importantFiles = List.copyOf(importantFiles == null ? List.of() : importantFiles);
        importantSymbols = List.copyOf(importantSymbols == null ? List.of() : importantSymbols);
        repairHistory = List.copyOf(repairHistory == null ? List.of() : repairHistory);
        verificationEvidence = List.copyOf(verificationEvidence == null ? List.of() : verificationEvidence);
        decisions = List.copyOf(decisions == null ? List.of() : decisions);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public static ContextSnapshot empty() {
        return new ContextSnapshot("", "", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), Map.of());
    }
}