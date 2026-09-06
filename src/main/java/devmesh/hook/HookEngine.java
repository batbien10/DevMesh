package devmesh.hook;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Hook engine for lifecycle registration, condition matching, and action execution.
 * Mirrors the Go implementation's feature set:
 *   - 9 events and 4 action types (command / prompt / http / agent)
 *   - != / =~ / =* operators and && / || compound conditions
 *   - once, async, and onError policies
 *   - configuration validation, command timeouts, environment injection, and template substitution
 */
public class HookEngine {

    /** Default command timeout (10 minutes), matching the Go implementation. */
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(10);

    /** Default HTTP request timeout (10 seconds). */
    private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);

    private static final String BASH_EXECUTABLE = discoverBashExecutable();

    // ======== Event names ========

    public enum EventName {
        SESSION_START("session_start"),
        SESSION_END("session_end"),
        TURN_START("turn_start"),
        TURN_END("turn_end"),
        PRE_SEND("pre_send"),
        POST_RECEIVE("post_receive"),
        PRE_TOOL_USE("pre_tool_use"),
        POST_TOOL_USE("post_tool_use"),
        SHUTDOWN("shutdown");

        private final String value;

        EventName(String value) { this.value = value; }

        public String value() { return value; }

        /** Find the enum value for a string, or return null when unknown. */
        public static EventName fromString(String s) {
            if (s == null) return null;
            for (EventName e : values()) {
                if (e.value.equals(s)) return e;
            }
            return null;
        }
    }

    // ======== Action types ========

    public enum ActionType {
        COMMAND("command"),
        PROMPT("prompt"),
        HTTP("http"),
        AGENT("agent");

        private final String value;

        ActionType(String value) { this.value = value; }

        public String value() { return value; }

        /** Find the enum value for a string, or return null when unknown. */
        public static ActionType fromString(String s) {
            if (s == null) return null;
            for (ActionType t : values()) {
                if (t.value.equals(s)) return t;
            }
            return null;
        }
    }

    // ======== Data records ========

    /**
     * Action definition aligned with the Go Action structure.
     * Different types use different fields:
     *   command -> command field
     *   prompt  -> message field
     *   agent   -> message, or command as a fallback
     */
    public record Action(
            ActionType type,
            String command,
            String message,
            String url,
            String method,
            Map<String, String> headers,
            String body,
            Duration timeout
    ) {
        /** Convenience constructor for command/prompt actions. */
        public Action(ActionType type, String command, String message) {
            this(type, command, message, null, null, null, null, Duration.ZERO);
        }
    }

    /**
     * A single hook configuration aligned with the Go Hook structure.
     */
    public record Hook(
            String id,
            EventName event,
            String condition,
            Action action,
            boolean reject,
            boolean once,
            boolean async,
            String onError
    ) {
        /** Backward-compatible convenience constructor. */
        public Hook(String id, EventName event, String condition, Action action, boolean reject) {
            this(id, event, condition, action, reject, false, false, null);
        }
    }

    /**
     * Hook execution context carrying all information for the current event.
     */
    public record HookContext(
            EventName event,
            String toolName,
            Map<String, Object> toolArgs,
            String filePath,
            String message,
            String error
    ) {
        /**
         * Replace ${var} template variables with values from the context.
         * Supported variables: event, tool, file_path, message, error, args.xxx
         */
        public String expand(String template) {
            if (template == null || !template.contains("${")) return template;
            String result = template;
            result = result.replace("${event}", event != null ? event.value() : "");
            result = result.replace("${tool}", toolName != null ? toolName : "");
            result = result.replace("${file_path}", filePath != null ? filePath : "");
            result = result.replace("${message}", message != null ? message : "");
            result = result.replace("${error}", error != null ? error : "");

            // Replace args.xxx variables.
            if (toolArgs != null) {
                for (var entry : toolArgs.entrySet()) {
                    String placeholder = "${args." + entry.getKey() + "}";
                    if (result.contains(placeholder)) {
                        result = result.replace(placeholder,
                                entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                    }
                }
            }
            return result;
        }
    }

    public record HookResult(String hookId, String output, boolean success, boolean reject) {}

    public record PreToolResult(boolean rejected, String message) {}

    // ======== Engine state ========

    private final List<Hook> hooks = new ArrayList<>();
    private final List<HookResult> notifications = Collections.synchronizedList(new ArrayList<>());
    /** Hook IDs already triggered, used for once de-duplication. */
    private final Set<String> fired = Collections.synchronizedSet(new HashSet<>());

    /**
     * Optional executor for agent-type hooks.
     * Receives a prompt and context and returns output. An unregistered runner returns an explicit error.
     */
    private BiFunction<String, HookContext, String> agentRunner;

    // ======== Agent runner ========

    public void setAgentRunner(BiFunction<String, HookContext, String> runner) {
        this.agentRunner = runner;
    }

    // ======== Hook registration ========

    public void addHook(Hook hook) {
        synchronized (hooks) {
            hooks.add(hook);
        }
    }

    public void loadHooks(List<Hook> hookList) {
        synchronized (hooks) {
            hooks.clear();
            hooks.addAll(hookList);
            fired.clear();
        }
    }

    // ======== Config validation ========

    /**
     * Validate hook configurations and report errors before execution.
     * Aggregate all errors instead of stopping at the first one.
     * Aligned with the Go Validate() implementation.
     *
     * @return validation errors; empty means the configuration is valid
     */
    public static List<String> validate(List<Hook> hooks) {

        // Valid event names.
        Set<EventName> validEvents = EnumSet.allOf(EventName.class);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < hooks.size(); i++) {
            Hook h = hooks.get(i);
            String label = (h.id() != null && !h.id().isEmpty())
                    ? String.format("hook[%d] (id=%s)", i, h.id())
                    : String.format("hook[%d]", i);


            // Validate the event name.
            if (h.event() == null || !validEvents.contains(h.event())) {
                errors.add(String.format("%s: unknown event \"%s\"", label,
                        h.event() != null ? h.event().value() : "null"));
            }


            // Validate the timeout.
            if (h.action() != null && h.action().timeout() != null
                    && h.action().timeout().isNegative()) {
                errors.add(String.format("%s: action.timeout must be >= 0 (got %s)",
                        label, h.action().timeout()));
            }


            // Validate the action type and required fields.
            if (h.action() == null || h.action().type() == null) {
                errors.add(String.format("%s: action.type is required", label));
                continue;
            }

            switch (h.action().type()) {
                case COMMAND -> {
                    if (isBlank(h.action().command())) {
                        errors.add(String.format(
                                "%s: action.command must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
                case PROMPT -> {
                    if (isBlank(h.action().message())) {
                        errors.add(String.format(
                                "%s: action.message must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
                case HTTP -> {
                    if (isBlank(h.action().url())) {
                        errors.add(String.format(
                                "%s: action.url must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    } else {

                        // Validate the URL format.
                        try {
                            URI uri = URI.create(h.action().url());
                            String scheme = uri.getScheme();
                            if (!"http".equals(scheme) && !"https".equals(scheme)
                                    || uri.getHost() == null || uri.getHost().isEmpty()) {
                                errors.add(String.format(
                                        "%s: action.url must be a valid http(s) URL (got \"%s\")",
                                        label, h.action().url()));
                            }
                        } catch (Exception e) {
                            errors.add(String.format(
                                    "%s: action.url must be a valid http(s) URL (got \"%s\")",
                                    label, h.action().url()));
                        }
                    }
                }
                case AGENT -> {
                    if (isBlank(h.action().message()) && isBlank(h.action().command())) {
                        errors.add(String.format(
                                "%s: action.message (or action.command as fallback) must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
            }
        }
        return errors;
    }

    // ======== Hook execution ========

    /**
     * Match an event name and execute all applicable hooks.
     * Async hooks run on an independent thread without blocking the caller.
     */
    public List<HookResult> runHooks(HookContext ctx) {
        List<HookResult> results = new ArrayList<>();
        for (Hook h : snapshotHooks()) {
            if (h.event() != ctx.event()) continue;
            if (!shouldFire(h, ctx)) continue;


            // Async hook: run independently and return a placeholder immediately.
            if (h.async()) {
                CompletableFuture.runAsync(() -> {
                    HookResult res = executeAction(h, ctx);
                    notifications.add(res);
                });
                results.add(new HookResult(h.id(), "(async)", true, false));
                continue;
            }

            HookResult result = executeAction(h, ctx);
            results.add(result);
            notifications.add(result);
        }
        return results;
    }

    /**
     * Execute hooks for the pre_tool_use event.
     * Supports the reject setting and onError=reject policy.
     * Non-rejecting hooks still run for side effects such as notifications or HTTP calls.
     */
    public PreToolResult runPreToolHooks(String toolName, Map<String, Object> args) {
        HookContext ctx = new HookContext(
                EventName.PRE_TOOL_USE, toolName, args, null, null, null);
        for (Hook h : snapshotHooks()) {
            if (h.event() != EventName.PRE_TOOL_USE) continue;
            if (!shouldFire(h, ctx)) continue;

            HookResult result = executeAction(h, ctx);
            notifications.add(result);


            // A configured reject or action failure with onError=reject blocks the call.
            if (h.reject() || (!result.success() && "reject".equals(h.onError()))) {
                String msg = result.output();
                if (msg == null || msg.isEmpty()) {
                    msg = "blocked by hook " + h.id();
                }
                return new PreToolResult(true, msg);
            }
        }
        return new PreToolResult(false, "");
    }

    // ======== Notifications ========

    /** Drain accumulated notifications in a thread-safe way. */
    public List<HookResult> drainNotifications() {
        synchronized (notifications) {
            List<HookResult> result = List.copyOf(notifications);
            notifications.clear();
            return result;
        }
    }


    // ======== Internal methods ========

    /** Determine whether a hook should trigger (condition match plus once de-duplication). */
    private boolean shouldFire(Hook h, HookContext ctx) {

        // Skip when the condition does not match.
        if (h.condition() != null && !h.condition().isEmpty()
                && !evaluateCondition(h.condition(), ctx)) {
            return false;
        }

        // once: trigger each ID only once.
        if (h.once()) {
            if (h.id() != null && !h.id().isEmpty()) {
                if (fired.contains(h.id())) return false;
                fired.add(h.id());
            }
        }
        return true;
    }

    /** Snapshot the hook list to avoid concurrent modification during execution. */
    private List<Hook> snapshotHooks() {
        synchronized (hooks) {
            return new ArrayList<>(hooks);
        }
    }

    // ======== Condition evaluation ========

    /**
    * Condition evaluator supporting:
    *   - Leaf conditions: var == "value", var != "value", var =~ /regex/, var =* "glob"
    *   - Compound conditions: cond1 && cond2, cond1 || cond2 (left-to-right, no parentheses)
    *   - Negation: !cond
     */
    static boolean evaluateCondition(String condition, HookContext ctx) {
        String cond = condition.strip();
        if (cond.isEmpty()) return true;

        // Try to split compound conditions (&& and ||).
        List<CompToken> tokens = splitComposite(cond);
        if (tokens != null && tokens.size() > 1) {
            boolean result = evaluateCondition(tokens.get(0).expr, ctx);
            for (int i = 1; i < tokens.size(); i++) {
                boolean rhs = evaluateCondition(tokens.get(i).expr, ctx);
                if ("&&".equals(tokens.get(i).op)) {
                    result = result && rhs;
                } else {
                    result = result || rhs;
                }
            }
            return result;
        }

        // Negation operator.
        if (cond.startsWith("!")) {
            return !evaluateCondition(cond.substring(1).strip(), ctx);
        }

        return evaluateLeaf(cond, ctx);
    }

    /** Token for splitting compound conditions. */
    private record CompToken(String op, String expr) {}

    /**
    * Split a condition string into tokens at top-level && and || operators.
    * Operators inside quotes are not handled; hook conditions remain intentionally simple.
    * Return null when no split is needed (a pure leaf condition).
     */
    private static List<CompToken> splitComposite(String s) {
        List<CompToken> out = new ArrayList<>();
        int start = 0;
        String currentOp = "";
        for (int i = 0; i < s.length() - 1; i++) {
            String pair = s.substring(i, i + 2);
            if ("&&".equals(pair) || "||".equals(pair)) {
                out.add(new CompToken(currentOp, s.substring(start, i).strip()));
                currentOp = pair;
                start = i + 2;
                i++; // Skip the second character.
            }
        }
        out.add(new CompToken(currentOp, s.substring(start).strip()));
        // One element means no split occurred.
        return out.size() <= 1 ? null : out;
    }

    /**
    * Evaluate a leaf condition with four operators:
    *   ==  exact equality
    *   !=  inequality
    *   =~  regular-expression match
    *   =*  glob-pattern match
     */
    static boolean evaluateLeaf(String condition, HookContext ctx) {
        // Check operators by precedence; != must be checked before ==.
        for (String op : new String[]{"!=", "=~", "=*", "=="}) {
            int idx = condition.indexOf(op);
            if (idx >= 0) {
                String left = condition.substring(0, idx).strip();
                String right = stripQuotes(condition.substring(idx + op.length()).strip());
                String val = resolveVar(left, ctx);

                return switch (op) {
                    case "==" -> val.equals(right);
                    case "!=" -> !val.equals(right);
                    case "=~" -> {
                        // Regex match: remove slash delimiters.
                        // Use find() rather than matches() to match Go regexp.MatchString partial-match semantics.
                        String pattern = stripSlashes(right);
                        try {
                            yield Pattern.compile(pattern).matcher(val).find();
                        } catch (PatternSyntaxException e) {
                            yield false;
                        }
                    }
                    case "=*" -> {
                        // Glob match using Java NIO PathMatcher.
                        try {
                            PathMatcher matcher = FileSystems.getDefault()
                                    .getPathMatcher("glob:" + right);
                            yield matcher.matches(Paths.get(val));
                        } catch (Exception e) {
                            yield false;
                        }
                    }
                    default -> false;
                };
            }
        }
        // No operator means true when the variable is non-empty.
        return !resolveVar(condition.strip(), ctx).isEmpty();
    }

    /** Resolve a variable reference from HookContext. */
    static String resolveVar(String name, HookContext ctx) {
        return switch (name) {
            case "tool" -> ctx.toolName() != null ? ctx.toolName() : "";
            case "event" -> ctx.event() != null ? ctx.event().value() : "";
            case "file_path" -> ctx.filePath() != null ? ctx.filePath() : "";
            case "message" -> ctx.message() != null ? ctx.message() : "";
            default -> {
                // Support args.xxx variable access.
                if (name.startsWith("args.") && ctx.toolArgs() != null) {
                    String key = name.substring("args.".length());
                    Object v = ctx.toolArgs().get(key);
                    yield v != null ? String.valueOf(v) : "";
                }
                yield "";
            }
        };
    }

    /** Remove quotes from both ends of a string (single, double, or regex slashes). */
    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '/' && last == '/')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** Remove slash delimiters from a regex pattern. */
    private static String stripSlashes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '/' && s.charAt(s.length() - 1) == '/') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ======== Action execution ========

    /** Dispatch execution by action type. */
    private HookResult executeAction(Hook h, HookContext ctx) {
        return switch (h.action().type()) {
            case COMMAND -> executeCommand(h, ctx);
            case PROMPT -> new HookResult(h.id(), h.action().message(), true, h.reject());
            case HTTP -> executeHTTP(h, ctx);
            case AGENT -> executeAgent(h, ctx);
        };
    }

    /**
    * Execute a command action through bash -c.
    * Inject DEVMESH_EVENT, DEVMESH_TOOL, and DEVMESH_FILE_PATH environment variables.
    * Enforce a timeout and terminate the child process when it expires.
     */
    private HookResult executeCommand(Hook h, HookContext ctx) {
        Duration timeout = h.action().timeout() != null && !h.action().timeout().isZero()
                ? h.action().timeout()
                : DEFAULT_COMMAND_TIMEOUT;

        // Substitute template variables in the command.
        String command = ctx.expand(h.action().command());

        try {
            ProcessBuilder pb = new ProcessBuilder(BASH_EXECUTABLE, "-c", command);
            Map<String, String> env = pb.environment();
            // Inject the three environment variables used by the Go implementation.
            env.put("DEVMESH_EVENT", ctx.event() != null ? ctx.event().value() : "");
            env.put("DEVMESH_TOOL", ctx.toolName() != null ? ctx.toolName() : "");
            env.put("DEVMESH_FILE_PATH", ctx.filePath() != null ? ctx.filePath() : "");

            Process proc = pb.start();

            // Read stdout and stderr asynchronously to avoid pipe blocking.
            InputStream stdoutStream = proc.getInputStream();
            InputStream stderrStream = proc.getErrorStream();

            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                // Terminate the whole process tree; killing only bash can leave
                // the command child alive on Windows/MSYS and leak resources.
                proc.descendants().forEach(child -> {
                    try { child.destroyForcibly(); } catch (Exception ignored) {}
                });
                proc.destroyForcibly();
                try { proc.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String msg = String.format("command timed out after %s", timeout);
                return new HookResult(h.id(), msg, false, h.reject());
            }

            String stdout = new String(stdoutStream.readAllBytes()).strip();
            String stderr = new String(stderrStream.readAllBytes()).strip();
            int code = proc.exitValue();

            String output = stdout;
            if (!stderr.isEmpty()) {
                output = output.isEmpty() ? stderr : output + "\n" + stderr;
            }
            return new HookResult(h.id(), output, code == 0, h.reject());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new HookResult(h.id(),
                    "Failed to execute hook: " + e.getMessage(), false, h.reject());
        }
    }

    /** Prefer Git Bash on Windows; System32/bash.exe is a WSL launcher and may be unavailable. */
    private static String discoverBashExecutable() {
        String configured = System.getenv("DEVMESH_BASH");
        if (configured != null && !configured.isBlank()) return configured;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return "bash";

        var candidates = new ArrayList<Path>();
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) candidates.add(Path.of(programFiles, "Git", "bin", "bash.exe"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            candidates.add(Path.of(localAppData, "Programs", "Git", "bin", "bash.exe"));
        }
        try {
            Process git = new ProcessBuilder("git", "--exec-path").start();
            String execPath = new String(git.getInputStream().readAllBytes()).strip();
            if (git.waitFor(2, TimeUnit.SECONDS) && git.exitValue() == 0 && !execPath.isBlank()) {
                Path root = Path.of(execPath).getParent(); // libexec
                if (root != null) root = root.getParent(); // mingw64
                if (root != null) root = root.getParent(); // Git root
                if (root != null) candidates.add(root.resolve("bin").resolve("bash.exe"));
            }
        } catch (Exception ignored) {
        }
        return candidates.stream().filter(Files::isRegularFile)
                .map(Path::toString).findFirst().orElse("bash");
    }

    /**
    * Execute an HTTP action by sending an HTTP request.
    * The default method is POST and the default Content-Type is application/json.
    * When no body is configured, build a JSON payload containing context information.
     */
    private HookResult executeHTTP(Hook h, HookContext ctx) {
        String method = h.action().method() != null && !h.action().method().isEmpty()
                ? h.action().method().toUpperCase() : "POST";
        Duration timeout = h.action().timeout() != null && !h.action().timeout().isZero()
                ? h.action().timeout() : DEFAULT_HTTP_TIMEOUT;

        // Substitute template variables in the URL.
        String url = ctx.expand(h.action().url());

        // Build the request body.
        String body = h.action().body();
        if (body != null && !body.isEmpty()) {
            body = ctx.expand(body);
        } else {
            // Generate JSON containing context information.
            body = String.format(
                    "{\"event\":\"%s\",\"tool\":\"%s\",\"file_path\":\"%s\",\"message\":\"%s\",\"error\":\"%s\"}",
                    ctx.event() != null ? ctx.event().value() : "",
                    ctx.toolName() != null ? ctx.toolName() : "",
                    ctx.filePath() != null ? ctx.filePath() : "",
                    ctx.message() != null ? escapeJson(ctx.message()) : "",
                    ctx.error() != null ? escapeJson(ctx.error()) : "");
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));

            // Set request headers.
            boolean hasContentType = false;
            if (h.action().headers() != null) {
                for (var entry : h.action().headers().entrySet()) {
                    reqBuilder.header(entry.getKey(), ctx.expand(entry.getValue()));
                    if ("content-type".equalsIgnoreCase(entry.getKey())) {
                        hasContentType = true;
                    }
                }
            }
            if (!hasContentType && !body.isEmpty()) {
                reqBuilder.header("Content-Type", "application/json");
            }

            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            HttpResponse<String> resp = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            String respBody = resp.body();
            // Limit the response body to 64 KB.
            if (respBody != null && respBody.length() > 65536) {
                respBody = respBody.substring(0, 65536);
            }
            return new HookResult(h.id(),
                    String.format("HTTP %d: %s", resp.statusCode(), respBody != null ? respBody.strip() : ""),
                    ok, h.reject());
        } catch (Exception e) {
            return new HookResult(h.id(), e.getMessage(), false, h.reject());
        }
    }

    /**
    * Execute an agent action by starting a SubAgent for the hook task.
    * Register a runner through setAgentRunner first; otherwise return an explicit error.
     */
    private HookResult executeAgent(Hook h, HookContext ctx) {
        if (agentRunner == null) {
            return new HookResult(h.id(),
                    "agent-type hook configured but no AgentRunner registered",
                    false, h.reject());
        }
        // Prefer message, with command as the fallback.
        String prompt = h.action().message();
        if (prompt == null || prompt.isEmpty()) {
            prompt = h.action().command();
        }
        // Substitute template variables.
        prompt = ctx.expand(prompt);

        try {
            String output = agentRunner.apply(prompt, ctx);
            return new HookResult(h.id(), output, true, h.reject());
        } catch (Exception e) {
            return new HookResult(h.id(), e.getMessage(), false, h.reject());
        }
    }

    // ======== Utilities ========

    private static boolean isBlank(String s) {
        return s == null || s.strip().isEmpty();
    }

    /** Escape a JSON string. */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
