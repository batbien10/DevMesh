package devmesh.toolresult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-conversation-thread decision log for tool-result budgeting.
 *
 * <ul>
 *       {@link ToolResultBudget#apply} at least once. Once present, the
 *       decision (replaced or not) is frozen forever for that id.</li>
 *       that was decided "replace". Subsequent turns re-apply this string
 * </ul>
 *
 */
public final class ContentReplacementState {

    private final Set<String> seenIds = new HashSet<>();

    private final Map<String, String> replacements = new HashMap<>();

    public Set<String> seenIds() {
        return seenIds;
    }

    public Map<String, String> replacements() {
        return replacements;
    }

    /**
     * Produce an independent copy. Used at fork time so the child agent
     * inherits the parent's frozen decisions but does not write back into
     * the parent's collections.
     */
    public ContentReplacementState copy() {
        ContentReplacementState out = new ContentReplacementState();
        out.seenIds.addAll(this.seenIds);
        out.replacements.putAll(this.replacements);
        return out;
    }
}
