package devmesh.llm;

import java.util.Locale;

public enum ReasoningEffort {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh");

    private final String wireValue;

    ReasoningEffort(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }

    public static ReasoningEffort parse(String value) {
        if (value == null) return null;
        for (var effort : values()) {
            if (effort.wireValue.equals(value.strip().toLowerCase(Locale.ROOT))) return effort;
        }
        return null;
    }
}