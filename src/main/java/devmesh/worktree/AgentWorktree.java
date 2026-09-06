package devmesh.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Lightweight worktree API for sub-agents. Does NOT touch global session
 * state (WorktreeSessionStore).
 */
public final class AgentWorktree {

    private static final Logger log = Logger.getLogger(AgentWorktree.class.getName());

    public record Result(String worktreePath, String worktreeBranch, String headCommit, String gitRoot) {}

    private AgentWorktree() {}

    /**
     * Creates or resumes a worktree for a sub-agent.
     */
    public static Result create(String slug, String repoRoot, List<String> symlinkDirs) throws Exception {
        SlugValidator.validate(slug);

        Path wtPath = Path.of(repoRoot, ".devmesh", "worktrees", SlugValidator.flatten(slug));
        String branch = "worktree-" + SlugValidator.flatten(slug);

        // Fast-resume: check if worktree already exists
        if (Files.isDirectory(wtPath)) {
            // Bump mtime to prevent stale cleanup
            Files.setLastModifiedTime(wtPath, java.nio.file.attribute.FileTime.from(Instant.now()));
            String head = readHead(wtPath.toString());
            return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
        }

        Files.createDirectories(wtPath.getParent());

        ProcessBuilder pb = new ProcessBuilder("git", "worktree", "add", "-B", branch, wtPath.toString(), "HEAD");
        pb.directory(Path.of(repoRoot).toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new IOException("Failed to create agent worktree: " + output);
        }

        PostCreationSetup.perform(repoRoot, wtPath.toString(), symlinkDirs);

        String head = readHead(wtPath.toString());
        return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
    }

    /**
     * Removes a worktree created by {@link #create}.
     */
    public static boolean remove(String worktreePath, String worktreeBranch, String gitRoot) {
        if (gitRoot == null || gitRoot.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove", "--force", worktreePath);
            pb.directory(Path.of(gitRoot).toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            proc.waitFor(30, TimeUnit.SECONDS);
            if (proc.exitValue() != 0) return false;

            if (worktreeBranch != null && !worktreeBranch.isBlank()) {
                Thread.sleep(100); // wait for git lockfile release
                ProcessBuilder delBranch = new ProcessBuilder("git", "branch", "-D", worktreeBranch);
                delBranch.directory(Path.of(gitRoot).toFile());
                delBranch.redirectErrorStream(true);
                Process branchProc = delBranch.start();
                branchProc.getInputStream().readAllBytes();
                branchProc.waitFor(30, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.fine("Failed to remove agent worktree: " + e.getMessage());
            return false;
        }
    }

    /**
     * Builds the notice text for sub-agents running in isolated worktrees.
     */
    public static String buildNotice(String parentCwd, String worktreeCwd) {
        return "You've inherited the conversation context above from a parent agent working in %s. "
                .formatted(parentCwd)
                + "You are operating in an isolated git worktree at %s — same repository, same relative "
                .formatted(worktreeCwd)
                + "file structure, separate working copy. Paths in the inherited context refer to the "
                + "parent's working directory; translate them to your worktree root. Re-read files before "
                + "editing if the parent may have modified them since they appear in the context. Your "
                + "changes stay in this worktree and will not affect the parent's files.";
    }


    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{40}([0-9a-f]{24})?$");

    private static final Pattern SAFE_REF = Pattern.compile("^[a-zA-Z0-9/._+@-]+$");

    /**
     * <p>
     */
    private static String readHead(String worktreePath) {
        try {
            Path dotGit = Path.of(worktreePath, ".git");
            if (!Files.exists(dotGit)) return null;

            String gitDir;
            if (Files.isDirectory(dotGit)) {

                gitDir = dotGit.toString();
            } else {

                String pointer = Files.readString(dotGit).strip();
                if (!pointer.startsWith("gitdir:")) return null;
                String rel = pointer.substring("gitdir:".length()).strip();
                Path resolved = Path.of(rel).isAbsolute()
                        ? Path.of(rel)
                        : Path.of(worktreePath, rel).normalize();
                gitDir = resolved.toString();
            }


            Path headFile = Path.of(gitDir, "HEAD");
            if (!Files.exists(headFile)) return null;
            String content = Files.readString(headFile).strip();

            if (content.startsWith("ref:")) {

                String ref = content.substring("ref:".length()).strip();
                if (!SAFE_REF.matcher(ref).matches() || ref.contains("..")) return null;
                return resolveRef(gitDir, ref);
            }

            return SHA_PATTERN.matcher(content).matches() ? content : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     */
    private static String resolveRef(String gitDir, String ref) {
        try {

            String sha = resolveRefInDir(gitDir, ref);
            if (sha != null) return sha;


            Path commonFile = Path.of(gitDir, "commondir");
            if (Files.exists(commonFile)) {
                String commonRel = Files.readString(commonFile).strip();
                String commonDir = Path.of(commonRel).isAbsolute()
                        ? commonRel
                        : Path.of(gitDir, commonRel).normalize().toString();
                if (!commonDir.equals(gitDir)) {
                    return resolveRefInDir(commonDir, ref);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     */
    private static String resolveRefInDir(String dir, String ref) throws IOException {

        Path loosePath = Path.of(dir, ref);
        if (Files.exists(loosePath)) {
            String content = Files.readString(loosePath).strip();
            if (content.startsWith("ref:")) {

                String target = content.substring("ref:".length()).strip();
                if (!SAFE_REF.matcher(target).matches() || target.contains("..")) return null;
                return resolveRef(dir, target);
            }
            return SHA_PATTERN.matcher(content).matches() ? content : null;
        }


        Path packed = Path.of(dir, "packed-refs");
        if (!Files.exists(packed)) return null;
        for (String line : Files.readAllLines(packed)) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) continue;
            int sp = line.indexOf(' ');
            if (sp == -1) continue;
            if (line.substring(sp + 1).equals(ref)) {
                String sha = line.substring(0, sp);
                return SHA_PATTERN.matcher(sha).matches() ? sha : null;
            }
        }
        return null;
    }
}
