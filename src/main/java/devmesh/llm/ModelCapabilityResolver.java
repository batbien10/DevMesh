package devmesh.llm;

import devmesh.config.ProviderConfig;

import java.util.Locale;

/** Resolves capabilities at the provider boundary, never in the TUI. */
public final class ModelCapabilityResolver {
    private ModelCapabilityResolver() {}

    public static ModelCapabilities resolve(ProviderConfig config) {
        String protocol = config.getProtocol() == null ? "" : config.getProtocol().toLowerCase(Locale.ROOT);
        if ("anthropic".equals(protocol)) {
            boolean thinking = ModelResolver.supportsThinking(config.getModel());
            return new ModelCapabilities(
                    thinking ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED,
                    CapabilitySupport.UNSUPPORTED,
                    CapabilitySupport.UNSUPPORTED,
                    CapabilitySupport.UNSUPPORTED,
                    CapabilitySupport.UNKNOWN,
                    java.util.List.of());
        }
        if ("openai".equals(protocol)) {
            boolean reasoning = ModelResolver.supportsReasoning(config.getModel());
            return new ModelCapabilities(
                    reasoning ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED,
                    reasoning ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED,
                    reasoning ? CapabilitySupport.UNSUPPORTED : CapabilitySupport.SUPPORTED,
                    CapabilitySupport.SUPPORTED,
                    CapabilitySupport.UNKNOWN,
                    reasoning ? java.util.List.of(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
                            : java.util.List.of());
        }
        return config.configuredCapabilities();
    }
}