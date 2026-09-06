package devmesh.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Coalesces rapid output lines while never delaying terminal process events. */
public final class ProcessOutputBatcher implements AutoCloseable {
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "devmesh-process-output");
        thread.setDaemon(true);
        return thread;
    });

    private final Consumer<ProcessEvent> sink;
    private final long intervalMillis;
    private final int maxLines;
    private final int maxChars;
    private final List<ProcessEvent> pending = new ArrayList<>();
    private int pendingChars;
    private boolean scheduled;
    private boolean closed;

    public ProcessOutputBatcher(Consumer<ProcessEvent> sink, long intervalMillis, int maxLines, int maxChars) {
        this.sink = sink;
        this.intervalMillis = Math.max(1, intervalMillis);
        this.maxLines = Math.max(1, maxLines);
        this.maxChars = Math.max(256, maxChars);
    }

    public ProcessOutputBatcher(Consumer<ProcessEvent> sink) {
        this(sink, 40, 32, 16_384);
    }

    public synchronized void accept(ProcessEvent event) {
        if (closed || !(event instanceof ProcessEvent.Output || event instanceof ProcessEvent.ErrorOutput)) return;
        pending.add(event);
        pendingChars += event instanceof ProcessEvent.Output output ? output.text().length()
                : ((ProcessEvent.ErrorOutput) event).text().length();
        if (pending.size() >= maxLines || pendingChars >= maxChars) {
            flushLocked();
        } else if (!scheduled) {
            scheduled = true;
            SCHEDULER.schedule(this::flush, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void flush() {
        flushLocked();
    }

    private void flushLocked() {
        if (pending.isEmpty()) {
            scheduled = false;
            return;
        }
        var events = new ArrayList<>(pending);
        pending.clear();
        pendingChars = 0;
        scheduled = false;
        for (int i = 0; i < events.size(); i++) {
            ProcessEvent first = events.get(i);
            StringBuilder text = new StringBuilder(textOf(first));
            boolean stderr = first instanceof ProcessEvent.ErrorOutput;
            while (i + 1 < events.size()) {
                ProcessEvent next = events.get(i + 1);
                boolean nextStderr = next instanceof ProcessEvent.ErrorOutput;
                if (nextStderr != stderr) break;
                text.append('\n').append(textOf(next));
                i++;
            }
            sink.accept(stderr ? new ProcessEvent.ErrorOutput(text.toString()) : new ProcessEvent.Output(text.toString()));
        }
    }

    private static String textOf(ProcessEvent event) {
        return event instanceof ProcessEvent.Output output ? output.text()
                : ((ProcessEvent.ErrorOutput) event).text();
    }

    @Override
    public synchronized void close() {
        flushLocked();
        closed = true;
    }
}