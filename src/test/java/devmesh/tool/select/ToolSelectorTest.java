package devmesh.tool.select;

import devmesh.permission.PermissionChecker;
import devmesh.permission.PermissionMode;
import devmesh.platform.OperatingSystem;
import devmesh.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolSelectorTest {
    @Test
    void specializedReadToolBeatsCommandFallback() throws Exception {
        var registry = ToolRegistry.createDefault();
        var selector = new ToolSelector(registry, Files.createTempDirectory("selector"));
        var request = new ToolSelectionRequest(ToolIntent.READ_FILE,
                Map.of("file_path", "README.md"), OperatingSystem.detect(), null, Set.of());
        assertEquals("ReadFile", selector.select(request).tool().name());
    }

    @Test
    void symbolSearchUsesRepositoryIntelligenceWhenAvailable() throws Exception {
        var root = Files.createTempDirectory("selector");
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("src/main/java/demo/RepairController.java"), "class RepairController {}");
        var selector = new ToolSelector(ToolRegistry.createDefault(), root);
        var request = new ToolSelectionRequest(ToolIntent.SEARCH_SYMBOL,
                Map.of("query", "RepairController"), OperatingSystem.detect(), null, Set.of());
        assertEquals("ToolSearch", selector.select(request).tool().name());
    }

    @Test
    void deniedWriteToolIsExcludedByExistingPermissionChecker() throws Exception {
        var root = Files.createTempDirectory("selector");
        var checker = new PermissionChecker(PermissionMode.DEFAULT, root);
        var selector = new ToolSelector(ToolRegistry.createDefault(), root);
        var request = new ToolSelectionRequest(ToolIntent.EDIT_FILE,
                Map.of("file_path", root.resolve("file.txt").toString(), "content", "x"),
                OperatingSystem.detect(), checker, Set.of());
        assertTrue(selector.candidates(request).stream().anyMatch(selection -> selection.tool().name().equals("EditFile")));
        assertNull(selector.select(new ToolSelectionRequest(ToolIntent.EDIT_FILE,
                Map.of("file_path", root.resolve(".devmesh/config.yaml").toString(), "content", "x"),
                OperatingSystem.detect(), checker, Set.of())));
    }

    @Test
    void repeatedFailureLowersToolScore() throws Exception {
        var selector = new ToolSelector(ToolRegistry.createDefault(), Files.createTempDirectory("selector"));
        var request = new ToolSelectionRequest(ToolIntent.READ_FILE, Map.of("file_path", "x"),
                OperatingSystem.detect(), null, Set.of());
        int before = selector.select(request).score();
        selector.recordFailure("ReadFile");
        assertTrue(selector.select(request).score() < before);
    }

    @Test
    void commandIntentFallsBackToBashWhenNoSpecializedToolExists() throws Exception {
        var selector = new ToolSelector(ToolRegistry.createDefault(), Files.createTempDirectory("selector"));
        var selection = selector.select(new ToolSelectionRequest(ToolIntent.RUN_COMMAND,
                Map.of("command", "git status"), OperatingSystem.detect(), null, Set.of()));
        assertNotNull(selection);
        assertEquals("Bash", selection.tool().name());
    }
}