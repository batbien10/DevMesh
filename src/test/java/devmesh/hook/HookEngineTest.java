package devmesh.hook;

import devmesh.hook.HookEngine.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 */
class HookEngineTest {



    @Test
    void testEvaluateConditionLeafOps() {
        var ctx = new HookContext(
                EventName.PRE_TOOL_USE, "Bash",
                Map.of("command", "rm -rf /"),
                "src/foo.go", null, null);


        assertTrue(HookEngine.evaluateCondition("tool == \"Bash\"", ctx));
        assertFalse(HookEngine.evaluateCondition("tool == \"Read\"", ctx));


        assertTrue(HookEngine.evaluateCondition("tool != \"Read\"", ctx));
        assertFalse(HookEngine.evaluateCondition("tool != \"Bash\"", ctx));


        assertTrue(HookEngine.evaluateCondition("event =~ /^pre_/", ctx));
        assertTrue(HookEngine.evaluateCondition("args.command =~ /rm -rf/", ctx));


        assertTrue(HookEngine.evaluateCondition("file_path =* \"src/*.go\"", ctx));
        assertFalse(HookEngine.evaluateCondition("file_path =* \"src/*.py\"", ctx));


        assertTrue(HookEngine.evaluateCondition(
                "tool == \"Bash\" && file_path =* \"src/*.go\"", ctx));
        assertFalse(HookEngine.evaluateCondition(
                "tool == \"Bash\" && file_path =* \"src/*.py\"", ctx));


        assertTrue(HookEngine.evaluateCondition(
                "tool == \"Read\" || tool == \"Bash\"", ctx));
        assertFalse(HookEngine.evaluateCondition(
                "tool == \"Read\" || tool == \"Write\"", ctx));


        assertTrue(HookEngine.evaluateCondition("!tool == \"Read\"", ctx));
    }



    @Test
    void testRunPreToolHooksReject() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "block-rm-rf", EventName.PRE_TOOL_USE,
                "tool == \"Bash\" && args.command =~ /rm -rf/",
                new Action(ActionType.PROMPT, null, "destructive command blocked"),
                true)));

        var result = engine.runPreToolHooks("Bash", Map.of("command", "rm -rf /tmp/x"));
        assertTrue(result.rejected());
        assertTrue(result.message().contains("destructive command blocked"));
    }

    @Test
    void testRunPreToolHooksAllowsWhenConditionFails() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "block-go", EventName.PRE_TOOL_USE,
                "file_path =* \"**/*.go\"",
                new Action(ActionType.PROMPT, null, "blocked"),
                true)));


        var result = engine.runPreToolHooks("WriteFile", Map.of());
        assertFalse(result.rejected());
    }



    @Test
    void testHookOnceOnlyFiresOnce() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "greet", EventName.SESSION_START, null,
                new Action(ActionType.PROMPT, null, "hello"),
                false, true, false, null)));

        var res1 = engine.runHooks(new HookContext(EventName.SESSION_START, null, null, null, null, null));
        var res2 = engine.runHooks(new HookContext(EventName.SESSION_START, null, null, null, null, null));
        assertEquals(1, res1.size(), "The first trigger should produce one result");
        assertEquals(0, res2.size(), "The second trigger should be empty (once)");
    }



    @Test
    void testHookAsyncIsNonBlocking() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "slow", EventName.TURN_END, null,
                new Action(ActionType.COMMAND, "sleep 0.2", null),
                false, false, true, null)));

        long start = System.nanoTime();
        var res = engine.runHooks(new HookContext(EventName.TURN_END, null, null, null, null, null));
        long elapsed = (System.nanoTime() - start) / 1_000_000;


        assertTrue(elapsed < 150, "The async hook blocked for " + elapsed + "ms");
        assertEquals(1, res.size());
        assertEquals("(async)", res.get(0).output());
    }



    @Test
    void testHookOnErrorReject() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "fail", EventName.PRE_TOOL_USE, null,
                new Action(ActionType.COMMAND, "exit 7", null),
                false, false, false, "reject")));

        var result = engine.runPreToolHooks("Bash", Map.of());
        assertTrue(result.rejected(), "A command failure with onError=reject should block the action");
    }



    @Test
    void testValidateCatchesMissingFields() {

        var errors1 = HookEngine.validate(List.of(new Hook(
                "no-cmd", EventName.PRE_TOOL_USE, null,
                new Action(ActionType.COMMAND, null, null),
                false)));
        assertTrue(errors1.stream().anyMatch(e -> e.contains("action.command must be non-empty")));


        var errors2 = HookEngine.validate(List.of(new Hook(
                "no-msg", EventName.SESSION_START, null,
                new Action(ActionType.PROMPT, null, null),
                false)));
        assertTrue(errors2.stream().anyMatch(e -> e.contains("action.message must be non-empty")));


        var errors3 = HookEngine.validate(List.of(new Hook(
                "no-url", EventName.POST_TOOL_USE, null,
                new Action(ActionType.HTTP, null, null, null, null, null, null, Duration.ZERO),
                false)));
        assertTrue(errors3.stream().anyMatch(e -> e.contains("action.url must be non-empty")));


        var errors4 = HookEngine.validate(List.of(new Hook(
                "bad-url", EventName.POST_TOOL_USE, null,
                new Action(ActionType.HTTP, null, null, "not-a-url", null, null, null, Duration.ZERO),
                false)));
        assertTrue(errors4.stream().anyMatch(e -> e.contains("action.url must be a valid http(s) URL")));
    }

    @Test
    void testValidateAcceptsGoodConfig() {
        var hooks = List.of(
                new Hook("fmt", EventName.POST_TOOL_USE, null,
                        new Action(ActionType.COMMAND, "echo ok", null), false),
                new Hook("ctx", EventName.SESSION_START, null,
                        new Action(ActionType.PROMPT, null, "hello"), false),
                new Hook("slack", EventName.POST_TOOL_USE, null,
                        new Action(ActionType.HTTP, null, null, "https://hooks.slack.com/services/xxx",
                                null, null, null, Duration.ZERO), false),
                new Hook("review", EventName.POST_TOOL_USE, null,
                        new Action(ActionType.AGENT, null, "review the change"), false)
        );
        var errors = HookEngine.validate(hooks);
        assertTrue(errors.isEmpty(), "A valid configuration should have no errors: " + errors);
    }



    @Test
    void testRunCommandTimeout() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "slow", EventName.POST_TOOL_USE, null,
                new Action(ActionType.COMMAND, "sleep 5", null, null, null, null, null,
                        Duration.ofMillis(200)),
                false)));

        long start = System.nanoTime();
        var results = engine.runHooks(
                new HookContext(EventName.POST_TOOL_USE, "Bash", null, null, null, null));
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).output().contains("timed out"));
        assertTrue(elapsed < 2000, "The timed-out command should terminate within 200ms; actual: " + elapsed + "ms");
    }



    @Test
    void testCommandInjectsFilePathEnvVar() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "echo-path", EventName.POST_TOOL_USE, null,
                new Action(ActionType.COMMAND, "echo $DEVMESH_FILE_PATH", null),
                false)));

        var results = engine.runHooks(
                new HookContext(EventName.POST_TOOL_USE, "WriteFile", null,
                        "/tmp/test.txt", null, null));
        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals("/tmp/test.txt", results.get(0).output());
    }



    @Test
    void testHookContextExpand() {
        var ctx = new HookContext(
                EventName.POST_TOOL_USE, "Bash",
                Map.of("file", "main.go"),
                "/src/main.go", "hello world", null);

        assertEquals("tool=Bash path=/src/main.go",
                ctx.expand("tool=${tool} path=${file_path}"));
        assertEquals("file=main.go",
                ctx.expand("file=${args.file}"));
        assertEquals("no template here",
                ctx.expand("no template here"));
    }



    @Test
    void testAgentActionWithoutRunner() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "review", EventName.POST_TOOL_USE, null,
                new Action(ActionType.AGENT, null, "review changes"),
                false)));

        var results = engine.runHooks(
                new HookContext(EventName.POST_TOOL_USE, "Bash", null, null, null, null));
        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).output().contains("no AgentRunner registered"));
    }

    @Test
    void testAgentActionWithRunner() {
        var engine = new HookEngine();
        engine.setAgentRunner((prompt, ctx) -> "reviewed: " + prompt);
        engine.loadHooks(List.of(new Hook(
                "review", EventName.POST_TOOL_USE, null,
                new Action(ActionType.AGENT, null, "review changes"),
                false)));

        var results = engine.runHooks(
                new HookContext(EventName.POST_TOOL_USE, "Bash", null, null, null, null));
        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals("reviewed: review changes", results.get(0).output());
    }
}
