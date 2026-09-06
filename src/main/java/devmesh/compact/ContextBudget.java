package devmesh.compact;

public record ContextBudget(int window, int used, int reserved, int remaining,
                            int retainedItems, int discardedItems, int compactions) {}