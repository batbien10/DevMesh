package devmesh.tool.select;

import devmesh.tool.Tool;

public record ToolSelection(Tool tool, ToolCapability capability, int score, String reason) {}