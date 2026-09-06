package devmesh.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Priority-aware state and working-context assembly layered on top of the
 * existing ContextCompactor. Persistent execution facts are stored separately
 * from disposable context items and can be reloaded after compaction/restart.
 */
public final class ContextControlPlane {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path statePath;
    private final int contextWindow;
    private final int outputReserve;
    private final Map<String, ContextItem> items = new LinkedHashMap<>();
    private ContextSnapshot snapshot;
    private int compactions;

    public ContextControlPlane(Path workDir, String sessionId, int contextWindow, int outputReserve) {
        this.contextWindow = Math.max(1, contextWindow);
        this.outputReserve = Math.max(0, outputReserve);
        String id = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        this.statePath = workDir.resolve(".devmesh/context/" + id + ".json");
        this.snapshot = loadSnapshot();
        this.compactions = parseCompactions(snapshot.metadata());
    }

    public synchronized void put(ContextItem item) { items.put(item.key(), item); }
    public synchronized void remove(String key) { items.remove(key); }
    public synchronized ContextSnapshot snapshot() { return snapshot; }
    public synchronized void setSnapshot(ContextSnapshot value) { snapshot = value == null ? ContextSnapshot.empty() : value; persist(); }
    public synchronized int compactions() { return compactions; }

    public synchronized List<ContextItem> assemble() {
        int budget = Math.max(1, contextWindow - outputReserve);
        int used = 0;
        var selected = new ArrayList<ContextItem>();
        var ordered = items.values().stream()
                .sorted(Comparator.comparing((ContextItem item) -> item.priority().rank())
                        .thenComparing(ContextItem::key))
                .toList();
        for (ContextItem item : ordered) {
            int estimate = Math.max(0, item.estimate().tokens());
            if (used + estimate <= budget || item.priority() == ContextPriority.P0_CRITICAL) {
                selected.add(item);
                used += estimate;
            }
        }
        return List.copyOf(selected);
    }

    public synchronized ContextBudget budget() {
        int used = assemble().stream().mapToInt(item -> item.estimate().tokens()).sum();
        return new ContextBudget(contextWindow, used, outputReserve,
                Math.max(0, contextWindow - outputReserve - used), assemble().size(),
                Math.max(0, items.size() - assemble().size()), compactions);
    }

    public synchronized String renderPersistentState() {
        var sb = new StringBuilder("## Persistent execution state\n");
        append(sb, "Task", snapshot.task());
        append(sb, "Current objective", snapshot.currentObjective());
        appendList(sb, "Constraints", snapshot.constraints());
        appendList(sb, "Completed", snapshot.completedObjectives());
        appendList(sb, "Pending", snapshot.pendingObjectives());
        appendList(sb, "Important files", snapshot.importantFiles());
        appendList(sb, "Important symbols", snapshot.importantSymbols());
        appendList(sb, "Repair history", snapshot.repairHistory());
        appendList(sb, "Verification", snapshot.verificationEvidence());
        appendList(sb, "Decisions", snapshot.decisions());
        return sb.toString();
    }

    public synchronized void markCompaction() {
        compactions++;
        var metadata = new LinkedHashMap<>(snapshot.metadata());
        metadata.put("compactions", Integer.toString(compactions));
        snapshot = new ContextSnapshot(snapshot.task(), snapshot.currentObjective(), snapshot.constraints(),
                snapshot.completedObjectives(), snapshot.pendingObjectives(), snapshot.importantFiles(),
                snapshot.importantSymbols(), snapshot.repairHistory(), snapshot.verificationEvidence(),
                snapshot.decisions(), metadata);
        persist();
    }

    private static int parseCompactions(Map<String, String> metadata) {
        try {
            return Integer.parseInt(metadata.getOrDefault("compactions", "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void persist() {
        try {
            Files.createDirectories(statePath.getParent());
            MAPPER.writeValue(statePath.toFile(), snapshot);
        } catch (IOException ignored) {
            // Context persistence is best effort; the active in-memory state remains authoritative.
        }
    }

    private ContextSnapshot loadSnapshot() {
        try {
            if (Files.exists(statePath)) return MAPPER.readValue(statePath.toFile(), ContextSnapshot.class);
        } catch (IOException ignored) {}
        return ContextSnapshot.empty();
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) sb.append(name).append(": ").append(value).append('\n');
    }

    private static void appendList(StringBuilder sb, String name, List<String> values) {
        if (!values.isEmpty()) sb.append(name).append(": ").append(String.join("; ", values)).append('\n');
    }
}