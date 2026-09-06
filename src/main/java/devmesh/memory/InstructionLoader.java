package devmesh.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 *
 * <ol>
 * </ol>
 *
 * <ul>
 * </ul>
 */
public final class InstructionLoader {

    private InstructionLoader() {}

    public static final int MAX_INCLUDE_DEPTH = 5;

    public record InstructionSource(String path, String content) {}

    /**
     */
    public static String loadInstructions(String workDir) {
        List<InstructionSource> sources = discoverInstructions(workDir);
        if (sources.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        Path workPath = Path.of(workDir);
        for (var s : sources) {

            String label = s.path();
            try {
                Path rel = workPath.relativize(Path.of(s.path()));
                if (!rel.toString().startsWith("..")) {
                    label = rel.toString();
                }
            } catch (IllegalArgumentException ignored) {}
            parts.add("Contents of %s:\n\n%s".formatted(label, stripTrailingNewlines(s.content())));
        }
        return String.join("\n\n---\n\n", parts);
    }

    /**
     */
    static List<InstructionSource> discoverInstructions(String workDir) {
        var sources = new ArrayList<InstructionSource>();
        var seen = new HashSet<String>();


        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            addSource(sources, seen, Path.of(home, ".devmesh", "DEVMESH.md"));
            addSource(sources, seen, Path.of(home, ".devmesh", "AGENTS.md"));
        }


        for (String dir : projectInstructionDirs(workDir)) {
            addSource(sources, seen, Path.of(dir, "DEVMESH.md"));
            addSource(sources, seen, Path.of(dir, "AGENTS.md"));
        }


        addSource(sources, seen, Path.of(workDir, ".devmesh", "INSTRUCTIONS.md"));


        addSource(sources, seen, Path.of(workDir, "DEVMESH.local.md"));

        return sources;
    }

    /**
     */
    private static void addSource(List<InstructionSource> out, Set<String> seen, Path path) {
        String abs;
        try {
            abs = path.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return;
        }
        if (seen.contains(abs)) {
            return;
        }
        String content;
        try {
            content = Files.readString(Path.of(abs));
        } catch (IOException e) {
            return;
        }
        seen.add(abs);

        String expanded = expandIncludes(content, Path.of(abs).getParent().toString(), seen, 0);
        out.add(new InstructionSource(abs, expanded));
    }

    /**
     */
    static String expandIncludes(String content, String baseDir, Set<String> seen, int depth) {
        if (depth > MAX_INCLUDE_DEPTH) {
            return content;
        }
        var out = new StringBuilder();
        boolean inCode = false;
        try (var reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (trimmed.startsWith("```")) {
                    inCode = !inCode;
                    out.append(line).append('\n');
                    continue;
                }

                if (!inCode) {
                    String includePath = parseInclude(trimmed);
                    if (includePath != null) {
                        String resolved = resolveInclude(includePath, baseDir);
                        if (resolved != null && !resolved.isEmpty()) {
                            try {
                                String absPath = Path.of(resolved).toAbsolutePath().normalize().toString();
                                if (!seen.contains(absPath)) {
                                    String data = Files.readString(Path.of(absPath));
                                    seen.add(absPath);
                                    out.append("<!-- included from %s -->\n".formatted(includePath));
                                    out.append(expandIncludes(data,
                                            Path.of(absPath).getParent().toString(), seen, depth + 1));
                                    out.append('\n');
                                    continue;
                                }
                            } catch (IOException ignored) {

                            }
                        }
                    }
                }
                out.append(line).append('\n');
            }
        } catch (IOException ignored) {
            return content;
        }
        return out.toString();
    }

    /**
     */
    static String parseInclude(String trimmed) {
        if (!trimmed.startsWith("@") || trimmed.startsWith("@@")) {
            return null;
        }
        String rest = trimmed.substring(1);
        if (rest.isEmpty()) {
            return null;
        }

        if (rest.contains(" ") || rest.contains("\t")) {
            return null;
        }

        if (rest.startsWith("./") || rest.startsWith("../")
                || rest.startsWith("~/") || rest.startsWith("/")) {
            return rest;
        }
        return null;
    }

    /**
     */
    static String resolveInclude(String p, String baseDir) {
        if (p.startsWith("~/")) {
            String home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) {
                return "";
            }
            return Path.of(home, p.substring(2)).toString();
        }
        if (Path.of(p).isAbsolute()) {
            return p;
        }
        return Path.of(baseDir, p).toString();
    }

    /**
     */
    static List<String> projectInstructionDirs(String workDir) {
        String abs;
        try {
            abs = Path.of(workDir).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return List.of(workDir);
        }
        String root = findGitRoot(abs);
        if (root == null || root.isEmpty()) {
            return List.of(abs);
        }

        var dirs = new ArrayList<String>();
        String cur = abs;
        while (true) {
            dirs.addFirst(cur);
            if (cur.equals(root)) {
                break;
            }
            Path parent = Path.of(cur).getParent();
            if (parent == null || parent.toString().equals(cur)) {
                break;
            }
            cur = parent.toString();
        }
        return dirs;
    }

    /**
     */
    private static String findGitRoot(String start) {
        String cur = start;
        while (true) {
            if (Files.exists(Path.of(cur, ".git"))) {
                return cur;
            }
            Path parent = Path.of(cur).getParent();
            if (parent == null || parent.toString().equals(cur)) {
                return null;
            }
            cur = parent.toString();
        }
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end);
    }
}
