package devmesh.evolution;

import java.util.Map;

/** Append-only lifecycle event. Current status is rebuilt by replaying these events. */
public record SkillEvolutionEvent(
        String eventId,
        String candidateId,
        Type type,
        String timestamp,
        Map<String, Object> payload
) {
    public SkillEvolutionEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public enum Type {
        PROPOSED,
        EVALUATION_PASSED,
        EVALUATION_FAILED,
        PROMOTED,
        ROLLED_BACK
    }
}
