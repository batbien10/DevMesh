package devmesh.task;

import devmesh.agent.AgentEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentTodoSynchronizerTest {
    @Test
    void toolEventsDriveTestTodoAndRecordHistory() throws Exception {
        var root = Files.createTempDirectory("devmesh-events");
        var list = new TaskList("events", root.toString());
        var task = list.create("Run tests", "Run tests", "Running tests", Map.of());
        var sync = new AgentTodoSynchronizer(list);

        sync.onEvent(new AgentEvent.ToolUseEvent("1", "Bash", Map.of("command", "gradlew test")));
        assertEquals(TaskList.Status.IN_PROGRESS.value(), list.get(task.getId()).orElseThrow().getStatus());
        sync.onEvent(new AgentEvent.ToolResultEvent("1", "Bash", "ok", false, 0.1));
        var completed = list.get(task.getId()).orElseThrow();
        assertEquals(TaskList.Status.COMPLETED.value(), completed.getStatus());
        assertTrue(completed.getMetadata().containsKey("event_history"));
    }

    @Test
    void failuresCreateRepairTasksAndRetryCanContinue() throws Exception {
        var root = Files.createTempDirectory("devmesh-events");
        var list = new TaskList("events", root.toString());
        var task = list.create("Run tests", "Run tests", "Running tests", Map.of());
        var sync = new AgentTodoSynchronizer(list);
        sync.onEvent(new AgentEvent.ToolUseEvent("1", "Bash", Map.of("command", "gradlew test")));
        sync.onEvent(new AgentEvent.ToolResultEvent("1", "Bash", "failed", true, 0.1));
        assertEquals(TaskList.Status.FAILED.value(), list.get(task.getId()).orElseThrow().getStatus());
        sync.onEvent(new AgentEvent.RetryEvent("test failure", 0));
        assertTrue(list.list().stream().anyMatch(t -> t.getSubject().equals("Diagnose failure")));
        assertTrue(list.list().stream().anyMatch(t -> t.getSubject().equals("Re-run tests")));
    }
}