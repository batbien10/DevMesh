package devmesh.task;

import devmesh.agent.AgentEvent;
import devmesh.platform.ProcessResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class RepairControllerTest {
    @Test
    void successfulRepairRunsDiagnoseApplyRetestAndCompletesTodo() throws Exception {
        var root = Files.createTempDirectory("devmesh-repair");
        var list = new TaskList("repair", root.toString());
        var events = new LinkedBlockingQueue<AgentEvent>();
        var controller = new RepairController(list, events, 2);
        var state = controller.repair(FailureReport.from("tests", "gradlew test", 1, "Test failed"),
                failure -> true,
                () -> new ProcessResult(0, ProcessResult.Status.SUCCESS, "ok", 1));
        assertEquals(RepairState.VERIFIED, state);
        assertTrue(list.list().stream().anyMatch(t -> TaskList.Status.COMPLETED.value().equals(t.getStatus())));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.RepairDiagnosing));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.RepairSucceeded));
    }

    @Test
    void repeatedFailureStopsAtFinalStateAndDoesNotLoop() throws Exception {
        var root = Files.createTempDirectory("devmesh-repair");
        var list = new TaskList("repair", root.toString());
        var events = new LinkedBlockingQueue<AgentEvent>();
        var controller = new RepairController(list, events, 3);
        var failure = FailureReport.from("build", "gradlew build", 1, "same failure");
        assertEquals(RepairState.FAILED_FINAL, controller.repair(failure, ignored -> false, () -> null));
        assertEquals(RepairState.FAILED_FINAL, controller.repair(failure, ignored -> true,
                () -> new ProcessResult(0, ProcessResult.Status.SUCCESS, "", 0)));
        assertEquals(1, controller.retries());
        assertTrue(list.list().stream().anyMatch(t -> TaskList.Status.FAILED.value().equals(t.getStatus())));
    }
}