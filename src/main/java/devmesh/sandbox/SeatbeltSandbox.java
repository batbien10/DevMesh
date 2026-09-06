package devmesh.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 */
public class SeatbeltSandbox implements Sandbox {


    private static final String SANDBOX_EXEC_PATH = "/usr/bin/sandbox-exec";

    @Override
    public boolean isAvailable() {
        return Files.exists(Path.of(SANDBOX_EXEC_PATH));
    }

    @Override
    public String wrap(String command, SandboxConfig config) {
        String profile = buildProfile(config);

        return "%s -p '%s' bash -c %s".formatted(
                SANDBOX_EXEC_PATH, profile, shellQuote(command));
    }

    /**
     */
    static String buildProfile(SandboxConfig config) {
        var sb = new StringBuilder();

        sb.append("(version 1)\n");
        sb.append("(deny default)\n");


        sb.append("(allow process-exec)\n");
        sb.append("(allow process-fork)\n");

        sb.append("(allow sysctl-read)\n");

        sb.append("(allow file-read* (subpath \"/\"))\n");


        for (String path : config.getAllowWrite()) {
            sb.append("(allow file-write* (subpath \"%s\"))\n".formatted(path));
        }



        for (String path : config.getDenyWrite()) {
            var f = new java.io.File(path);
            String matcher = f.isDirectory() ? "subpath" : "literal";
            sb.append("(deny file-write* (%s \"%s\"))\n".formatted(matcher, path));
        }


        if (config.isNetworkEnabled()) {
            sb.append("(allow network*)\n");
        } else {
            sb.append("(deny network*)\n");
        }

        return sb.toString();
    }

    /**
     */
    private static String shellQuote(String s) {

        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("$", "\\$")
                       .replace("`", "\\`")
                       .replace("!", "\\!") + "\"";
    }
}
