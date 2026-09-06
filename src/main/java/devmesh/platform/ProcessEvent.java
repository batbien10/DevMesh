package devmesh.platform;

public sealed interface ProcessEvent permits ProcessEvent.Started, ProcessEvent.Output,
        ProcessEvent.ErrorOutput, ProcessEvent.Completed, ProcessEvent.TimedOut, ProcessEvent.Cancelled,
        ProcessEvent.Failed {
    record Started(String command) implements ProcessEvent {}
    record Output(String text) implements ProcessEvent {}
    record ErrorOutput(String text) implements ProcessEvent {}
    record Completed(int exitCode) implements ProcessEvent {}
    record TimedOut() implements ProcessEvent {}
    record Cancelled() implements ProcessEvent {}
    record Failed(ProcessResult.Status status, String message) implements ProcessEvent {}
}