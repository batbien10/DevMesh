package devmesh.platform;

import java.util.ArrayList;
import java.util.List;

public final class EnvironmentInspector {
    private EnvironmentInspector() {}

    public static EnvironmentReport inspect() {
        var tools = new ArrayList<String>();
        for (String tool : List.of("java", "git", "gradle", "mvn", "node", "npm", "python", "docker")) {
            if (ShellDetector.firstAvailable(tool, tool + ".cmd", tool + ".exe") != null) tools.add(tool);
        }
        String terminal = System.getenv().getOrDefault("TERM_PROGRAM",
                System.getenv().getOrDefault("TERM", "unknown"));
        return new EnvironmentReport(OperatingSystem.detect(), System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"), ShellDetector.detect(), terminal, tools);
    }
}