package devmesh.command;

import devmesh.command.Command.CommandType;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central registry for all slash commands.
 * Ported from Go: internal/commands/commands.go (Registry + CreateDefaultRegistry).
 */
public class CommandRegistry {

    private final List<Command> commands = new ArrayList<>();
    private final Map<String, Function<CommandContext, String>> handlers = new HashMap<>();

    private final Map<String, String> nameIndex = new HashMap<>();
    private final Map<String, String> aliasIndex = new HashMap<>();

    /** Creates a registry pre-populated with the default DevMesh commands. */
    public CommandRegistry() {
        registerDefaults();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Registers a command with an optional handler.
     *
     * @param cmd     command definition
     * @param handler handler function (args -> output); may be {@code null} for UI-only commands
     */
    public void register(Command cmd, Function<CommandContext, String> handler) {

        if (nameIndex.containsKey(cmd.name())) {
            throw new IllegalArgumentException(
                    "commands: duplicate command name '%s'".formatted(cmd.name()));
        }

        if (aliasIndex.containsKey(cmd.name())) {
            throw new IllegalArgumentException(
                    "commands: command name '%s' collides with alias of '%s'"
                            .formatted(cmd.name(), aliasIndex.get(cmd.name())));
        }

        for (var alias : cmd.aliases()) {
            if (nameIndex.containsKey(alias)) {
                throw new IllegalArgumentException(
                        "commands: alias '%s' for '%s' collides with existing command name"
                                .formatted(alias, cmd.name()));
            }
            if (aliasIndex.containsKey(alias)) {
                throw new IllegalArgumentException(
                        "commands: alias '%s' for '%s' already registered by '%s'"
                                .formatted(alias, cmd.name(), aliasIndex.get(alias)));
            }
        }


        nameIndex.put(cmd.name(), cmd.name());
        for (var alias : cmd.aliases()) {
            aliasIndex.put(alias, cmd.name());
        }

        commands.add(cmd);
        if (handler != null) {
            handlers.put(cmd.name(), handler);
            for (var alias : cmd.aliases()) {
                handlers.put(alias, handler);
            }
        }
    }

    /**
     */
    public boolean hasConflict(Command cmd) {
        if (find(cmd.name()).isPresent()) {
            return true;
        }
        for (var alias : cmd.aliases()) {
            if (find(alias).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns all non-hidden commands whose name starts with {@code prefix}
     * (case-insensitive comparison).
     */
    public List<Command> search(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return commands.stream()
                .filter(c -> !c.hidden())
                .map(c -> new AbstractMap.SimpleEntry<>(c, matchScore(c, lower)))
                .filter(entry -> entry.getValue() >= 0)
                .sorted(Comparator.comparingInt((Map.Entry<Command, Integer> entry) -> entry.getValue())
                    .thenComparingInt(entry -> commands.indexOf(entry.getKey())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private static int matchScore(Command command, String query) {
        int best = matchScore(command.name(), query);
        for (String alias : command.aliases()) best = Math.min(best, matchScore(alias, query));
        return best;
    }

    private static int matchScore(String value, String query) {
        String candidate = value.toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return 0;
        if (candidate.equals(query)) return 1;
        if (candidate.startsWith(query)) return 2;
        int position = 0;
        int gaps = 0;
        for (int i = 0; i < query.length(); i++) {
            int found = candidate.indexOf(query.charAt(i), position);
            if (found < 0) return -1;
            gaps += found - position;
            position = found + 1;
        }
        return 10 + gaps;
    }

    /** Finds a command by exact name or alias match. */
    public Optional<Command> find(String name) {
        return commands.stream()
                .filter(c -> c.matches(name))
                .findFirst();
    }

    /**
     * Executes a LOCAL command handler and returns its output.
     *
     * @param name command name or alias
     * @param args arguments passed after the command name
     * @return handler output, or an error message if not found / no handler
     */
    public String execute(String name, CommandContext ctx) {
        Function<CommandContext, String> handler = handlers.get(name);
        if (handler != null) {
            return handler.apply(ctx);
        }
        Optional<Command> cmd = find(name);
        if (cmd.isEmpty()) {
            return "Unknown command: " + name;
        }
        handler = handlers.get(cmd.get().name());
        if (handler != null) {
            return handler.apply(ctx);
        }
        return "No handler registered for /" + name;
    }

    /** Returns an unmodifiable view of all registered commands. */
    public List<Command> listAll() {
        return Collections.unmodifiableList(commands);
    }

    /** Returns all non-hidden commands, sorted by name. */
    public List<Command> listVisible() {
        return commands.stream()
                .filter(c -> !c.hidden())
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Default command registration
    // ------------------------------------------------------------------

    private void registerDefaults() {
        // /help (LOCAL, aliases: h, ?)
        register(
                new Command("help", "Show available commands",
                        new String[]{"h", "?"}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    if (args != null && !args.isBlank()) {
                        Optional<Command> target = find(args.strip());
                        if (target.isEmpty()) {
                            return "Unknown command: " + args.strip();
                        }
                        Command c = target.get();
                        var sb = new StringBuilder();
                        sb.append("/").append(c.name()).append(" — ").append(c.description()).append("\n");
                        if (c.aliases().length > 0) {
                            sb.append("  Aliases: ").append(String.join(", ", c.aliases())).append("\n");
                        }
                        return sb.toString();
                    }
                    var sb = new StringBuilder();
                    sb.append("Available commands:\n\n");
                    for (var cmd : listVisible()) {
                        String aliases = "";
                        if (cmd.aliases().length > 0) {
                            aliases = ", /" + String.join(", /", cmd.aliases());
                        }
                        sb.append("  /").append(cmd.name()).append(aliases).append("\n");
                        sb.append("    ").append(cmd.description()).append("\n");
                    }
                    sb.append("\nType /help <command> for details.");
                    return sb.toString();
                }
        );

        // /mcp (LOCAL)
        register(
                new Command("mcp", "Show MCP server status",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    if (ctx.mcpInfo() == null) return "No MCP servers configured";
                    String info = ctx.mcpInfo().get();
                    return info.isEmpty() ? "No MCP servers connected" : info;
                }
        );

            register(
                new Command("model", "Select the active AI model",
                    new String[]{}, CommandType.LOCAL_UI, false),
                null
            );
            register(
                new Command("provider", "Select the active provider",
                    new String[]{}, CommandType.LOCAL_UI, false),
                null
            );
            register(
                new Command("mode", "Select model reasoning mode",
                    new String[]{}, CommandType.LOCAL_UI, false),
                null
            );
            register(
                new Command("thinking", "Toggle model thinking",
                    new String[]{}, CommandType.LOCAL_UI, false),
                null
            );
            register(
                new Command("model-settings", "Configure model runtime settings",
                    new String[]{}, CommandType.LOCAL_UI, false),
                null
            );

            register(
                new Command("context", "Show context and session information",
                    new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    int[] tokens = ctx.tokenCount().get();
                    return "Context\n"
                        + "  Session: " + ctx.sessionInfo().get() + "\n"
                        + "  Tokens: " + tokens[0] + " in / " + tokens[1] + " out\n"
                        + "  Model: " + ctx.model();
                }
            );
            register(
                new Command("tools", "Show the number of available tools",
                    new String[]{}, CommandType.LOCAL, false),
                ctx -> "Available tools: " + ctx.toolCount().getAsInt()
            );

        // /clear (LOCAL_UI)
        register(
                new Command("clear", "Clear conversation and start fresh",
                        new String[]{}, CommandType.LOCAL_UI, false),
                null
        );

            register(
                new Command("quit", "Exit DevMesh",
                    new String[]{"q"}, CommandType.LOCAL_UI, false),
                null
            );

        // /compact (LOCAL_UI, alias: c)
        register(
                new Command("compact", "Compress conversation context",
                        new String[]{"c"}, CommandType.LOCAL_UI, false),
                null
        );

        // /status (LOCAL, alias: s)
        register(
                new Command("status", "Show current status",
                        new String[]{"s"}, CommandType.LOCAL, false),
                ctx -> {
                    var sb = new StringBuilder();
                    sb.append("DevMesh Status\n");
                    sb.append("──────────────\n");
                    sb.append("  Mode:      ").append(ctx.permissionMode().get()).append("\n");
                    int[] tokens = ctx.tokenCount().get();
                    sb.append("  Tokens:    ").append(tokens[0]).append(" in / ").append(tokens[1]).append(" out\n");
                    sb.append("  Tools:     ").append(ctx.toolCount().getAsInt()).append(" enabled\n");
                    var memories = ctx.memoryList().get();
                    sb.append("  Memories:  ").append(memories.size()).append(" entries\n");
                    sb.append("  Model:     ").append(ctx.model()).append("\n");
                    sb.append("  Directory: ").append(ctx.workDir()).append("\n");
                    return sb.toString();
                }
        );

        // /memory (LOCAL)
        register(
                new Command("memory", "Manage auto-memories",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank()) ? "list" : args.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "list" -> {
                            var memories = ctx.memoryList().get();
                            if (memories.isEmpty()) yield "No memories stored yet.";
                            var sb = new StringBuilder("Auto-memories (%d):\n".formatted(memories.size()));
                            for (var m : memories) sb.append("  • ").append(m).append("\n");
                            yield sb.toString();
                        }
                        case "clear" -> { ctx.memoryClear().run(); yield "All auto-memories cleared."; }
                        default -> "Usage: /memory [list|clear]";
                    };
                }
        );

        // /plan (LOCAL_UI, alias: p)
        register(
                new Command("plan", "Switch to plan mode (read-only)",
                        new String[]{"p"}, CommandType.LOCAL_UI, false),
                null
        );

        // /session (LOCAL)
        register(
                new Command("session", "Session management",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank()) ? "info" : args.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "info" -> ctx.sessionInfo().get();
                        case "list" -> ctx.sessionInfo().get();

                        default -> "Usage: /session [list|info]";
                    };
                }
        );

        // /permission (LOCAL, alias: perm)
        register(
                new Command("permission", "Permission management",
                        new String[]{"perm"}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank()) ? "info" : args.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "info" -> "Current permission mode: " + ctx.permissionMode().get();
                        case "mode" -> "Usage: /permission mode <default|acceptEdits|plan|bypassPermissions>";

                        default -> "Usage: /permission [info|mode <mode>|rules]";
                    };
                }
        );

        // /resume (LOCAL_UI, alias: r)
        register(
                new Command("resume", "Resume a previous session",
                        new String[]{"r"}, CommandType.LOCAL_UI, false),
                null
        );

        // /rewind (LOCAL_UI)
        register(
                new Command("rewind", "Rewind to a previous checkpoint",
                        new String[]{}, CommandType.LOCAL_UI, false),
                null
        );


        register(
                new Command("skills", "List available skills (use '/skills reload' to hot-reload)",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    if (ctx.args() != null && ctx.args().strip().equals("reload")) {
                        int count = ctx.skillReload().getAsInt();
                        return "Skills reloaded. %d skill(s) available.".formatted(count);
                    }
                    var skills = ctx.skillList().get();
                    if (skills.isEmpty()) return "No skills installed.\n\nAdd skills to .devmesh/skills/<skill-name>/SKILL.md";
                    var sb = new StringBuilder("Installed skills (%d):\n".formatted(skills.size()));
                    for (var s : skills) sb.append("  • ").append(s).append("\n");
                    sb.append("\nType /skills reload to hot-reload skills from disk.");
                    return sb.toString();
                }
        );

        // /review (PROMPT)
        register(
                new Command("review", "Review current code changes",
                        new String[]{}, CommandType.PROMPT, false),
                ctx -> {
                    String args = ctx.args();
                    String prompt = "Please review the current git diff for code changes. Focus on:\n"
                            + "1. Logic errors\n2. Security issues\n3. Performance problems\n4. Code style";
                    if (args != null && !args.isBlank()) {
                        prompt += "\n\nAdditional focus: " + args.strip();
                    }
                    return prompt;
                }
        );


        register(
                new Command("sandbox", "Manage OS-level sandbox for Bash commands",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    if (args == null || args.isBlank()) {

                        String status = ctx.sandboxStatus() != null ? ctx.sandboxStatus().get() : "unavailable";
                        var sb = new StringBuilder();
                        sb.append("Sandbox status: ").append(status).append("\n\n");
                        sb.append("Available modes:\n");
                        sb.append("  /sandbox 1  — Enable sandbox + auto-allow (recommended)\n");
                        sb.append("  /sandbox 2  — Enable sandbox + standard permissions\n");
                        sb.append("  /sandbox 3  — Disable sandbox\n");
                        return sb.toString();
                    }

                    String sub = args.strip();
                    if (ctx.sandboxSwitch() == null) {
                        return "Sandbox is unavailable (the current platform is unsupported or bwrap/sandbox-exec is not installed)";
                    }
                    return switch (sub) {
                        case "1" -> {
                            ctx.sandboxSwitch().accept(1);
                            yield "Sandbox enabled with auto-allow mode. Commands run in an OS-level sandbox without individual confirmation.";
                        }
                        case "2" -> {
                            ctx.sandboxSwitch().accept(2);
                            yield "Sandbox enabled with standard permission mode. Commands run in the sandbox but still require permission checks.";
                        }
                        case "3" -> {
                            ctx.sandboxSwitch().accept(3);
                            yield "Sandbox disabled. Commands will run directly.";
                        }
                        default -> "Invalid option. Use /sandbox 1|2|3 to select a mode.";
                    };
                }
        );
    }
}
