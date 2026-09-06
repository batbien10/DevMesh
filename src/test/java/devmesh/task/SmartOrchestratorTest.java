package devmesh.task;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class SmartOrchestratorTest {
    @Test
    void simpleRequestsDoNotCreatePlans() throws Exception {
        var root = Files.createTempDirectory("devmesh-task");
        var orchestrator = new SmartOrchestrator(new TaskList("test", root.toString()));
        assertTrue(orchestrator.plan("Fix typo").isEmpty());
    }

    @Test
    void complexRequestsCreatePersistedPlanAndAdvanceOneTask() throws Exception {
        var root = Files.createTempDirectory("devmesh-task");
        var list = new TaskList("test", root.toString());
        var orchestrator = new SmartOrchestrator(list);
        var tasks = orchestrator.plan("Add authentication to the API with tests and documentation");
        assertTrue(tasks.size() >= 6);
        var current = orchestrator.startNext();
        assertNotNull(current);
        assertEquals(TaskList.Status.IN_PROGRESS.value(), list.get(current.getId()).orElseThrow().getStatus());
        orchestrator.complete(current.getId());
        assertEquals(TaskList.Status.COMPLETED.value(), list.get(current.getId()).orElseThrow().getStatus());
        assertFalse(list.list().isEmpty());
    }
}