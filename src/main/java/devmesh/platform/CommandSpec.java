package devmesh.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public record CommandSpec(
        String executable,
        List<String> arguments,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        Mode mode
) {
    public enum Mode { DIRECT, SHELL }

    public CommandSpec {
        if (executable == null || executable.isBlank()) throw new IllegalArgumentException("executable is required");
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
        environment = Map.copyOf(environment == null ? Map.of() : environment);
        timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
        mode = mode == null ? Mode.DIRECT : mode;
    }
}