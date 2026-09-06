package devmesh.platform;

public record ProcessResult(int exitCode, Status status, String output, long durationMillis) {
    public enum Status {
        SUCCESS, COMMAND_NOT_FOUND, PERMISSION_DENIED, TIMEOUT, INTERRUPTED, FAILED
    }

    public boolean succeeded() { return status == Status.SUCCESS && exitCode == 0; }
}