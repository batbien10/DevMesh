package devmesh.task;

import devmesh.platform.EnvironmentInspector;
import devmesh.platform.EnvironmentReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Coordinates task understanding and persisted Todo state independently of the TUI. */
public final class SmartOrchestrator {
    private final TaskList taskList;
    private final EnvironmentReport environment;

    public SmartOrchestrator(TaskList taskList) {
        this.taskList = taskList;
        this.environment = EnvironmentInspector.inspect();
    }

    public EnvironmentReport environment() { return environment; }
    public TaskList taskList() { return taskList; }

    public List<TaskList.Task> plan(String request) {
        TaskComplexity complexity = TaskComplexityAnalyzer.analyze(request);
        if (complexity == TaskComplexity.SIMPLE) return List.of();
        var subjects = new ArrayList<String>();
        subjects.add("Understand task and repository");
        subjects.add("Inspect relevant architecture");
        subjects.add("Implement requested change");
        subjects.add("Add or update tests");
        subjects.add("Run build and tests");
        if (complexity == TaskComplexity.COMPLEX) {
            subjects.add("Diagnose and repair failures");
            subjects.add("Review diff and verify completion");
        }
        var result = new ArrayList<TaskList.Task>();
        for (String subject : subjects) {
            result.add(taskList.create(subject, subject, subject, Map.of("orchestrator", true)));
        }
        return List.copyOf(result);
    }

    public TaskList.Task startNext() {
        for (var task : taskList.list()) {
            if (TaskList.Status.PENDING.value().equals(task.getStatus())) {
                taskList.update(task.getId(), Map.of("status", TaskList.Status.IN_PROGRESS.value()));
                return task;
            }
        }
        return null;
    }

    public void complete(String id) {
        taskList.update(id, Map.of("status", TaskList.Status.COMPLETED.value()));
    }

    public void fail(String id) {
        taskList.update(id, Map.of("status", TaskList.Status.FAILED.value()));
    }
}