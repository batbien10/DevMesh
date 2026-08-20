package devmesh.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Event-sourced store for quarantined Skill candidates. Candidate content is
 * immutable; lifecycle changes are append-only events protected by a file lock.
 */
public final class SkillEvolutionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper LINE_MAPPER = new ObjectMapper();
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path workspace;
    private final Path root;
    private final Path candidatesRoot;
    private final Path releasesRoot;
    private final Path skillsRoot;
    private final Path eventsFile;
    private final Path lockFile;
    private final ReentrantLock jvmLock;

    public record Proposal(
            String name,
            String description,
            String whenToUse,
            List<String> tags,
            String instructions,
            List<String> failureModes,
            List<String> validationChecks,
            String sourceTrace
    ) {}

    public record CandidateView(SkillCandidate candidate, SkillCandidate.Status status,
                                int evidenceCount) {}

    public SkillEvolutionStore(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.root = this.workspace.resolve(".devmesh").resolve("evolution");
        this.candidatesRoot = root.resolve("candidates");
        this.releasesRoot = root.resolve("releases");
        this.skillsRoot = this.workspace.resolve(".devmesh").resolve("skills");
        this.eventsFile = root.resolve("events.jsonl");
        this.lockFile = root.resolve("evolution.lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
    }

    public Path root() {
        return root;
    }

    public SkillCandidate propose(Proposal proposal) throws IOException {
        validateProposal(proposal);
        return withLock(() -> {
            Files.createDirectories(candidatesRoot);
            int version = listCandidatesInternal().stream()
                    .filter(c -> c.name().equals(proposal.name()))
                    .mapToInt(SkillCandidate::version).max().orElse(0) + 1;
            String createdAt = Instant.now().toString();
            String hash = candidateHash(proposal.name(), version, proposal.description(),
                    proposal.whenToUse(), proposal.tags(), proposal.instructions(),
                    proposal.failureModes(), proposal.validationChecks(), proposal.sourceTrace());
            String id = proposal.name() + "-v" + version + "-" + hash.substring(0, 8);
            var candidate = new SkillCandidate(id, proposal.name(), version,
                    proposal.description(), proposal.whenToUse(), proposal.tags(),
                    proposal.instructions(), proposal.failureModes(), proposal.validationChecks(),
                    proposal.sourceTrace(), createdAt, hash);

            Path candidateDir = candidatesRoot.resolve(id);
            if (Files.exists(candidateDir)) throw new IOException("Candidate already exists: " + id);
            try {
                Files.createDirectories(candidateDir);
                writeAtomic(candidateDir.resolve("candidate.json"), MAPPER.writeValueAsString(candidate));
                writeAtomic(candidateDir.resolve("SKILL.md"), candidate.renderSkillMarkdown());
                appendEventLocked(newEvent(id, SkillEvolutionEvent.Type.PROPOSED, Map.of(
                        "name", candidate.name(),
                        "version", candidate.version(),
                        "content_hash", candidate.contentHash())));
            } catch (Exception e) {
                try {
                    deleteTree(candidateDir);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
                throw e;
            }
            return candidate;
        });
    }

    public SkillCandidate loadCandidate(String candidateId) throws IOException {
        Path file = candidatePath(candidateId).resolve("candidate.json");
        if (!Files.isRegularFile(file)) throw new IOException("Unknown candidate: " + candidateId);
        SkillCandidate candidate = MAPPER.readValue(file.toFile(), SkillCandidate.class);
        String expected = candidateHash(candidate.name(), candidate.version(), candidate.description(),
                candidate.whenToUse(), candidate.tags(), candidate.instructions(),
                candidate.failureModes(), candidate.validationChecks(), candidate.sourceTrace());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                candidate.contentHash().getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Candidate content hash mismatch: " + candidateId);
        }
        return candidate;
    }

    public List<CandidateView> listViews() throws IOException {
        List<SkillEvolutionEvent> events = readEvents();
        var views = new ArrayList<CandidateView>();
        for (SkillCandidate candidate : listCandidatesInternal()) {
            views.add(new CandidateView(candidate, statusOf(candidate.id(), events),
                    (int) events.stream().filter(e -> e.candidateId().equals(candidate.id()))
                            .filter(e -> e.type() == SkillEvolutionEvent.Type.EVALUATION_PASSED
                                    || e.type() == SkillEvolutionEvent.Type.EVALUATION_FAILED)
                            .count()));
        }
        views.sort(Comparator.comparing((CandidateView v) -> v.candidate().name())
                .thenComparingInt(v -> v.candidate().version()));
        return List.copyOf(views);
    }

    public SkillCandidate.Status statusOf(String candidateId) throws IOException {
        loadCandidate(candidateId);
        return statusOf(candidateId, readEvents());
    }

    public void recordEvaluation(String candidateId, boolean passed,
                                 Map<String, Object> evidence) throws IOException {
        withLock(() -> {
            loadCandidate(candidateId);
            appendEventLocked(newEvent(candidateId,
                    passed ? SkillEvolutionEvent.Type.EVALUATION_PASSED
                            : SkillEvolutionEvent.Type.EVALUATION_FAILED,
                    evidence));
            return null;
        });
    }

    public Path promote(String candidateId) throws IOException {
        return withLock(() -> {
            SkillCandidate candidate = loadCandidate(candidateId);
            SkillCandidate.Status status = statusOf(candidateId, readEvents());
            if (status != SkillCandidate.Status.VERIFIED) {
                throw new IOException("Candidate must be VERIFIED before promotion; current status=" + status);
            }
            Files.createDirectories(skillsRoot);
            Files.createDirectories(releasesRoot.resolve(candidate.name()));
            Path target = skillsRoot.resolve(candidate.name()).normalize();
            ensureInside(target, skillsRoot);
            String stamp = Instant.now().toString().replace(':', '-');
            Path backup = releasesRoot.resolve(candidate.name())
                    .resolve(stamp + "-pre-v" + candidate.version());
            boolean hadPrevious = Files.exists(target);
            if (hadPrevious) moveDirectory(target, backup);

            Path staging = skillsRoot.resolve(".evolving-" + candidate.id() + "-" + UUID.randomUUID());
            try {
                Files.createDirectories(staging);
                Files.writeString(staging.resolve("SKILL.md"), candidate.renderSkillMarkdown(),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                moveDirectory(staging, target);
            } catch (Exception e) {
                if (Files.exists(staging)) deleteTree(staging);
                if (hadPrevious && Files.exists(backup) && !Files.exists(target)) {
                    moveDirectory(backup, target);
                }
                throw e;
            }

            var payload = new LinkedHashMap<String, Object>();
            payload.put("skill_path", target.toString());
            payload.put("content_hash", candidate.contentHash());
            payload.put("had_previous", hadPrevious);
            payload.put("backup_path", hadPrevious ? backup.toString() : "");
            try {
                appendEventLocked(newEvent(candidateId, SkillEvolutionEvent.Type.PROMOTED, payload));
            } catch (IOException eventError) {
                try {
                    if (Files.exists(target)) deleteTree(target);
                    if (hadPrevious && Files.exists(backup)) moveDirectory(backup, target);
                } catch (IOException compensationError) {
                    eventError.addSuppressed(compensationError);
                }
                throw new IOException("Promotion event could not be committed; filesystem change was compensated",
                        eventError);
            }
            return target;
        });
    }

    public Path rollback(String candidateId) throws IOException {
        return withLock(() -> {
            SkillCandidate candidate = loadCandidate(candidateId);
            List<SkillEvolutionEvent> events = readEvents();
            if (statusOf(candidateId, events) != SkillCandidate.Status.PROMOTED) {
                throw new IOException("Only a PROMOTED candidate can be rolled back");
            }
            SkillEvolutionEvent promotion = events.stream()
                    .filter(e -> e.candidateId().equals(candidateId))
                    .filter(e -> e.type() == SkillEvolutionEvent.Type.PROMOTED)
                    .reduce((first, second) -> second).orElseThrow();
            Path target = skillsRoot.resolve(candidate.name()).normalize();
            ensureInside(target, skillsRoot);
            String backupValue = String.valueOf(promotion.payload().getOrDefault("backup_path", ""));
            Path backup = null;
            if (!backupValue.isBlank()) {
                backup = Path.of(backupValue).toAbsolutePath().normalize();
                ensureInside(backup, releasesRoot);
                if (!Files.exists(backup)) throw new IOException("Rollback backup is missing: " + backup);
            }
            String stamp = Instant.now().toString().replace(':', '-');
            Path retired = releasesRoot.resolve(candidate.name())
                    .resolve(stamp + "-rolled-back-v" + candidate.version());
            if (Files.exists(target)) moveDirectory(target, retired);

            Path restored = target;
            if (backup != null) {
                try {
                    moveDirectory(backup, target);
                } catch (IOException e) {
                    if (Files.exists(retired) && !Files.exists(target)) moveDirectory(retired, target);
                    throw e;
                }
            }
            try {
                appendEventLocked(newEvent(candidateId, SkillEvolutionEvent.Type.ROLLED_BACK, Map.of(
                        "retired_path", retired.toString(),
                        "restored_path", backupValue.isBlank() ? "" : restored.toString())));
            } catch (IOException eventError) {
                try {
                    if (backup != null && Files.exists(target)) moveDirectory(target, backup);
                    if (Files.exists(retired)) moveDirectory(retired, target);
                } catch (IOException compensationError) {
                    eventError.addSuppressed(compensationError);
                }
                throw new IOException("Rollback event could not be committed; filesystem change was compensated",
                        eventError);
            }
            return restored;
        });
    }

    public List<SkillEvolutionEvent> readEvents() throws IOException {
        if (!Files.exists(eventsFile)) return List.of();
        var events = new ArrayList<SkillEvolutionEvent>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(eventsFile)) {
            lineNumber++;
            if (line.isBlank()) continue;
            try {
                events.add(LINE_MAPPER.readValue(line, SkillEvolutionEvent.class));
            } catch (Exception e) {
                throw new IOException("Malformed evolution event at line " + lineNumber, e);
            }
        }
        return List.copyOf(events);
    }

    private List<SkillCandidate> listCandidatesInternal() throws IOException {
        if (!Files.isDirectory(candidatesRoot)) return List.of();
        var candidates = new ArrayList<SkillCandidate>();
        try (Stream<Path> dirs = Files.list(candidatesRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path json = dir.resolve("candidate.json");
                if (Files.isRegularFile(json)) candidates.add(loadCandidate(dir.getFileName().toString()));
            }
        }
        return candidates;
    }

    private SkillCandidate.Status statusOf(String candidateId, List<SkillEvolutionEvent> events) {
        SkillCandidate.Status status = SkillCandidate.Status.QUARANTINED;
        for (SkillEvolutionEvent event : events) {
            if (!event.candidateId().equals(candidateId)) continue;
            status = switch (event.type()) {
                case PROPOSED -> SkillCandidate.Status.QUARANTINED;
                case EVALUATION_PASSED -> SkillCandidate.Status.VERIFIED;
                case EVALUATION_FAILED -> SkillCandidate.Status.REJECTED;
                case PROMOTED -> SkillCandidate.Status.PROMOTED;
                case ROLLED_BACK -> SkillCandidate.Status.ROLLED_BACK;
            };
        }
        return status;
    }

    private SkillEvolutionEvent newEvent(String candidateId, SkillEvolutionEvent.Type type,
                                         Map<String, Object> payload) {
        return new SkillEvolutionEvent(UUID.randomUUID().toString(), candidateId, type,
                Instant.now().toString(), payload);
    }

    private void appendEventLocked(SkillEvolutionEvent event) throws IOException {
        Files.createDirectories(root);
        String line = LINE_MAPPER.writeValueAsString(event) + System.lineSeparator();
        try (FileChannel channel = FileChannel.open(eventsFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
    }

    private <T> T withLock(Callable<T> action) throws IOException {
        jvmLock.lock();
        try {
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                try {
                    return action.call();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e.getMessage(), e);
                }
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private Path candidatePath(String candidateId) throws IOException {
        if (candidateId == null || !candidateId.matches("[a-z0-9-]{4,100}")) {
            throw new IOException("Invalid candidate id");
        }
        Path path = candidatesRoot.resolve(candidateId).normalize();
        ensureInside(path, candidatesRoot);
        return path;
    }

    private static void validateProposal(Proposal proposal) {
        if (proposal == null) throw new IllegalArgumentException("proposal is required");
        if (proposal.name() == null || !VALID_NAME.matcher(proposal.name()).matches()) {
            throw new IllegalArgumentException("name must match " + VALID_NAME.pattern());
        }
        if (blank(proposal.description())) throw new IllegalArgumentException("description is required");
        if (blank(proposal.whenToUse())) throw new IllegalArgumentException("when_to_use is required");
        if (blank(proposal.instructions())) throw new IllegalArgumentException("instructions are required");
        if (proposal.instructions().length() > 50_000) {
            throw new IllegalArgumentException("instructions exceed 50000 characters");
        }
    }

    private static String candidateHash(String name, int version, String description,
                                        String whenToUse, List<String> tags, String instructions,
                                        List<String> failureModes, List<String> validationChecks,
                                        String sourceTrace) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(name, Integer.toString(version), nullSafe(description),
                    nullSafe(whenToUse), String.join("\n", safeList(tags)), nullSafe(instructions),
                    String.join("\n", safeList(failureModes)),
                    String.join("\n", safeList(validationChecks)), nullSafe(sourceTrace))) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target);
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void ensureInside(Path path, Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!path.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
            throw new IOException("Path escapes evolution workspace: " + path);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static List<String> safeList(List<String> value) {
        return value == null ? List.of() : value;
    }
}
