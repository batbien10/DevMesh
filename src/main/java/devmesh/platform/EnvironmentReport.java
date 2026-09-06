package devmesh.platform;

import java.util.List;

public record EnvironmentReport(
        OperatingSystem os,
        String osVersion,
        String architecture,
        Shell shell,
        String terminal,
        List<String> availableTools
) {
    public EnvironmentReport {
        availableTools = List.copyOf(availableTools == null ? List.of() : availableTools);
    }
}