package devmesh.compact;

public record ContextItem(String key, ContextLayer layer, ContextPriority priority,
                          String content, TokenEstimate estimate, boolean persistent) {
    public ContextItem {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("context key is required");
        content = content == null ? "" : content;
        layer = layer == null ? ContextLayer.TOOL : layer;
        priority = priority == null ? ContextPriority.P3_LOW : priority;
        estimate = estimate == null ? new TokenEstimate(0, TokenEstimate.Confidence.UNKNOWN) : estimate;
    }
}