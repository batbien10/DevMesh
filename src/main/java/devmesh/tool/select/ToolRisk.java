package devmesh.tool.select;

public enum ToolRisk {
    SAFE_READ,
    SAFE_ANALYSIS,
    CONTROLLED_WRITE,
    EXECUTION,
    DESTRUCTIVE_WRITE,
    NETWORK,
    PRIVILEGED
}