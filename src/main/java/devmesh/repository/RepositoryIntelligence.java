package devmesh.repository;

import devmesh.platform.ProjectDetector;
import devmesh.platform.ProjectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Metadata-first repository map and lightweight structural index. */
public final class RepositoryIntelligence {
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".gradle", ".idea", ".vscode", "node_modules", "build", "target",
            "dist", "coverage", "out", "generated");
    private static final Map<String, String> LANGUAGE_EXTENSIONS = Map.ofEntries(
            Map.entry(".java", "Java"), Map.entry(".kt", "Kotlin"), Map.entry(".kts", "Kotlin"),
            Map.entry(".js", "JavaScript"), Map.entry(".ts", "TypeScript"), Map.entry(".py", "Python"),
            Map.entry(".rs", "Rust"), Map.entry(".go", "Go"), Map.entry(".c", "C"),
            Map.entry(".cpp", "C++"), Map.entry(".h", "C/C++"), Map.entry(".md", "Markdown"),
            Map.entry(".yml", "YAML"), Map.entry(".yaml", "YAML"), Map.entry(".sh", "Shell"));
    private static final Pattern JAVA_TYPE = Pattern.compile("\\b(class|interface|record|enum)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile("(?:public|private|protected|static|final|synchronized|native|abstract|default|\\s)+[A-Za-z_$][\\w$<>?,.\\[\\]]*\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern JAVA_FIELD = Pattern.compile("(?:public|private|protected|static|final|volatile|transient|\\s)+[A-Za-z_$][\\w$<>?,.\\[\\]]*\\s+([A-Za-z_$][\\w$]*)\\s*(?:=|;)");
    private static final Pattern JAVA_PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)");
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.*]+)");

    private final Path root;
    private RepositoryMap cached;
    private long cachedFingerprint;

    public RepositoryIntelligence(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public synchronized RepositoryMap analyze(AnalysisDepth depth) {
        long fingerprint = fingerprint();
        if (cached != null && cachedFingerprint == fingerprint && depth != AnalysisDepth.DEEP) return cached;
        long started = System.nanoTime();
        var files = scanFiles();
        ProjectType projectType = ProjectDetector.detect(root);
        var languages = new HashSet<String>();
        var symbols = new ArrayList<RepositorySymbol>();
        var imports = new LinkedHashMap<String, List<String>>();
        List<String> sourceRoots = new ArrayList<>();
        List<String> testRoots = new ArrayList<>();
        var configs = new ArrayList<String>();
        var generated = new ArrayList<String>();
        var important = new ArrayList<String>();
        var entryPoints = new ArrayList<String>();
        var testRelationships = new LinkedHashMap<String, String>();

        for (Path file : files) {
            String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
            String name = file.getFileName().toString();
            String language = languageFor(name);
            if (language != null) languages.add(language);
            if (isConfig(name, relative)) configs.add(relative);
            if (isGenerated(relative)) generated.add(relative);
            if (isSource(relative)) sourceRoots.add(sourceRoot(relative, "src/main"));
            if (isTest(relative)) testRoots.add(sourceRoot(relative, "src/test"));
            if (isImportant(name, relative)) important.add(relative);
            if (depth != AnalysisDepth.QUICK && isAnalyzable(file)) indexJava(file, relative, symbols, imports, entryPoints);
        }
        sourceRoots = distinctSorted(sourceRoots);
        testRoots = distinctSorted(testRoots);
        for (RepositorySymbol symbol : symbols) {
            if (symbol.kind().equals("class") || symbol.kind().equals("record")) {
                String test = likelyTestFor(symbol.path(), symbol.name(), files);
                if (test != null) testRelationships.put(symbol.path(), test);
            }
        }
        var map = new RepositoryMap(root, projectType.name(), languages.stream().sorted().toList(),
                buildSystems(projectType), modules(projectType), sourceRoots, testRoots, configs, generated,
                important, entryPoints, symbols, imports, testRelationships, gitState(), files.size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        cached = map;
        cachedFingerprint = fingerprint;
        return map;
    }

    public synchronized List<RepositorySearchResult> search(String query, AnalysisDepth depth) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).strip();
        RepositoryMap map = analyze(depth);
        var results = new ArrayList<RepositorySearchResult>();
        for (RepositorySymbol symbol : map.symbols()) {
            String name = symbol.name().toLowerCase(Locale.ROOT);
            String path = symbol.path().toLowerCase(Locale.ROOT);
            double score = name.equals(normalized) ? 1.0 : name.startsWith(normalized) ? .9
                    : name.contains(normalized) ? .7 : path.contains(normalized) ? .4 : 0;
            if (score > 0) results.add(new RepositorySearchResult(symbol.path(), symbol.name(), symbol.line(), score, symbol.confidence()));
        }
        results.sort(Comparator.comparingDouble(RepositorySearchResult::relevance).reversed()
                .thenComparing(RepositorySearchResult::path));
        return List.copyOf(results);
    }

    public synchronized void invalidate() { cached = null; cachedFingerprint = 0; }

    private List<Path> scanFiles() {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).filter(path -> !ignored(path)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean ignored(Path path) {
        for (Path part : root.relativize(path)) if (SKIP_DIRS.contains(part.toString())) return true;
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative.endsWith("/.gitignore") || relative.contains("/.git/");
    }

    private long fingerprint() {
        return scanFiles().stream().mapToLong(path -> {
            try { return Files.getLastModifiedTime(path).toMillis() ^ Files.size(path); }
            catch (IOException e) { return path.hashCode(); }
        }).reduce(0, (a, b) -> a * 31 + b);
    }

    private static boolean isAnalyzable(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts");
    }

    private static void indexJava(Path file, String relative, List<RepositorySymbol> symbols,
                                  Map<String, List<String>> imports, List<String> entryPoints) {
        try {
            String packageName = "";
            var fileImports = new ArrayList<String>();
            var lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                var packageMatch = JAVA_PACKAGE.matcher(line);
                if (packageMatch.find()) packageName = packageMatch.group(1);
                var importMatch = JAVA_IMPORT.matcher(line);
                if (importMatch.find()) fileImports.add(importMatch.group(1));
                var type = JAVA_TYPE.matcher(line);
                if (type.find()) symbols.add(new RepositorySymbol(type.group(2), type.group(1), relative, i + 1, packageName, SymbolConfidence.STRUCTURAL));
                var method = JAVA_METHOD.matcher(line);
                while (method.find()) symbols.add(new RepositorySymbol(method.group(1), "method", relative, i + 1, packageName, SymbolConfidence.STRUCTURAL));
                var field = JAVA_FIELD.matcher(line);
                while (field.find()) symbols.add(new RepositorySymbol(field.group(1), "field", relative, i + 1, packageName, SymbolConfidence.HEURISTIC));
                if (line.contains("static void main(String[]") || line.contains("static void main(String ...")) entryPoints.add(relative + ":" + (i + 1));
            }
            imports.put(relative, List.copyOf(fileImports));
        } catch (IOException ignored) {}
    }

    private List<RepositoryModule> modules(ProjectType type) {
        var modules = new ArrayList<RepositoryModule>();
        Path settings = Files.exists(root.resolve("settings.gradle.kts")) ? root.resolve("settings.gradle.kts") : root.resolve("settings.gradle");
        if (type == ProjectType.GRADLE && Files.exists(settings)) {
            try {
                for (String line : Files.readAllLines(settings)) {
                    if (line.contains("include(")) for (String part : line.substring(line.indexOf('(') + 1).replace(")", "").split(",")) {
                        String name = part.replace("\"", "").replace("'", "").replace(":", "").strip();
                        if (!name.isEmpty()) modules.add(new RepositoryModule(name, name.replace(':', '/'), List.of()));
                    }
                }
            } catch (IOException ignored) {}
        }
        if (modules.isEmpty()) modules.add(new RepositoryModule("root", ".", List.of()));
        return modules;
    }

    private List<String> buildSystems(ProjectType type) {
        return type == ProjectType.UNKNOWN ? List.of() : List.of(type.name());
    }

    private Map<String, String> gitState() {
        var state = new LinkedHashMap<String, String>();
        state.put("root", root.toString());
        state.put("branch", runGit("rev-parse", "--abbrev-ref", "HEAD"));
        state.put("status", runGit("status", "--short"));
        return state;
    }

    private String runGit(String... args) {
        try {
            var command = new ArrayList<String>(); command.add("git"); command.add("-C"); command.add(root.toString()); command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.waitFor(2, TimeUnit.SECONDS);
            return new String(process.getInputStream().readAllBytes()).strip();
        } catch (Exception e) { return "unknown"; }
    }

    private static String languageFor(String name) {
        for (var entry : LANGUAGE_EXTENSIONS.entrySet()) if (name.endsWith(entry.getKey())) return entry.getValue();
        return null;
    }

    private static boolean isConfig(String name, String relative) {
        return Set.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "pom.xml",
                "package.json", "Cargo.toml", "go.mod", "pyproject.toml", "requirements.txt", "CMakeLists.txt", "Makefile", "AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md").contains(name)
                || relative.startsWith(".github/workflows/");
    }

    private static boolean isGenerated(String relative) { return relative.startsWith("generated/") || relative.startsWith("build/") || relative.startsWith("target/"); }
    private static boolean isSource(String relative) { return relative.contains("/src/main/") || relative.startsWith("src/main/") || relative.startsWith("src/") && !isTest(relative); }
    private static boolean isTest(String relative) { return relative.contains("/src/test/") || relative.startsWith("src/test/") || relative.startsWith("tests/") || relative.startsWith("test/"); }
    private static boolean isImportant(String name, String relative) { return isConfig(name, relative) || name.equals("README.md") || name.equals("gradlew") || name.equals("gradlew.bat"); }
    private static String parentRoot(String relative) { int slash = relative.indexOf('/'); return slash < 0 ? "." : relative.substring(0, slash); }
    private static String sourceRoot(String relative, String marker) {
        int index = relative.indexOf(marker + "/");
        return index < 0 ? parentRoot(relative) : relative.substring(0, index + marker.length());
    }
    private static List<String> distinctSorted(List<String> values) { return values.stream().distinct().sorted().toList(); }

    private static String likelyTestFor(String path, String symbol, List<Path> files) {
        String candidate = symbol + "Test";
        return files.stream().filter(file -> file.getFileName().toString().equals(candidate + ".java"))
                .map(file -> file.toString().replace('\\', '/')).findFirst().orElse(null);
    }
}