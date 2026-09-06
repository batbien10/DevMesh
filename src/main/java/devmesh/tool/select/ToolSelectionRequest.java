package devmesh.tool.select;

import devmesh.permission.PermissionChecker;
import devmesh.platform.OperatingSystem;

import java.util.Map;
import java.util.Set;

public record ToolSelectionRequest(ToolIntent intent, Map<String, Object> arguments,
                                   OperatingSystem platform, PermissionChecker permissions,
                                   Set<String> alreadyKnownKeys) {
    public ToolSelectionRequest {
        intent = intent == null ? ToolIntent.UNKNOWN : intent;
        arguments = Map.copyOf(arguments == null ? Map.of() : arguments);
        platform = platform == null ? OperatingSystem.detect() : platform;
        alreadyKnownKeys = Set.copyOf(alreadyKnownKeys == null ? Set.of() : alreadyKnownKeys);
    }
}