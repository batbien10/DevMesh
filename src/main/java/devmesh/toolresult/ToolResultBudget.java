package devmesh.toolresult;

import devmesh.conversation.ConversationManager;
import devmesh.conversation.Message;
import devmesh.conversation.ToolResultBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 */
public final class ToolResultBudget {

    /** Per-result spill threshold. */
    public static final int SINGLE_RESULT_LIMIT = 50_000;

    /** Per-message aggregate spill threshold. */
    public static final int MESSAGE_AGGREGATE_LIMIT = 200_000;

    /** Tool-results spill subdirectory relative to sessionDir. */
    public static final String SPILL_SUBDIR = "tool_results";

    private static final String PERSISTED_TAG_PREFIX = "[Result of ";
    private ToolResultBudget() {}

    private static boolean isSpillReadback(ToolResultBlock tr, Map<String, devmesh.conversation.ToolUseBlock> toolUseIndex, String absSpillDir) {
        var tu = toolUseIndex.get(tr.toolUseId());
        if (tu == null || !"ReadFile".equals(tu.toolName()) || absSpillDir.isEmpty()) return false;
        Object raw = tu.arguments().get("file_path");
        if (!(raw instanceof String path) || path.isEmpty()) return false;
        try {
            String abs = Path.of(path).toAbsolutePath().normalize().toString();
            return abs.startsWith(absSpillDir);
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, devmesh.conversation.ToolUseBlock> buildToolUseIndex(List<Message> messages) {
        Map<String, devmesh.conversation.ToolUseBlock> idx = new HashMap<>();
        for (Message m : messages) {
            if (m.getToolUses() != null) {
                for (var tu : m.getToolUses()) {
                    idx.put(tu.toolUseId(), tu);
                }
            }
        }
        return idx;
    }

    /**
     *
     */
    public static List<ContentReplacementRecord> apply(
            ConversationManager conv,
            Path sessionDir,
            ContentReplacementState state
    ) {
        List<Message> messages = conv.getMessages();
        if (messages.isEmpty()) {
            return List.of();
        }

        Path spillDir = sessionDir.resolve(SPILL_SUBDIR);
        String absSpillDir = spillDir.toAbsolutePath().normalize().toString();
        Map<String, devmesh.conversation.ToolUseBlock> toolUseIndex = buildToolUseIndex(messages);
        List<ContentReplacementRecord> records = new ArrayList<>();

        for (Message msg : messages) {
            List<ToolResultBlock> trs = msg.getToolResults();
            if (trs == null || trs.isEmpty()) {
                continue;
            }

            Map<String, String> decisions = new HashMap<>(trs.size() * 2);
            List<ToolResultBlock> fresh = new ArrayList<>();

            for (ToolResultBlock tr : trs) {
                String id = tr.toolUseId();
                String existing = state.replacements().get(id);
                if (existing != null) {
                    decisions.put(id, existing);
                    continue;
                }
                if (state.seenIds().contains(id)) {
                    decisions.put(id, tr.content());
                    continue;
                }
                if (isAlreadyReplaced(tr.content())) {

                    state.seenIds().add(id);
                    state.replacements().put(id, tr.content());
                    decisions.put(id, tr.content());
                    records.add(ContentReplacementRecord.toolResult(id, tr.content()));
                    continue;
                }
                fresh.add(tr);
            }

            // Pass 1: persist any single result above SINGLE_RESULT_LIMIT.
            Set<String> persistedByP1 = new HashSet<>();
            for (ToolResultBlock tr : fresh) {
                if (tr.content().length() <= SINGLE_RESULT_LIMIT) continue;
                if (isSpillReadback(tr, toolUseIndex, absSpillDir)) {
                    persistedByP1.add(tr.toolUseId());
                    continue;
                }
                String preview = spillAndPreview(spillDir, tr);
                if (preview == null) {

                    state.seenIds().add(tr.toolUseId());
                    decisions.put(tr.toolUseId(), tr.content());
                    persistedByP1.add(tr.toolUseId());
                    continue;
                }
                decisions.put(tr.toolUseId(), preview);
                state.seenIds().add(tr.toolUseId());
                state.replacements().put(tr.toolUseId(), preview);
                records.add(ContentReplacementRecord.toolResult(tr.toolUseId(), preview));
                persistedByP1.add(tr.toolUseId());
            }


            List<ToolResultBlock> remaining = new ArrayList<>();
            for (ToolResultBlock tr : fresh) {
                if (!persistedByP1.contains(tr.toolUseId())) {
                    remaining.add(tr);
                }
            }

            int total = 0;
            for (String content : decisions.values()) total += content.length();
            for (ToolResultBlock tr : remaining) total += tr.content().length();

            if (total > MESSAGE_AGGREGATE_LIMIT && !remaining.isEmpty()) {
                List<ToolResultBlock> sorted = new ArrayList<>(remaining);
                sorted.sort(Comparator.comparingInt((ToolResultBlock t) -> t.content().length()).reversed());
                for (ToolResultBlock tr : sorted) {
                    if (total <= MESSAGE_AGGREGATE_LIMIT) break;
                    if (isSpillReadback(tr, toolUseIndex, absSpillDir)) continue;
                    String preview = spillAndPreview(spillDir, tr);
                    if (preview == null) {
                        state.seenIds().add(tr.toolUseId());
                        decisions.put(tr.toolUseId(), tr.content());
                        continue;
                    }
                    decisions.put(tr.toolUseId(), preview);
                    state.seenIds().add(tr.toolUseId());
                    state.replacements().put(tr.toolUseId(), preview);
                    records.add(ContentReplacementRecord.toolResult(tr.toolUseId(), preview));
                    total -= tr.content().length() - preview.length();
                }
            }

            // Freeze the remaining fresh results as "seen but not replaced".
            for (ToolResultBlock tr : fresh) {
                if (decisions.containsKey(tr.toolUseId())) continue;
                state.seenIds().add(tr.toolUseId());
                decisions.put(tr.toolUseId(), tr.content());
            }


            List<ToolResultBlock> newResults = new ArrayList<>(trs.size());
            for (ToolResultBlock tr : trs) {
                String decided = decisions.get(tr.toolUseId());
                if (decided != null && !decided.equals(tr.content())) {
                    newResults.add(new ToolResultBlock(tr.toolUseId(), decided, tr.isError()));
                } else {
                    newResults.add(tr);
                }
            }
            msg.setToolResults(newResults);
        }

        return records;
    }

    private static final int PREVIEW_CHARS = 2_000;

    private static String buildSpillPreview(String content, Path path) {
        int sizeKB = content.length() / 1024;
        String preview = content.length() <= PREVIEW_CHARS
                ? content : content.substring(0, PREVIEW_CHARS);
        boolean hasMore = content.length() > PREVIEW_CHARS;
        StringBuilder sb = new StringBuilder();
        sb.append("<persisted-output>\n");
        sb.append("Output too large (").append(sizeKB).append("KB); the full content was saved to:\n");
        sb.append(path).append("\n\n");
        sb.append("Preview (first 2KB):\n").append(preview);
        if (hasMore) sb.append("\n...");
        sb.append("\n</persisted-output>");
        return sb.toString();
    }

    private static String spillAndPreview(Path spillDir, ToolResultBlock tr) {
        try {
            Files.createDirectories(spillDir);
            Path file = spillDir.resolve(tr.toolUseId());
            if (Files.exists(file) && Files.size(file) == tr.content().length()) {
                return buildSpillPreview(tr.content(), file);
            }
            Files.writeString(file, tr.content());
            return buildSpillPreview(tr.content(), file);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isAlreadyReplaced(String s) {
        return s != null && s.startsWith(PERSISTED_TAG_PREFIX);
    }

}
