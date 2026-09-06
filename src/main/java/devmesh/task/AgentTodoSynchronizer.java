package devmesh.task;

import devmesh.agent.AgentEvent;
import devmesh.platform.ProcessResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/** Deterministically projects actual AgentEvents onto the persisted Todo plan. */
public final class AgentTodoSynchronizer {
    private final TaskList taskList;
    private String currentTaskId;
    private String currentToolId;
    private RepairController repairController;

    public AgentTodoSynchronizer(TaskList taskList) {
        this.taskList = taskList;
    }

    public void setEventQueue(BlockingQueue<AgentEvent> queue) {
        repairController = new RepairController(taskList, queue, 3);
    }

    public void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.ToolUseEvent tool -> onToolStarted(tool);
            case AgentEvent.ToolResultEvent result -> onToolFinished(result);
            case AgentEvent.RetryEvent retry -> onRetry(retry.reason());
            case AgentEvent.ErrorEvent error -> failCurrent("agent_error", error.message());
            case AgentEvent.LoopComplete ignored -> completeCurrent("loop_complete");
            default -> { }
        }
    }

    public String currentTaskId() { return currentTaskId; }

    private void onToolStarted(AgentEvent.ToolUseEvent event) {
        currentToolId = event.toolId();
        String command = event.args() == null ? "" : String.valueOf(event.args().getOrDefault("command", ""));
        String query = (event.toolName() + " " + command).toLowerCase(Locale.ROOT);
        String desired = query.contains("test") ? "test" : query.contains("build") || query.contains("compile") ? "build" : "";
        TaskList.Task task = findTask(desired);
        if (task == null) task = nextPending();
        if (task != null) {
            transition(task, TaskList.Status.IN_PROGRESS, "tool_started:" + event.toolName());
            currentTaskId = task.getId();
        }
    }

    private void onToolFinished(AgentEvent.ToolResultEvent event) {
        if (currentToolId != null && !currentToolId.equals(event.toolId())) return;
        if (currentTaskId == null) return;
        if (event.isError()) {
            failCurrent("tool_failed", event.toolName());
            if (repairController != null) {
                repairController.observeFailure(FailureReport.from(event.toolName(), event.toolName(), 1, event.output()));
            } else {
                addRepairTasks("Repair after " + event.toolName() + " failure");
            }
        } else {
            transitionById(currentTaskId, TaskList.Status.COMPLETED, "tool_completed:" + event.toolName());
            if (repairController != null) {
                repairController.observeRetest(new ProcessResult(0, ProcessResult.Status.SUCCESS, event.output(),
                        (long) (event.elapsed() * 1000)));
            }
        }
        currentToolId = null;
    }

    private void onRetry(String reason) {
        failCurrent("retry", reason);
        addRepairTasks("Diagnose failure", "Fix failure", "Re-run tests");
    }

    private void completeCurrent(String reason) {
        if (currentTaskId != null) {
            transitionById(currentTaskId, TaskList.Status.COMPLETED, reason);
            currentTaskId = null;
        }
    }

    private void failCurrent(String event, String reason) {
        if (currentTaskId != null) transitionById(currentTaskId, TaskList.Status.FAILED, reason);
    }

    private void addRepairTasks(String... subjects) {
        for (String subject : subjects) {
            boolean exists = taskList.list().stream().anyMatch(t -> subject.equals(t.getSubject())
                    && !TaskList.Status.COMPLETED.value().equals(t.getStatus()));
            if (!exists) taskList.create(subject, subject, subject, Map.of("repair", true));
        }
    }

    private TaskList.Task nextPending() {
        return taskList.list().stream()
                .filter(t -> TaskList.Status.PENDING.value().equals(t.getStatus()))
                .findFirst().orElse(null);
    }

    private TaskList.Task findTask(String desired) {
        if (desired.isEmpty()) return null;
        return taskList.list().stream()
                .filter(t -> TaskList.Status.PENDING.value().equals(t.getStatus()))
                .filter(t -> t.getSubject() != null && t.getSubject().toLowerCase(Locale.ROOT).contains(desired))
                .findFirst().orElse(null);
    }

    private void transition(TaskList.Task task, TaskList.Status status, String reason) {
        transitionById(task.getId(), status, reason);
    }

    private void transitionById(String id, TaskList.Status status, String reason) {
        var current = taskList.get(id).orElse(null);
        if (current == null || status.value().equals(current.getStatus())) return;
        taskList.update(id, Map.of("status", status.value()));
        taskList.recordEvent(id, status.value(), reason);
    }
}