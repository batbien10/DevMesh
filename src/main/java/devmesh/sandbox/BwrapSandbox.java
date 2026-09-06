package devmesh.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 */
public class BwrapSandbox implements Sandbox {

    @Override
    public boolean isAvailable() {

        try {
            Process p = new ProcessBuilder("which", "bwrap")
                    .redirectErrorStream(true)
                    .start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String wrap(String command, SandboxConfig config) {
        var args = new ArrayList<String>();


        args.addAll(List.of("bwrap", "--unshare-user", "--unshare-pid"));


        args.addAll(List.of("--ro-bind", "/", "/"));


        for (String path : config.getAllowWrite()) {
            args.addAll(List.of("--bind", path, path));
        }


        for (String path : config.getDenyWrite()) {
            args.addAll(List.of("--ro-bind", path, path));
        }


        if (!config.isNetworkEnabled()) {
            args.add("--unshare-net");
        }


        args.addAll(List.of("--proc", "/proc"));


        args.addAll(List.of("--", "bash", "-c", command));


        var sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            String arg = args.get(i);

            if (arg.matches(".*[ \\t\\n\"'\\\\$`!].*")) {
                sb.append("'").append(arg.replace("'", "'\\''")).append("'");
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }
}
