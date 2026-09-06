package devmesh.task;

import devmesh.agent.AgentEvent;
import devmesh.platform.ProcessResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;
import java.util.function.Supplier;

/** Deterministic diagnose, repair, retest, and verification lifecycle. */
public final class RepairController {
    private final TaskList taskList;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final int maxRetries;
    private final Set<String> attemptedFailures = new java.util.HashSet<>();
    private RepairState state = RepairState.IDLE;
    private int retries;
    private String repairTaskId;

    public RepairController(TaskList taskList, BlockingQueue<AgentEvent> eventQueue, int maxRetries) {
        this.taskList = taskList;
        this.eventQueue = eventQueue;
        this.maxRetries = Math.max(1, maxRetries);
    }

    public RepairState state() { return state; }
    public int retries() { return retries; }

    /** Records a runtime failure and opens a bounded repair cycle for the agent to execute. */
    public synchronized void observeFailure(FailureReport failure) {
        state = RepairState.FAILED;
        emit(new AgentEvent.RepairDiagnosing(failure.intent(), failure.signature()));
        if (!attemptedFailures.add(failure.signature()) || retries >= maxRetries) {
            state = RepairState.FAILED_FINAL;
            failTodo("repeated_failure_or_retry_limit");
            emit(new AgentEvent.RepairFailed(failure.signature(), "retry limit or repeated failure"));
            return;
        }
        retries++;
        repairTaskId = ensureRepairTodo(failure);
        transition(TaskList.Status.IN_PROGRESS, "repair_started");
        state = RepairState.REPAIRING;
        emit(new AgentEvent.RepairPlanned(repairTaskId, failure.diagnostics()));
        emit(new AgentEvent.RepairApplying(repairTaskId));
    }

    /** Completes the runtime-driven repair cycle when a later command succeeds or fails. */
    public synchronized void observeRetest(ProcessResult result) {
        if (repairTaskId == null || state != RepairState.REPAIRING) return;
        state = RepairState.RETESTING;
        emit(new AgentEvent.RepairRetesting(repairTaskId));
        if (result != null && result.succeeded()) {
            state = RepairState.VERIFIED;
            transition(TaskList.Status.COMPLETED, "repair_verified");
            emit(new AgentEvent.RepairSucceeded(repairTaskId));
        } else {
            state = RepairState.FAILED;
            failTodo("retest_failed");
            emit(new AgentEvent.RepairFailed("retest", "retest failed"));
        }
    }

    /**
     * Runs one bounded repair cycle. The repair action is deliberately injected
     * so the existing Agent/permission/sandbox layer remains the authority for
     * applying code changes.
     */
    public synchronized RepairState repair(FailureReport failure,
                                            Function<FailureReport, Boolean> repairAction,
                                            Supplier<ProcessResult> retest) {
        state = RepairState.FAILED;
        emit(new AgentEvent.RepairDiagnosing(failure.intent(), failure.signature()));
        if (!attemptedFailures.add(failure.signature()) || retries >= maxRetries) {
            state = RepairState.FAILED_FINAL;
            failTodo("repeated_failure_or_retry_limit");
            emit(new AgentEvent.RepairFailed(failure.signature(), "retry limit or repeated failure"));
            return state;
        }

        retries++;
        repairTaskId = ensureRepairTodo(failure);
        transition(TaskList.Status.IN_PROGRESS, "repair_started");
        state = RepairState.REPAIR_PLANNING;
        emit(new AgentEvent.RepairPlanned(repairTaskId, failure.diagnostics()));

        state = RepairState.REPAIRING;
        emit(new AgentEvent.RepairApplying(repairTaskId));
        boolean repaired = repairAction != null && Boolean.TRUE.equals(repairAction.apply(failure));
        if (!repaired) {
            state = RepairState.FAILED_FINAL;
            failTodo("repair_action_failed");
            emit(new AgentEvent.RepairFailed(failure.signature(), "repair action failed"));
            return state;
        }

        state = RepairState.RETESTING;
        emit(new AgentEvent.RepairRetesting(repairTaskId));
        ProcessResult result = retest == null ? null : retest.get();
        if (result != null && result.succeeded()) {
            state = RepairState.VERIFIED;
            transition(TaskList.Status.COMPLETED, "repair_verified");
            emit(new AgentEvent.RepairSucceeded(repairTaskId));
        } else {
            state = RepairState.FAILED;
            failTodo("retest_failed");
            emit(new AgentEvent.RepairFailed(failure.signature(), "retest failed"));
        }
        return state;
    }

    private String ensureRepairTodo(FailureReport failure) {
        return taskList.list().stream()
                .filter(task -> task.getSubject() != null
                        && task.getSubject().equals("Repair " + failure.intent()))
                .findFirst()
                .map(TaskList.Task::getId)
                .orElseGet(() -> taskList.create("Repair " + failure.intent(),
                        "Diagnose and repair: " + failure.signature(), "Repairing " + failure.intent(),
                        Map.of("repair", true, "failure_signature", failure.signature())).getId());
    }

    private void transition(TaskList.Status status, String reason) {
        if (repairTaskId == null) return;
        taskList.update(repairTaskId, Map.of("status", status.value()));
        taskList.recordEvent(repairTaskId, status.value(), reason);
    }

    private void failTodo(String reason) {
        transition(TaskList.Status.FAILED, reason);
    }

    private void emit(AgentEvent event) {
        if (eventQueue == null) return;
        eventQueue.offer(event);
    }
}