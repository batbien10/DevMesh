package devmesh.sandbox;

import java.util.List;

/**
 */
public class SandboxConfig {

    private final List<String> allowWrite;
    private final List<String> denyWrite;
    private final boolean networkEnabled;

    public SandboxConfig(List<String> allowWrite, List<String> denyWrite, boolean networkEnabled) {
        this.allowWrite = allowWrite != null ? List.copyOf(allowWrite) : List.of();
        this.denyWrite = denyWrite != null ? List.copyOf(denyWrite) : List.of();
        this.networkEnabled = networkEnabled;
    }

    public List<String> getAllowWrite() { return allowWrite; }
    public List<String> getDenyWrite() { return denyWrite; }
    public boolean isNetworkEnabled() { return networkEnabled; }
}
