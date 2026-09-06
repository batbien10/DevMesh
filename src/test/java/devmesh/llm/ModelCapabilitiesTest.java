package devmesh.llm;

import devmesh.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelCapabilitiesTest {
    @Test
    void compatibleProviderIsUnknownUntilMetadataDeclaresSupport() {
        var config = provider("openrouter", "vendor/model");
        var capabilities = ModelCapabilityResolver.resolve(config);
        assertEquals(CapabilitySupport.UNKNOWN, capabilities.reasoning());
        assertEquals(CapabilitySupport.UNKNOWN, capabilities.temperature());
    }

    @Test
    void configuredEffortsAreModelSpecific() {
        var config = provider("openai-compat", "vendor/model");
        config.setSupportsReasoning(true);
        config.setSupportsReasoningEffort(true);
        config.setReasoningEfforts(List.of("low", "high"));
        var capabilities = ModelCapabilityResolver.resolve(config);
        assertEquals(List.of(ReasoningEffort.LOW, ReasoningEffort.HIGH), capabilities.supportedReasoningEfforts());
    }

    @Test
    void switchingCapabilitiesResetsInvalidRuntimeSettings() {
        var settings = new ModelRuntimeSettings();
        settings.setThinkingEnabled(true);
        settings.setReasoningEffort(ReasoningEffort.HIGH);
        settings.setTemperature(0.7);
        settings.resetUnsupported(new ModelCapabilities(
                CapabilitySupport.UNSUPPORTED, CapabilitySupport.UNSUPPORTED,
                CapabilitySupport.UNSUPPORTED, CapabilitySupport.UNKNOWN,
                CapabilitySupport.UNKNOWN, List.of()));
        assertNull(settings.thinkingEnabled());
        assertNull(settings.reasoningEffort());
        assertNull(settings.temperature());
    }

    private static ProviderConfig provider(String protocol, String model) {
        var config = new ProviderConfig();
        config.setName("test");
        config.setProtocol(protocol);
        config.setModel(model);
        config.setApiKey("test-key");
        config.setBaseUrl("https://example.invalid/v1");
        return config;
    }
}