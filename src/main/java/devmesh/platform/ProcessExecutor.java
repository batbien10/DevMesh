package devmesh.platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes structured commands without routing every process through a Unix shell. */
public final class ProcessExecutor {
    public ProcessResult execute(CommandSpec spec) {
        return start(spec, ignored -> {}).completion().join();
    }

    public RunningProcess start(CommandSpec spec, ProcessListener listener) {
        Instant started = Instant.now();
        var command = commandLine(spec);
        var completion = new CompletableFuture<ProcessResult>();
        var cancelled = new AtomicBoolean();
        final Process process;
        try {
            var builder = new ProcessBuilder(command);
            if (spec.workingDirectory() != null) builder.directory(spec.workingDirectory().toFile());
            builder.environment().putAll(spec.environment());
            process = builder.start();
            emit(listener, new ProcessEvent.Started(String.join(" ", command)));
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            var result = new ProcessResult(-1, ProcessResult.Status.COMMAND_NOT_FOUND, message, elapsed(started));
            emit(listener, new ProcessEvent.Failed(result.status(), message));
            completion.complete(result);
            return new RunningProcess(new DormantProcess(), completion, cancelled);
        }

        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        var batcher = new ProcessOutputBatcher(event -> emit(listener, event));
        Thread stdoutReader = Thread.startVirtualThread(() -> readStream(process.getInputStream(), false, stdout, batcher));
        Thread stderrReader = Thread.startVirtualThread(() -> readStream(process.getErrorStream(), true, stderr, batcher));
        var running = new RunningProcess(process, completion, cancelled);

        Thread.startVirtualThread(() -> {
            try {
                boolean finished = process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
                stdoutReader.join(1000);
                stderrReader.join(1000);
                ProcessResult result;
                if (!finished) {
                    process.destroy();
                    if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    batcher.flush();
                    emit(listener, new ProcessEvent.TimedOut());
                    result = new ProcessResult(-1, ProcessResult.Status.TIMEOUT,
                            combined(stdout, stderr), elapsed(started));
                } else if (cancelled.get()) {
                    batcher.flush();
                    emit(listener, new ProcessEvent.Cancelled());
                    result = new ProcessResult(-1, ProcessResult.Status.INTERRUPTED,
                            combined(stdout, stderr), elapsed(started));
                } else {
                    batcher.flush();
                    emit(listener, new ProcessEvent.Completed(process.exitValue()));
                    result = new ProcessResult(process.exitValue(), process.exitValue() == 0
                            ? ProcessResult.Status.SUCCESS : ProcessResult.Status.FAILED,
                            combined(stdout, stderr), elapsed(started));
                }
                batcher.close();
                completion.complete(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                batcher.close();
                emit(listener, new ProcessEvent.Cancelled());
                completion.complete(new ProcessResult(-1, ProcessResult.Status.INTERRUPTED,
                        combined(stdout, stderr), elapsed(started)));
            }
        });
        return running;
    }

    private static ArrayList<String> commandLine(CommandSpec spec) {
        var command = new ArrayList<String>();
        if (spec.mode() == CommandSpec.Mode.SHELL) {
            Shell shell = ShellDetector.detect();
            command.add(shell.executable());
            command.addAll(shell.command(spec.executable()));
        } else {
            command.add(spec.executable());
            command.addAll(spec.arguments());
        }
        return command;
    }

    private static void readStream(InputStream stream, boolean error, StringBuilder aggregate, ProcessOutputBatcher batcher) {
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (aggregate) { aggregate.append(line).append('\n'); }
                batcher.accept(error ? new ProcessEvent.ErrorOutput(line) : new ProcessEvent.Output(line));
            }
        } catch (IOException ignored) {
            // The process may close its pipe while being cancelled.
        }
    }

    private static String combined(StringBuilder stdout, StringBuilder stderr) {
        synchronized (stdout) {
            synchronized (stderr) {
                return stdout.toString() + stderr;
            }
        }
    }

    private static void emit(ProcessListener listener, ProcessEvent event) {
        if (listener != null) listener.onEvent(event);
    }

    private static long elapsed(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private static final class DormantProcess extends Process {
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return -1; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return -1; }
        @Override public void destroy() {}
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }
}