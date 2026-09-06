package devmesh.tool.select;

import devmesh.platform.OperatingSystem;
import devmesh.tool.Tool;
import devmesh.tool.ToolCategory;

import java.util.EnumSet;
import java.util.Set;

public record ToolCapability(
        String name,
        ToolCategory category,
        Set<ToolIntent> intents,
        boolean readOnly,
        boolean mutating,
        boolean destructive,
        boolean requiresPermission,
        boolean requiresSandbox,
        Set<OperatingSystem> supportedPlatforms,
        int estimatedCost,
        int reliability,
        ToolRisk risk
) {
    public ToolCapability {
        intents = Set.copyOf(intents == null ? Set.of() : intents);
        supportedPlatforms = Set.copyOf(supportedPlatforms == null
                ? EnumSet.allOf(OperatingSystem.class) : supportedPlatforms);
        category = category == null ? ToolCategory.COMMAND : category;
        risk = risk == null ? ToolRisk.EXECUTION : risk;
        estimatedCost = Math.max(0, estimatedCost);
        reliability = Math.max(0, Math.min(100, reliability));
    }

    public static ToolCapability from(Tool tool) {
        ToolCategory category = tool.category();
        return switch (tool.name()) {
            case "ReadFile" -> capability(tool, Set.of(ToolIntent.READ_FILE), true, false, false, ToolRisk.SAFE_READ, 1, 95);
            case "Glob", "Grep" -> capability(tool, Set.of(ToolIntent.SEARCH_CODE), true, false, false, ToolRisk.SAFE_ANALYSIS, 1, 90);
            case "WriteFile", "EditFile" -> capability(tool, Set.of(ToolIntent.EDIT_FILE, ToolIntent.CREATE_FILE), false, true, false, ToolRisk.CONTROLLED_WRITE, 2, 90);
            case "Bash" -> capability(tool, Set.of(ToolIntent.RUN_COMMAND, ToolIntent.RUN_BUILD, ToolIntent.RUN_TEST, ToolIntent.GIT_STATUS, ToolIntent.GIT_DIFF), false, true, true, ToolRisk.EXECUTION, 5, 70);
            case "ToolSearch" -> capability(tool, Set.of(ToolIntent.ANALYZE_REPOSITORY, ToolIntent.SEARCH_SYMBOL), true, false, false, ToolRisk.SAFE_ANALYSIS, 1, 80);
            default -> capability(tool, Set.of(ToolIntent.UNKNOWN), category == ToolCategory.READ,
                    category == ToolCategory.WRITE, false, category == ToolCategory.READ ? ToolRisk.SAFE_READ : ToolRisk.EXECUTION, 4, 60);
        };
    }

    private static ToolCapability capability(Tool tool, Set<ToolIntent> intents, boolean readOnly,
                                              boolean mutating, boolean destructive, ToolRisk risk,
                                              int cost, int reliability) {
        return new ToolCapability(tool.name(), tool.category(), intents, readOnly, mutating, destructive,
                !readOnly, destructive, EnumSet.allOf(OperatingSystem.class), cost, reliability, risk);
    }
}