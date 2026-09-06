package devmesh.compact;

public enum ContextPriority {
    P0_CRITICAL(0),
    P1_HIGH(1),
    P2_MEDIUM(2),
    P3_LOW(3),
    P4_DISCARDABLE(4);

    private final int rank;
    ContextPriority(int rank) { this.rank = rank; }
    public int rank() { return rank; }
}