package devmesh.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Detects an available shell without assuming bash exists. */
public final class ShellDetector {
    private ShellDetector() {}

    public static Shell detect() {
        OperatingSystem os = OperatingSystem.detect();
        if (os == OperatingSystem.WINDOWS) {
            String powershell = firstAvailable("pwsh", "powershell.exe");
            if (powershell != null) return new Shell("PowerShell", powershell,
                    List.of("-NoProfile", "-NonInteractive", "-Command"));
            return new Shell("cmd", "cmd.exe", List.of("/d", "/s", "/c"));
        }
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank() && Files.isExecutable(Path.of(shell))) {
            return new Shell(Path.of(shell).getFileName().toString(), shell, List.of("-c"));
        }
        for (String candidate : List.of("zsh", "bash", "sh")) {
            String executable = firstAvailable(candidate);
            if (executable != null) return new Shell(candidate, executable, List.of("-c"));
        }
        return new Shell("sh", "sh", List.of("-c"));
    }

    static String firstAvailable(String... names) {
        String path = System.getenv().getOrDefault("PATH", "");
        String separator = OperatingSystem.detect() == OperatingSystem.WINDOWS ? ";" : ":";
        for (String directory : path.split(java.util.regex.Pattern.quote(separator))) {
            for (String name : names) {
                Path candidate = Path.of(directory, name);
                if (Files.isRegularFile(candidate) && (OperatingSystem.detect() == OperatingSystem.WINDOWS
                        || Files.isExecutable(candidate))) return candidate.toString();
                if (OperatingSystem.detect() == OperatingSystem.WINDOWS) {
                    for (String ext : List.of(".exe", ".cmd", ".bat")) {
                        candidate = Path.of(directory, name + ext);
                        if (Files.isRegularFile(candidate)) return candidate.toString();
                    }
                }
            }
        }
        return null;
    }
}