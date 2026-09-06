package devmesh.llm;

import java.util.List;

/** Provider-adapter-owned capabilities for the currently selected model. */
public record ModelCapabilities(
        CapabilitySupport reasoning,
        CapabilitySupport reasoningEffort,
        CapabilitySupport temperature,
        CapabilitySupport topP,
        CapabilitySupport verbosity,
        List<ReasoningEffort> supportedReasoningEfforts
) {
    public ModelCapabilities {
        reasoning = reasoning == null ? CapabilitySupport.UNKNOWN : reasoning;
        reasoningEffort = reasoningEffort == null ? CapabilitySupport.UNKNOWN : reasoningEffort;
        temperature = temperature == null ? CapabilitySupport.UNKNOWN : temperature;
        topP = topP == null ? CapabilitySupport.UNKNOWN : topP;
        verbosity = verbosity == null ? CapabilitySupport.UNKNOWN : verbosity;
        supportedReasoningEfforts = List.copyOf(supportedReasoningEfforts == null
                ? List.of() : supportedReasoningEfforts);
    }

    public static ModelCapabilities unknown() {
        return new ModelCapabilities(null, null, null, null, null, List.of());
    }

    public boolean supportsThinking() {
        return reasoning == CapabilitySupport.SUPPORTED;
    }
}