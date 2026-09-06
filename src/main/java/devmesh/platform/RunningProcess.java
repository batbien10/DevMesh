package devmesh.platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RunningProcess {
    private final Process process;
    private final CompletableFuture<ProcessResult> completion;
    private final AtomicBoolean cancelled;

    RunningProcess(Process process, CompletableFuture<ProcessResult> completion, AtomicBoolean cancelled) {
        this.process = process;
        this.completion = completion;
        this.cancelled = cancelled;
    }

    public CompletableFuture<ProcessResult> completion() { return completion; }

    public void cancel() {
        cancelled.set(true);
        if (process.isAlive()) {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}