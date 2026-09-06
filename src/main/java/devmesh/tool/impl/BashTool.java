package devmesh.tool.impl;

import devmesh.sandbox.Sandbox;
import devmesh.sandbox.SandboxConfig;
import devmesh.platform.CommandSpec;
import devmesh.platform.ProcessExecutor;
import devmesh.platform.ProcessResult;
import devmesh.tool.Tool;
import devmesh.tool.ToolCategory;
import devmesh.tool.ToolResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Duration;

public class BashTool implements Tool, devmesh.tool.CommandEventEmitter {

    private static final int MAX_TIMEOUT = 600;


    private static final Map<String, String> EXIT_ONE_HINTS = Map.of(
            "grep", "no matches found",
            "egrep", "no matches found",
            "fgrep", "no matches found",
            "rg", "no matches found",
            "diff", "files differ",
            "find", "some directories were inaccessible",
            "test", "condition is false",
            "[", "condition is false"
    );


    private String workDir;


    private Sandbox sandbox;
    private SandboxConfig sandboxConfig;
    private final ProcessExecutor processExecutor = new ProcessExecutor();
    private java.util.function.Consumer<devmesh.agent.AgentEvent> eventSink = ignored -> {};

    public BashTool() {
        this.workDir = null;
    }

    public BashTool(String workDir) {
        this.workDir = workDir;
    }

    public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }
    public void setSandboxConfig(SandboxConfig config) { this.sandboxConfig = config; }
    @Override public void setEventSink(java.util.function.Consumer<devmesh.agent.AgentEvent> sink) {
        this.eventSink = sink == null ? ignored -> {} : sink;
    }

    private static final String DESCRIPTION = """
            Execute a shell command and return stdout and stderr.

            IMPORTANT: Avoid using this tool to run cat, head, tail, sed, awk, or echo commands. \
            Instead use the dedicated ReadFile, EditFile, or WriteFile tools which provide a better experience.

            Usage notes:
            - The working directory persists between commands, but shell state does not.
            - Always quote file paths containing spaces with double quotes.
            - Try to maintain your current working directory using absolute paths; avoid cd unless the user explicitly requests it.
            - Optional timeout in seconds (max 600). Default is 120s.
            - When issuing multiple independent commands, make separate parallel tool calls instead of chaining with &&.
            - Use && to chain sequential dependent commands. Use ; only when you don't care if earlier commands fail.
            - DO NOT use newlines to separate commands.

            Git Safety Protocol:
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., clean -f, branch -D) unless the user explicitly requests it.
            - NEVER skip hooks (--no-verify) unless the user explicitly requests it.
            - Prefer creating a new commit rather than amending an existing one.
            - Before running destructive operations, consider safer alternatives.

            Avoid unnecessary sleep commands. Do not retry failing commands in a sleep loop — diagnose the root cause instead.
            When using find, search from "." or a specific path, not "/" — scanning the full filesystem is too expensive.""";

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "Shell command to execute"),
                                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = stringArg(args, "command", "");
        if (command.isEmpty()) {
            return ToolResult.error("Error: command is required");
        }

        int timeout = intArg(args, "timeout", 120);
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }

        try {

            String actualCommand = command;
            if (sandbox != null && sandbox.isAvailable() && sandboxConfig != null) {
                actualCommand = sandbox.wrap(command, sandboxConfig);
            }

            var spec = new CommandSpec(actualCommand, List.of(),
                    workDir == null || workDir.isBlank() ? null : Path.of(workDir),
                    Map.of(), Duration.ofSeconds(timeout), CommandSpec.Mode.SHELL);
            var running = processExecutor.start(spec, event -> {
                switch (event) {
                    case devmesh.platform.ProcessEvent.Started e -> eventSink.accept(new devmesh.agent.AgentEvent.CommandStarted(e.command()));
                    case devmesh.platform.ProcessEvent.Output e -> eventSink.accept(new devmesh.agent.AgentEvent.CommandOutput(e.text(), false));
                    case devmesh.platform.ProcessEvent.ErrorOutput e -> eventSink.accept(new devmesh.agent.AgentEvent.CommandOutput(e.text(), true));
                    case devmesh.platform.ProcessEvent.Completed e -> eventSink.accept(new devmesh.agent.AgentEvent.CommandCompleted(e.exitCode(), "completed"));
                    case devmesh.platform.ProcessEvent.TimedOut ignored -> eventSink.accept(new devmesh.agent.AgentEvent.CommandTimedOut());
                    case devmesh.platform.ProcessEvent.Cancelled ignored -> eventSink.accept(new devmesh.agent.AgentEvent.CommandCancelled());
                    case devmesh.platform.ProcessEvent.Failed e -> eventSink.accept(new devmesh.agent.AgentEvent.CommandCompleted(-1, e.status().name()));
                }
            });
            ProcessResult result = running.completion().join();
            String output = result.output();
            if (result.status() == ProcessResult.Status.TIMEOUT) {
                return ToolResult.error("Error: command timed out after " + timeout + "s");
            }
            if (result.status() != ProcessResult.Status.SUCCESS
                    && result.status() != ProcessResult.Status.FAILED) {
                return ToolResult.error("Error executing command (" + result.status() + "): " + output);
            }
            int exitCode = result.exitCode();

            var sb = new StringBuilder();
            if (!output.isEmpty()) {
                sb.append(output);
                if (!output.endsWith("\n")) {
                    sb.append('\n');
                }
            }


            if (exitCode != 0) {
                sb.append("Exit code ").append(exitCode);

                String hint = getExitCodeHint(command, exitCode);
                if (hint != null) {
                    sb.append(" (").append(hint).append(")");
                }
                sb.append('\n');
            }


            return new ToolResult(sb.toString(), false);

        } catch (Exception e) {
            return ToolResult.error("Error executing command: " + e.getMessage());
        }
    }

    /**
     */
    private String getExitCodeHint(String command, int exitCode) {
        if (exitCode != 1) {
            return null;
        }
        String baseCmd = extractBaseCommand(command);
        return EXIT_ONE_HINTS.get(baseCmd);
    }

    /**
     */
    private String extractBaseCommand(String command) {
        String cmd = command.strip();


        int pipeIdx = cmd.lastIndexOf('|');
        if (pipeIdx >= 0 && pipeIdx < cmd.length() - 1) {
            cmd = cmd.substring(pipeIdx + 1).strip();
        }


        while (cmd.contains("=") && !cmd.startsWith("=")) {
            int spaceIdx = cmd.indexOf(' ');
            int eqIdx = cmd.indexOf('=');
            if (eqIdx < spaceIdx || spaceIdx == -1) {

                if (spaceIdx == -1) break;
                cmd = cmd.substring(spaceIdx + 1).strip();
            } else {
                break;
            }
        }


        String[] parts = cmd.split("\\s+", 2);
        String token = parts[0];


        int slashIdx = token.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < token.length() - 1) {
            token = token.substring(slashIdx + 1);
        }

        return token;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
