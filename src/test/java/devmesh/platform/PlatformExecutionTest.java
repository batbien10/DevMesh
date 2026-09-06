package devmesh.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlatformExecutionTest {
    @Test
    void shellBuildsPlatformSpecificArgumentsWithoutConcatenatingUserArguments() {
        var shell = new Shell("test", "shell", List.of("-c"));
        assertEquals(List.of("-c", "echo hello && echo world"), shell.command("echo hello && echo world"));
    }

    @Test
    void detectsGradleWrapperAndResolvesTestIntent() throws Exception {
        Path root = Files.createTempDirectory("devmesh-project");
        try {
            Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }");
            String wrapper = OperatingSystem.detect() == OperatingSystem.WINDOWS ? "gradlew.bat" : "gradlew";
            Files.writeString(root.resolve(wrapper), "");
            var spec = new CommandResolver(root).resolve(CommandIntent.TEST);
            assertEquals(root.resolve(wrapper).toString(), spec.executable());
            assertEquals(List.of("test"), spec.arguments());
            assertEquals(root, spec.workingDirectory());
            assertEquals(CommandSpec.Mode.DIRECT, spec.mode());
        } finally {
            Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void executesDirectProcessWithWorkingDirectoryAndTimeout() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                OperatingSystem.detect() == OperatingSystem.WINDOWS ? "java.exe" : "java").toString();
        var spec = new CommandSpec(executable, List.of("-version"), Path.of("."), Map.of(),
                Duration.ofSeconds(10), CommandSpec.Mode.DIRECT);
        var result = new ProcessExecutor().execute(spec);
        assertEquals(ProcessResult.Status.SUCCESS, result.status());
        assertTrue(result.output().contains("version"));
    }

        @Test
        void streamsStdoutAndStderrBeforeCompletion() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
            OperatingSystem.detect() == OperatingSystem.WINDOWS ? "java.exe" : "java").toString();
        var events = new CopyOnWriteArrayList<ProcessEvent>();
        var spec = new CommandSpec(java, List.of("-cp", System.getProperty("java.class.path"),
            StreamingProcessFixture.class.getName()), Path.of("."), Map.of(), Duration.ofSeconds(10), CommandSpec.Mode.DIRECT);
        var running = new ProcessExecutor().start(spec, events::add);
        assertTrue(waitForEvent(events, ProcessEvent.Output.class, 3, TimeUnit.SECONDS));
        assertFalse(running.completion().isDone());
        var result = running.completion().join();
        assertTrue(result.succeeded());
        assertTrue(events.stream().anyMatch(e -> e instanceof ProcessEvent.ErrorOutput error
            && error.text().equals("error 2")));
        assertTrue(events.stream().anyMatch(e -> e instanceof ProcessEvent.Output output
            && output.text().equals("line 3")));
        }

        @Test
        void timeoutAndCancellationProduceDistinctResults() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
            OperatingSystem.detect() == OperatingSystem.WINDOWS ? "java.exe" : "java").toString();
        var timeoutSpec = new CommandSpec(java, List.of("-cp", System.getProperty("java.class.path"),
            StreamingProcessFixture.class.getName()), Path.of("."), Map.of(), Duration.ofMillis(10), CommandSpec.Mode.DIRECT);
        assertEquals(ProcessResult.Status.TIMEOUT, new ProcessExecutor().execute(timeoutSpec).status());

        var running = new ProcessExecutor().start(new CommandSpec(java, List.of("-cp", System.getProperty("java.class.path"),
            StreamingProcessFixture.class.getName()), Path.of("."), Map.of(), Duration.ofSeconds(10), CommandSpec.Mode.DIRECT), ignored -> {});
        running.cancel();
        assertTrue(running.completion().join().status() != ProcessResult.Status.SUCCESS);
        }

    @Test
    void batchesRapidOutputAndFlushesExplicitly() throws Exception {
        var events = new CopyOnWriteArrayList<ProcessEvent>();
        var batcher = new ProcessOutputBatcher(events::add, 500, 100, 10_000);
        for (int i = 0; i < 20; i++) batcher.accept(new ProcessEvent.Output("line " + i));
        assertTrue(events.isEmpty());
        batcher.flush();
        batcher.close();
        assertEquals(1, events.size());
        assertTrue(((ProcessEvent.Output) events.getFirst()).text().contains("line 19"));
    }

        private static boolean waitForEvent(List<ProcessEvent> events, Class<?> type, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (events.stream().anyMatch(type::isInstance)) return true;
            Thread.sleep(10);
        }
        return false;
        }
}