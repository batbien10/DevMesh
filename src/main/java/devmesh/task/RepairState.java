package devmesh.task;

public enum RepairState {
    IDLE,
    FAILED,
    DIAGNOSING,
    REPAIR_PLANNING,
    REPAIRING,
    RETESTING,
    VERIFIED,
    FAILED_FINAL,
    CANCELLED,
    TIMEOUT,
    BLOCKED
}