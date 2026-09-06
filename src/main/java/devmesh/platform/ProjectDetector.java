package devmesh.platform;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectDetector {
    private ProjectDetector() {}

    public static ProjectType detect(Path root) {
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) return ProjectType.GRADLE;
        if (Files.exists(root.resolve("pom.xml"))) return ProjectType.MAVEN;
        if (Files.exists(root.resolve("package.json"))) return ProjectType.NODE;
        if (Files.exists(root.resolve("Cargo.toml"))) return ProjectType.RUST;
        if (Files.exists(root.resolve("go.mod"))) return ProjectType.GO;
        if (Files.exists(root.resolve("CMakeLists.txt"))) return ProjectType.CMAKE;
        if (Files.exists(root.resolve("Makefile"))) return ProjectType.MAKE;
        return ProjectType.UNKNOWN;
    }

    public static String nodePackageManager(Path root) {
        if (Files.exists(root.resolve("pnpm-lock.yaml"))) return "pnpm";
        if (Files.exists(root.resolve("yarn.lock"))) return "yarn";
        if (Files.exists(root.resolve("bun.lock")) || Files.exists(root.resolve("bun.lockb"))) return "bun";
        return "npm";
    }
}