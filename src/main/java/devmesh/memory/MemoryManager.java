package devmesh.memory;

import devmesh.conversation.ConversationManager;
import devmesh.conversation.Message;
import devmesh.llm.LlmClient;
import devmesh.llm.StreamEvent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Stream;

/**
 *
 * <ul>
 * </ul>
 *
 */
public class MemoryManager {

    private static final String ENTRYPOINT_NAME = "MEMORY.md";
    private static final int EXTRACTION_INTERVAL = 5;
    private static final String MEMORY_DIR = ".devmesh/memory";


    private static final Set<String> USER_TYPES = Set.of("user", "feedback");
    private static final Set<String> PROJECT_TYPES = Set.of("project", "reference");

    private final Path userMemDirPath;
    private final Path projectMemDirPath;
    private int turnCount;

    public MemoryManager(String workDir) {
        this.projectMemDirPath = Path.of(workDir, MEMORY_DIR);
        this.userMemDirPath = Path.of(System.getProperty("user.home"), MEMORY_DIR);

        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);
    }

    // ---- Directory accessors (for memory recall) ----

    public Path userMemDir() {
        return userMemDirPath;
    }

    public Path projectMemDir() {
        return projectMemDirPath;
    }

    public Path entrypointPath() {
        return projectMemDirPath.resolve(ENTRYPOINT_NAME);
    }

    public Path userEntrypointPath() {
        return userMemDirPath.resolve(ENTRYPOINT_NAME);
    }

    // ---- Accessors ----

    /**
     */
    public List<String> getMemories() {
        var files = loadAll();
        var out = new ArrayList<String>();
        for (var f : files) {
            String typeTag = f.type().isEmpty() ? "?" : f.type();
            String desc = f.description().isEmpty() ? f.filename() : f.description();
            out.add("[%s] %s — %s".formatted(typeTag, f.name(), desc));
        }
        return out;
    }

    public boolean shouldExtract() {
        turnCount++;
        return turnCount % EXTRACTION_INTERVAL == 0;
    }

    /**
     */
    public void clear() {
        clearDir(userMemDirPath);
        clearDir(projectMemDirPath);
    }

    // ---- Memory file record ----

    public record MemoryFile(String path, String filename, String name, String description, String type) {}

    /**
     */
    List<MemoryFile> loadAll() {
        var out = new ArrayList<MemoryFile>();
        out.addAll(loadDir(userMemDirPath));
        out.addAll(loadDir(projectMemDirPath));
        return out;
    }

    private static List<MemoryFile> loadDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            mdFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") && !n.equals(ENTRYPOINT_NAME);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }

        var out = new ArrayList<MemoryFile>();
        for (Path fp : mdFiles) {
            try {
                String content = Files.readString(fp);
                var fm = MemoryScanner.parseFrontmatter(content);
                String name = fm.name().isEmpty()
                        ? fp.getFileName().toString().replace(".md", "")
                        : fm.name();
                out.add(new MemoryFile(
                        fp.toAbsolutePath().toString(),
                        fp.getFileName().toString(),
                        name, fm.description(), fm.type()));
            } catch (IOException ignored) {

            }
        }
        return out;
    }

    private static void clearDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().endsWith(".md"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                  });
        } catch (IOException ignored) {}
    }

    // ---- Build system-reminder section ----

    /**
     */
    public String buildSystemReminder() {
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);

        var sb = new StringBuilder();
        sb.append("# auto memory\n\n");


        appendEntrypoint(sb, "User-level", userMemDirPath);
        sb.append("\n\n");

        appendEntrypoint(sb, "Project-level", projectMemDirPath);

        return sb.toString();
    }

    private static void appendEntrypoint(StringBuilder sb, String scopeLabel, Path memDir) {
        Path ep = memDir.resolve(ENTRYPOINT_NAME);
        sb.append("## %s %s (`%s`)\n\n".formatted(scopeLabel, ENTRYPOINT_NAME, ep));
        try {
            String content = Files.readString(ep).strip();
            if (!content.isEmpty()) {
                sb.append(content);
            } else {
                sb.append("This %s is currently empty.".formatted(ENTRYPOINT_NAME));
            }
        } catch (IOException e) {
            sb.append("This %s is currently empty.".formatted(ENTRYPOINT_NAME));
        }
    }

    // ---- Extraction via LLM ----

    /**
     */
    private String scanExistingMemories() {
        var entries = new ArrayList<String>();
        for (Path dir : List.of(userMemDirPath, projectMemDirPath)) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".md") && !f.getFileName().toString().equals(ENTRYPOINT_NAME))
                     .sorted()
                     .forEach(f -> {
                         try {
                             String content = Files.readString(f);

                             String type = extractField(content, "type");
                             String desc = extractField(content, "description");
                             if (type.isEmpty()) type = "?";
                             if (desc.isEmpty()) desc = f.getFileName().toString();
                             entries.add("- [%s] %s: %s".formatted(type, f.getFileName(), desc));
                         } catch (IOException ignored) {}
                     });
            } catch (IOException ignored) {}
        }
        return String.join("\n", entries);
    }

    /**
     */
    public void extract(LlmClient client, ConversationManager conv) {
        List<Message> messages = conv.getMessages();
        if (messages.size() < 4) return;


        int start = Math.max(0, messages.size() - 40);
        var sb = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            var msg = messages.get(i);
            sb.append('[').append(msg.getRole()).append("]: ").append(msg.getContent()).append('\n');
        }


        String manifest = scanExistingMemories();
        String manifestSection = manifest.isEmpty() ? "" :
                "\n\n## Existing memory files\n\n" + manifest +
                "\n\nCheck this list before creating — update an existing file rather than creating a duplicate.";

        ConversationManager extractConv = new ConversationManager();
        extractConv.addUserMessage(
                "Analyze the conversation below and extract memories worth saving.\n\n"
                + "For each memory, output in this exact format:\n"
                + "MEMORY_NAME: <kebab-case-name>\n"
                + "MEMORY_TYPE: <user|feedback|project|reference>\n"
                + "MEMORY_DESC: <one-line description>\n"
                + "MEMORY_BODY: <content>\n"
                + "---\n\n"
                + "Types:\n"
                + "- user/feedback → save to " + userMemDirPath + "\n"
                + "- project/reference → save to " + projectMemDirPath + "\n\n"
                + "What NOT to save:\n"
                + "- Code patterns derivable from reading the project\n"
                + "- Git history, debugging solutions\n"
                + "- Ephemeral task details\n\n"
                + "If nothing is worth saving, output NONE." + manifestSection + "\n\n"
                + "Conversation:\n" + sb
        );

        BlockingQueue<StreamEvent> events = client.stream(extractConv, null);
        var result = new StringBuilder();
        try {
            while (true) {
                StreamEvent event = events.take();
                if (event instanceof StreamEvent.TextDelta td) {
                    result.append(td.text());
                } else if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String output = result.toString().trim();
        if (output.isEmpty() || output.equals("NONE") || !output.contains("MEMORY_NAME:")) return;


        for (String block : output.split("---")) {
            if (!block.contains("MEMORY_NAME:")) continue;
            String name = extractField(block, "MEMORY_NAME");
            String type = extractField(block, "MEMORY_TYPE");
            String desc = extractField(block, "MEMORY_DESC");
            String body = extractField(block, "MEMORY_BODY");
            if (name.isEmpty() || body.isEmpty()) continue;
            if (!USER_TYPES.contains(type) && !PROJECT_TYPES.contains(type)) type = "reference";

            Path targetDir = USER_TYPES.contains(type) ? userMemDirPath : projectMemDirPath;
            writeMemoryFile(targetDir, name, type, desc, body);
        }
    }

    private static String extractField(String block, String field) {
        var m = java.util.regex.Pattern.compile(field + ":\\s*(.+?)(?:\\n|$)").matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     */
    private void writeMemoryFile(Path dir, String name, String type, String description, String body) {
        ensureDir(dir);
        String filename = name + ".md";
        Path filePath = dir.resolve(filename);

        String fileContent = "---\nname: %s\ndescription: %s\nmetadata:\n  type: %s\n---\n\n%s\n"
                .formatted(name, description, type, body);
        try {
            Files.writeString(filePath, fileContent);
        } catch (IOException e) {
            return;
        }


        Path entrypoint = dir.resolve(ENTRYPOINT_NAME);
        String pointer = "- [%s](%s) — %s\n".formatted(name, filename, description);
        try {
            String existing = Files.exists(entrypoint) ? Files.readString(entrypoint) : "";
            if (!existing.contains(filename)) {
                Files.writeString(entrypoint, existing + pointer);
            }
        } catch (IOException ignored) {}
    }

    /**
     */
    static Map<String, String> parseTypedSections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String currentType = null;
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                if (currentType != null) {
                    String body = buf.toString().trim();
                    if (!body.isEmpty()) {
                        out.merge(currentType, body, (a, b) -> a + "\n" + b);
                    }
                }
                currentType = trimmed.substring(4).trim().toLowerCase(Locale.ROOT);
                buf.setLength(0);
            } else if (currentType != null) {
                buf.append(line).append('\n');
            }
        }
        if (currentType != null) {
            String body = buf.toString().trim();
            if (!body.isEmpty()) {
                out.merge(currentType, body, (a, b) -> a + "\n" + b);
            }
        }
        return out;
    }

    // ---- Injection ----

    /**
     */
    public void injectMemories(ConversationManager conv) {
        String reminder = buildSystemReminder();
        if (reminder.isBlank()) {
            return;
        }
        if (conv.getMessages().isEmpty()) {
            conv.addUserMessage(reminder);
            conv.addAssistantMessage("Understood, I'll keep this context in mind.");
        }
    }

    // ---- Custom instructions ----

    /**
     */
    public static String loadInstructions(String workDir) {
        return InstructionLoader.loadInstructions(workDir);
    }

    // ---- Helpers ----

    private static void ensureDir(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
    }
}
