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

    @Test
    void diagnosisClassifiesFailureAndPreservesVerificationPlan() throws Exception {
        var root = Files.createTempDirectory("devmesh-repair");
        var list = new TaskList("repair", root.toString());
        var events = new LinkedBlockingQueue<AgentEvent>();
        var controller = new RepairController(list, events, 2);
        var failure = FailureReport.from("tests", "gradlew test", 1, "Tests failed: expiredToken assertion");
        controller.observeFailure(failure);
        assertEquals(FailureType.TEST_FAILURE, controller.lastDiagnosis().type());
        assertTrue(controller.repairPlan().stream().anyMatch(step -> step.contains("targeted")));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.RepairDiagnosisCompleted));
    }

    @Test
    void cancellationBlocksRepairTodoAndEmitsLifecycleEvent() throws Exception {
        var root = Files.createTempDirectory("devmesh-repair");
        var list = new TaskList("repair", root.toString());
        var events = new LinkedBlockingQueue<AgentEvent>();
        var controller = new RepairController(list, events, 2);
        controller.observeFailure(FailureReport.from("build", "gradlew build", 1, "compile error"));
        controller.cancel();
        assertEquals(RepairState.CANCELLED, controller.state());
        assertTrue(list.list().stream().anyMatch(t -> TaskList.Status.BLOCKED.value().equals(t.getStatus())));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.RepairCancelled));
    }

    @Test
    void realRepairActionChangesFileAndRetestVerifiesIt() throws Exception {
        var root = Files.createTempDirectory("devmesh-repair");
        var target = root.resolve("result.txt");
        Files.writeString(target, "broken");
        var list = new TaskList("repair", root.toString());
        var controller = new RepairController(list, new LinkedBlockingQueue<>(), 2);
        var state = controller.repair(FailureReport.from("tests", "verification", 1, "expected fixed"),
                failure -> {
                    try {
                        Files.writeString(target, "fixed");
                        return true;
                    } catch (java.io.IOException e) {
                        return false;
                    }
                },
                () -> {
                    try {
                        boolean fixed = "fixed".equals(Files.readString(target));
                        return new ProcessResult(fixed ? 0 : 1,
                                fixed ? ProcessResult.Status.SUCCESS : ProcessResult.Status.FAILED,
                                Files.readString(target), 1);
                    } catch (java.io.IOException e) {
                        return new ProcessResult(1, ProcessResult.Status.FAILED, e.getMessage(), 1);
                    }
                });
        assertEquals(RepairState.VERIFIED, state);
        assertEquals("fixed", Files.readString(target));
    }
}