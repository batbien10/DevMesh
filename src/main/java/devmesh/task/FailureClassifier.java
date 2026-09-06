package devmesh.task;

import devmesh.platform.ProcessResult;

import java.util.Locale;

public final class FailureClassifier {
    private FailureClassifier() {}

    public static FailureType classify(FailureReport failure) {
        String text = (failure.intent() + " " + failure.command() + " " + failure.output()).toLowerCase(Locale.ROOT);
        if (failure.exitCode() == 124 || text.contains("timed out") || text.contains("timeout")) return FailureType.TIMEOUT;
        if (text.contains("cancelled") || text.contains("canceled") || text.contains("interrupted")) return FailureType.CANCELLATION;
        if (text.contains("cannot find symbol") || text.contains("compilation failed") || text.contains("compile error")) return FailureType.COMPILE_ERROR;
        if (text.contains("test failed") || text.contains("tests failed") || text.contains("assertion")) return FailureType.TEST_FAILURE;
        if (text.contains("could not resolve") || text.contains("dependency") || text.contains("npm err")) return FailureType.DEPENDENCY_ERROR;
        if (text.contains("configuration") || text.contains("config error")) return FailureType.CONFIGURATION_ERROR;
        if (text.contains("not found") || text.contains("not recognized") || text.contains("no such file")) return FailureType.ENVIRONMENT_ERROR;
        if (failure.exitCode() != 0 || failure.command() != null && !failure.command().isBlank()) return FailureType.COMMAND_FAILURE;
        return FailureType.UNKNOWN;
    }
}