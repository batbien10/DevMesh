package devmesh.evolution;

import java.util.List;

/** Immutable procedural-memory candidate. It is never loaded as an active Skill directly. */
public record SkillCandidate(
        String id,
        String name,
        int version,
        String description,
        String whenToUse,
        List<String> tags,
        String instructions,
        List<String> failureModes,
        List<String> validationChecks,
        String sourceTrace,
        String createdAt,
        String contentHash
) {
    public SkillCandidate {
        tags = tags == null ? List.of() : List.copyOf(tags);
        failureModes = failureModes == null ? List.of() : List.copyOf(failureModes);
        validationChecks = validationChecks == null ? List.of() : List.copyOf(validationChecks);
        sourceTrace = sourceTrace == null ? "" : sourceTrace;
    }

    public enum Status {
        QUARANTINED,
        VERIFIED,
        REJECTED,
        PROMOTED,
        ROLLED_BACK
    }

    public String renderSkillMarkdown() {
        var out = new StringBuilder();
        out.append("---\n")
                .append("name: ").append(name).append('\n')
                .append("description: ").append(yamlString(description)).append('\n')
                .append("when_to_use: ").append(yamlString(whenToUse)).append('\n');
        if (!tags.isEmpty()) {
            out.append("tags: [");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(yamlString(tags.get(i)));
            }
            out.append("]\n");
        }
        out.append("---\n\n# ").append(name).append("\n\n")
                .append(instructions.strip()).append("\n");
        if (!failureModes.isEmpty()) {
            out.append("\n## Known Failure Modes\n\n");
            for (String item : failureModes) out.append("- ").append(item).append('\n');
        }
        if (!validationChecks.isEmpty()) {
            out.append("\n## Validation Before Completion\n\n");
            for (String item : validationChecks) out.append("- [ ] ").append(item).append('\n');
        }
        out.append("\n<!-- evolved-skill: candidate=").append(id)
                .append(" version=").append(version)
                .append(" hash=").append(contentHash).append(" -->\n");
        return out.toString();
    }

    public String renderCanaryContext() {
        return """
                # Canary Skill Candidate

                Candidate: %s (version %d, hash %s)

                This is a quarantined experiment, not a promoted Skill. Follow it only for this run. All normal
                permission, hook, sandbox, and validation rules still apply. Do not claim the candidate is useful
                merely because it was injected; complete the task and let external trace/outcome evaluation decide.

                %s
                """.formatted(id, version, contentHash, renderSkillMarkdown());
    }

    private static String yamlString(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ") + "\"";
    }
}
