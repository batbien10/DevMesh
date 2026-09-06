package devmesh.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * <p>
 * <p>
 */
public final class SkillInstaller {


    private static final int MAX_FILE_SIZE = 1 << 20;       // 1 MiB
    private static final long MAX_TOTAL_SIZE = 8L << 20;    // 8 MiB
    private static final int MAX_FILE_COUNT = 64;
    private static final int MAX_RECURSION_DEPTH = 4;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient httpClient;
    private final String apiBase;

    public SkillInstaller() {
        this("https://api.github.com");
    }

    public SkillInstaller(String apiBase) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.apiBase = apiBase;
    }



    /**
     * <p>
     * <ol>
     *   <li>{@code https://www.skills.sh/<owner>/<repo>/<skill-name>}</li>
     *   <li>{@code https://github.com/<owner>/<repo>/tree/<ref>/<subpath>}</li>
     *   <li>{@code https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<subpath>/SKILL.md}</li>
     * </ol>
     */
    public static SkillSource parseSkillURL(String raw) {
        raw = raw.strip();
        URI uri = URI.create(raw);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            throw new IllegalArgumentException("only http(s) URLs are supported");
        }

        String host = uri.getHost();

        String path = uri.getPath();
        if (path == null) path = "";
        String trimmed = path.replaceAll("^/+|/+$", "");
        String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("/");

        return switch (host) {
            case "www.skills.sh", "skills.sh" -> {
                // /<owner>/<repo>/<skill-name>
                if (parts.length < 3) {
                    throw new IllegalArgumentException(
                            "skills.sh URL must be /<owner>/<repo>/<skill-name>");
                }
                String subpath = "skills/" + String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
                yield new SkillSource(parts[0], parts[1], "main", subpath,
                        parts[parts.length - 1], raw);
            }
            case "github.com" -> {
                // /<owner>/<repo>/tree/<ref>/<...subpath>
                if (parts.length < 5 || !"tree".equals(parts[2])) {
                    throw new IllegalArgumentException(
                            "github.com URL must be /<owner>/<repo>/tree/<ref>/<subpath>");
                }
                String sub = String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
                yield new SkillSource(parts[0], parts[1], parts[3], sub,
                        parts[parts.length - 1], raw);
            }
            case "raw.githubusercontent.com" -> {
                // /<owner>/<repo>/<ref>/<...subpath>/SKILL.md
                if (parts.length < 4) {
                    throw new IllegalArgumentException("raw.githubusercontent.com URL too short");
                }
                String[] subParts = java.util.Arrays.copyOfRange(parts, 3, parts.length);

                if (subParts.length > 0 && subParts[subParts.length - 1].contains(".")) {
                    subParts = java.util.Arrays.copyOf(subParts, subParts.length - 1);
                }
                if (subParts.length == 0) {
                    throw new IllegalArgumentException("raw URL missing skill subpath");
                }
                yield new SkillSource(parts[0], parts[1], parts[2],
                        String.join("/", subParts),
                        subParts[subParts.length - 1], raw);
            }
            default -> throw new IllegalArgumentException(
                    "unsupported host \"%s\" (try skills.sh or github.com)".formatted(host));
        };
    }



    /**
     * <p>
     */
    public InstallReport install(SkillSource src, String installRoot) throws IOException {
        if (src == null) {
            throw new IllegalArgumentException("nil source");
        }
        validateSkillName(src.name());

        Path root = Path.of(installRoot);
        Files.createDirectories(root);


        Path staging = Files.createTempDirectory(root, ".install-" + src.name() + "-");
        try {

            int[] fileCount = {0};
            long[] totalBytes = {0L};

            walkAndDownload(src, src.subpath(), staging, fileCount, totalBytes, 0);

            if (!hasSkillManifest(staging)) {
                throw new IOException("downloaded tree missing SKILL.md or skill.yaml — not a skill?");
            }


            Path finalDir = root.resolve(src.name());
            if (Files.exists(finalDir)) {
                deleteRecursively(finalDir);
            }
            Files.move(staging, finalDir);

            return new InstallReport(src.name(), finalDir.toString(), fileCount[0], totalBytes[0]);

        } catch (Exception e) {

            deleteRecursively(staging);
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    /**
     */
    public static String userSkillsRoot() throws IOException {
        Path root = Path.of(System.getProperty("user.home"), ".devmesh", "skills");
        Files.createDirectories(root);
        return root.toString();
    }



    /**
     */
    private record ContentEntry(
            String name,
            String path,
            String type,        // "file" | "dir" | "symlink" | "submodule"
            String download_url,
            String content,
            String encoding,
            int size
    ) {}

    /**
     */
    private List<ContentEntry> listContents(SkillSource src, String subpath) throws IOException {
        String endpoint = "%s/repos/%s/%s/contents/%s?ref=%s".formatted(
                apiBase, src.owner(), src.repo(), subpath,
                URLEncoder.encode(src.ref(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "devmesh-install-skill")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }

        if (resp.statusCode() == 403) {
            String body = resp.body();
            if (body != null && body.length() > 512) body = body.substring(0, 512);
            throw new IOException("github API forbidden (rate-limited?): " + (body != null ? body.strip() : ""));
        }
        if (resp.statusCode() != 200) {
            throw new IOException("github API returned %d for %s".formatted(resp.statusCode(), endpoint));
        }

        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new IOException("github returned empty body");
        }


        String trimmedBody = body.strip();
        if (trimmedBody.startsWith("[")) {
            return MAPPER.readValue(body, new TypeReference<>() {});
        }
        ContentEntry single = MAPPER.readValue(body, ContentEntry.class);
        return List.of(single);
    }

    /**
     */
    private byte[] fetchBlob(ContentEntry entry) throws IOException {
        if (entry.size() > MAX_FILE_SIZE) {
            throw new IOException("file %s too large: %d bytes (max %d)".formatted(
                    entry.path(), entry.size(), MAX_FILE_SIZE));
        }


        if ("base64".equals(entry.encoding()) && entry.content() != null && !entry.content().isEmpty()) {
            String clean = entry.content().replace("\n", "");
            return Base64.getDecoder().decode(clean);
        }


        if (entry.download_url() == null || entry.download_url().isEmpty()) {
            throw new IOException("no download_url for " + entry.path());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(entry.download_url()))
                .header("User-Agent", "devmesh-install-skill")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }

        if (resp.statusCode() != 200) {
            throw new IOException("download %s: status %d".formatted(entry.download_url(), resp.statusCode()));
        }
        return resp.body();
    }



    /**
     */
    private void walkAndDownload(
            SkillSource src, String subpath, Path localDir,
            int[] fileCount, long[] totalBytes, int depth) throws IOException {

        if (depth > MAX_RECURSION_DEPTH) {
            throw new IOException("install tree too deep (>%d levels)".formatted(MAX_RECURSION_DEPTH));
        }

        List<ContentEntry> entries = listContents(src, subpath);
        for (ContentEntry entry : entries) {
            if (fileCount[0] >= MAX_FILE_COUNT) {
                throw new IOException("install file count limit (%d) reached".formatted(MAX_FILE_COUNT));
            }


            if (entry.name().contains("..") || entry.name().contains("/") || entry.name().contains("\\")) {
                throw new IOException("suspicious entry name: \"%s\"".formatted(entry.name()));
            }

            Path target = localDir.resolve(entry.name());

            switch (entry.type()) {
                case "file" -> {
                    byte[] data = fetchBlob(entry);
                    if (totalBytes[0] + data.length > MAX_TOTAL_SIZE) {
                        throw new IOException("install total size limit (%d bytes) reached".formatted(MAX_TOTAL_SIZE));
                    }
                    Files.write(target, data);
                    fileCount[0]++;
                    totalBytes[0] += data.length;
                }
                case "dir" -> {
                    Files.createDirectories(target);
                    walkAndDownload(src, entry.path(), target, fileCount, totalBytes, depth + 1);
                }
                default -> {

                }
            }
        }
    }



    /**
     */
    private static boolean hasSkillManifest(Path dir) {
        return Files.isRegularFile(dir.resolve("SKILL.md"))
                || Files.isRegularFile(dir.resolve("skill.yaml"));
    }

    /**
     */
    static void validateSkillName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("empty skill name");
        }
        if (name.startsWith(".")) {
            throw new IllegalArgumentException("skill name cannot start with '.'");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                continue;
            }
            throw new IllegalArgumentException(
                    "skill name \"%s\" contains invalid char '%c' (use a-z 0-9 - _)".formatted(name, c));
        }
    }

    /**
     */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
