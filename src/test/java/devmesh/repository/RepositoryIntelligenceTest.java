package devmesh.repository;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryIntelligenceTest {
    @Test
    void mapsGradleJavaRepositoryStructureAndSymbols() throws Exception {
        Path root = Files.createTempDirectory("devmesh-repo");
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\":core\", \":cli\")");
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }");
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.createDirectories(root.resolve("src/test/java/demo"));
        Files.writeString(root.resolve("src/main/java/demo/Service.java"),
                "package demo;\npublic class Service {\n public String run() { return \"ok\"; }\n}");
        Files.writeString(root.resolve("src/test/java/demo/ServiceTest.java"), "class ServiceTest {}");

        var map = new RepositoryIntelligence(root).analyze(AnalysisDepth.DEEP);
        assertEquals("GRADLE", map.projectType());
        assertTrue(map.languages().contains("Java"));
        assertTrue(map.configFiles().contains("build.gradle.kts"));
        assertTrue(map.sourceRoots().stream().anyMatch(path -> path.contains("src/main")));
        assertTrue(map.testRoots().stream().anyMatch(path -> path.contains("src/test")));
        assertTrue(map.modules().stream().anyMatch(module -> module.name().equals("core")));
        assertTrue(map.symbols().stream().anyMatch(symbol -> symbol.name().equals("Service")));
        assertTrue(map.symbols().stream().anyMatch(symbol -> symbol.name().equals("run")));
    }

    @Test
    void ignoresBuildDirectoriesAndRanksSymbolSearch() throws Exception {
        Path root = Files.createTempDirectory("devmesh-repo");
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.createDirectories(root.resolve("build/generated"));
        Files.writeString(root.resolve("src/main/java/demo/RepairController.java"), "class RepairController {}");
        Files.writeString(root.resolve("build/generated/RepairController.java"), "class RepairController {}");

        var intelligence = new RepositoryIntelligence(root);
        var results = intelligence.search("RepairController", AnalysisDepth.STANDARD);
        assertEquals(1, results.size());
        assertEquals(1.0, results.getFirst().relevance());
        var first = intelligence.analyze(AnalysisDepth.STANDARD);
        var second = intelligence.analyze(AnalysisDepth.STANDARD);
        assertSame(first, second);
    }

    @Test
    void identifiesRepositoryConfigurationAndEntryPoint() throws Exception {
        Path root = Files.createTempDirectory("devmesh-repo");
        Files.writeString(root.resolve("package.json"), "{}\n");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "class Main { public static void main(String[] args) {} }");
        var map = new RepositoryIntelligence(root).analyze(AnalysisDepth.DEEP);
        assertEquals("NODE", map.projectType());
        assertTrue(map.configFiles().contains("package.json"));
        assertTrue(map.entryPoints().stream().anyMatch(entry -> entry.contains("Main.java")));
    }
}