package devmesh.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Converts a project-level intent into a platform-native process command. */
public final class CommandResolver {
    private final Path root;
    private final OperatingSystem os;
    private final ProjectType projectType;

    public CommandResolver(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.os = OperatingSystem.detect();
        this.projectType = ProjectDetector.detect(this.root);
    }

    public OperatingSystem os() { return os; }
    public ProjectType projectType() { return projectType; }

    public CommandSpec resolve(CommandIntent intent) {
        return switch (intent) {
            case BUILD -> projectCommand("build", Duration.ofMinutes(10));
            case TEST -> projectCommand("test", Duration.ofMinutes(10));
            case PACKAGE -> projectCommand("package", Duration.ofMinutes(10));
            case GIT_STATUS -> direct("git", List.of("status", "--short"), Duration.ofMinutes(1));
            case GIT_DIFF -> direct("git", List.of("diff", "--"), Duration.ofMinutes(1));
        };
    }

    private CommandSpec projectCommand(String action, Duration timeout) {
        return switch (projectType) {
            case GRADLE -> wrapper("gradlew", action, timeout, "gradle");
            case MAVEN -> wrapper("mvnw", action, timeout, "mvn");
            case NODE -> direct(executable(ProjectDetector.nodePackageManager(root)), List.of(action.equals("test") ? "test" : "run", action), timeout);
            case RUST -> direct(executable("cargo"), List.of(action.equals("test") ? "test" : "build"), timeout);
            case GO -> direct(executable("go"), List.of(action.equals("test") ? "test" : "build", "./..."), timeout);
            case CMAKE -> direct("cmake", List.of("--build", "build"), timeout);
            case MAKE -> direct("make", List.of(action), timeout);
            case UNKNOWN -> throw new IllegalStateException("Cannot resolve " + action + ": project type is unknown");
        };
    }

    private CommandSpec wrapper(String unixBase, String action, Duration timeout, String global) {
        boolean windows = os == OperatingSystem.WINDOWS;
        Path wrapper = root.resolve(windows ? unixBase + ".bat" : unixBase);
        if (Files.isRegularFile(wrapper)) {
            return direct(wrapper.toString(), List.of(action), timeout);
        }
        return direct(executable(global), List.of(action), timeout);
    }

    private String executable(String base) {
        if (os != OperatingSystem.WINDOWS) return base;
        if (base.endsWith(".bat") || base.endsWith(".cmd") || base.endsWith(".exe")) return base;
        if (base.equals("gradle")) return "gradle.bat";
        if (base.equals("mvn") || base.equals("npm") || base.equals("pnpm") || base.equals("yarn") || base.equals("bun")) {
            return base + ".cmd";
        }
        return base + ".exe";
    }

    private CommandSpec direct(String executable, List<String> args, Duration timeout) {
        return new CommandSpec(executable, args, root, Map.of(), timeout, CommandSpec.Mode.DIRECT);
    }
}