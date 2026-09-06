package devmesh.command;

import devmesh.command.Command.CommandType;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;

/**
 *
 */
public final class CommandLoader {

    private CommandLoader() {}

    /**
     */
    public static List<Command> loadUserCommands(String workDir) {
        List<String> dirs = new ArrayList<>();

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            dirs.add(Path.of(home, ".devmesh", "commands").toString());
        }
        dirs.add(Path.of(workDir, ".devmesh", "commands").toString());


        Map<String, CommandWithHandler> merged = new LinkedHashMap<>();
        for (String dir : dirs) {
            for (var entry : loadDir(dir)) {
                merged.put(entry.cmd.name(), entry);
            }
        }
        return List.copyOf(merged.values().stream().map(e -> e.cmd).toList());
    }

    /**
     */
    public static void registerUserCommands(CommandRegistry registry, String workDir) {
        List<String> dirs = new ArrayList<>();

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            dirs.add(Path.of(home, ".devmesh", "commands").toString());
        }
        dirs.add(Path.of(workDir, ".devmesh", "commands").toString());


        Map<String, CommandWithHandler> merged = new LinkedHashMap<>();
        for (String dir : dirs) {
            for (var entry : loadDir(dir)) {
                merged.put(entry.cmd.name(), entry);
            }
        }

        for (var entry : merged.values()) {

            if (registry.hasConflict(entry.cmd)) {
                continue;
            }
            registry.register(entry.cmd, entry.handler);
        }
    }



    private record CommandWithHandler(Command cmd, Function<CommandContext, String> handler) {}

    /**
     */
    private static List<CommandWithHandler> loadDir(String dir) {
        Path dirPath = Path.of(dir);
        if (!Files.isDirectory(dirPath)) {
            return List.of();
        }

        List<CommandWithHandler> results = new ArrayList<>();
        try {
            Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.toString().endsWith(".md")) {
                        return FileVisitResult.CONTINUE;
                    }
                    var entry = parseCommandFile(dirPath, file);
                    if (entry != null) {
                        results.add(entry);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {

        }
        return results;
    }

    /**
     */
    private static CommandWithHandler parseCommandFile(Path baseDir, Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return null;
        }


        Path rel = baseDir.relativize(file);
        String relStr = rel.toString();
        if (relStr.toLowerCase().endsWith(".md")) {
            relStr = relStr.substring(0, relStr.length() - 3);
        }

        String[] parts = relStr.split("[/\\\\]");
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) nameBuilder.append(':');
            nameBuilder.append(parts[i].toLowerCase().replace(' ', '-'));
        }
        String name = nameBuilder.toString();
        if (name.isEmpty()) {
            return null;
        }


        var parsed = splitFrontmatter(content);
        String body = parsed.body.strip();
        String description = parsed.meta.description;
        String[] aliases = parsed.meta.aliases != null ? parsed.meta.aliases : new String[0];


        if (description == null || description.isBlank()) {
            description = firstNonHeaderLine(body);
        }
        if (description == null) {
            description = "";
        }

        Command cmd = new Command(name, description, aliases, CommandType.PROMPT, false);

        Function<CommandContext, String> handler = promptHandler(body);

        return new CommandWithHandler(cmd, handler);
    }

    /**
     */
    private static Function<CommandContext, String> promptHandler(String body) {
        return ctx -> {
            String args = ctx.args();
            if (body.contains("$ARGUMENTS")) {
                return body.replace("$ARGUMENTS", args != null ? args : "");
            }
            if (args == null || args.isBlank()) {
                return body;
            }
            return body + "\n\n## User Request\n\n" + args;
        };
    }



    private record CommandMeta(String description, String argumentHint, String[] aliases) {}
    private record ParsedFile(CommandMeta meta, String body) {}

    @SuppressWarnings("unchecked")
    private static ParsedFile splitFrontmatter(String content) {
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) {
            return new ParsedFile(new CommandMeta(null, null, null), content);
        }


        String[] parts = content.split("---", 3);
        if (parts.length < 3) {
            return new ParsedFile(new CommandMeta(null, null, null), content);
        }

        String yamlBlock = parts[1];
        String body = parts[2];

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(yamlBlock);
            if (map == null) {
                return new ParsedFile(new CommandMeta(null, null, null), body);
            }

            String description = map.get("description") instanceof String s ? s : null;
            String argumentHint = map.get("argument-hint") instanceof String s ? s : null;

            String[] aliases = null;
            Object rawAliases = map.get("aliases");
            if (rawAliases instanceof List<?> list) {
                aliases = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toArray(String[]::new);
            }

            return new ParsedFile(new CommandMeta(description, argumentHint, aliases), body);
        } catch (Exception e) {
            return new ParsedFile(new CommandMeta(null, null, null), content);
        }
    }

    /**
     */
    private static String firstNonHeaderLine(String body) {
        for (String line : body.split("\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                return stripped;
            }
        }
        return null;
    }
}
