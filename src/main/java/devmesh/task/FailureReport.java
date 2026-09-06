package devmesh.task;

import java.util.List;

public record FailureReport(
        String intent,
        String command,
        int exitCode,
        String output,
        String signature,
        List<String> diagnostics
) {
    public FailureReport {
        intent = intent == null ? "command" : intent;
        command = command == null ? "" : command;
        output = output == null ? "" : output;
        signature = signature == null || signature.isBlank()
                ? intent + ":" + exitCode + ":" + firstLine(output) : signature;
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public static FailureReport from(String intent, String command, int exitCode, String output) {
        return new FailureReport(intent, command, exitCode, output, null,
                output == null || output.isBlank() ? List.of() : List.of(firstLine(output)));
    }

    private static String firstLine(String value) {
        if (value == null) return "";
        return value.lines().findFirst().orElse("").strip();
    }
}