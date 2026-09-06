package devmesh.tool.impl;

import devmesh.skill.InstallReport;
import devmesh.skill.SkillCatalog;
import devmesh.skill.SkillInstaller;
import devmesh.skill.SkillSource;
import devmesh.tool.Tool;
import devmesh.tool.ToolCategory;
import devmesh.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <p>
 */
public class InstallSkillTool implements Tool {

    private static final String DESCRIPTION =
            "Download and install a Skill from a URL into the user-global skills directory "
            + "(~/.devmesh/skills/). Supports skills.sh URLs (https://www.skills.sh/<owner>/<repo>/<name>), "
            + "GitHub tree URLs (https://github.com/<owner>/<repo>/tree/<ref>/<path>), and raw "
            + "SKILL.md URLs. After install the Skill becomes available via /<name> and LoadSkill. "
            + "Call this when the user pastes a Skill URL and asks to install it.";

    private SkillCatalog catalog;
    private Consumer<String> onInstalled;
    private String installRoot;



    public void setCatalog(SkillCatalog catalog) { this.catalog = catalog; }

    public void setOnInstalled(Consumer<String> callback) { this.onInstalled = callback; }

    public void setInstallRoot(String root) { this.installRoot = root; }



    @Override
    public String name() { return "InstallSkill"; }

    @Override
    public String description() { return DESCRIPTION; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of(
                                        "type", "string",
                                        "description",
                                        "The Skill URL to fetch. Examples: "
                                        + "\"https://www.skills.sh/anthropics/skills/frontend-design\", "
                                        + "\"https://github.com/anthropics/skills/tree/main/skills/pdf\"."
                                )
                        ),
                        "required", List.of("url")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String rawURL = stringArg(args, "url", "");
        if (rawURL.isEmpty()) {
            return ToolResult.error("url is required");
        }


        SkillSource src;
        try {
            src = SkillInstaller.parseSkillURL(rawURL);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }


        String root = installRoot;
        if (root == null || root.isEmpty()) {
            try {
                root = SkillInstaller.userSkillsRoot();
            } catch (Exception e) {
                return ToolResult.error(e.getMessage());
            }
        }


        InstallReport report;
        try {
            var installer = new SkillInstaller();
            report = installer.install(src, root);
        } catch (Exception e) {
            return ToolResult.error("install failed: " + e.getMessage());
        }


        if (catalog != null) {
            catalog.reload(catalog.getWorkDir());
        }
        if (onInstalled != null) {
            onInstalled.accept(report.skillName());
        }

        return ToolResult.success(
                "Installed skill \"%s\" from %s into %s (%d files, %d bytes). "
                        .formatted(report.skillName(), src.original(), report.targetDir(),
                                report.fileCount(), report.totalBytes())
                + "Now available — call LoadSkill({name: \"%s\"}) or invoke /%s directly."
                        .formatted(report.skillName(), report.skillName())
        );
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
