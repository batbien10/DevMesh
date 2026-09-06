package devmesh.llm;

import devmesh.config.ProviderConfig;
import devmesh.conversation.ConversationManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatRuntimeSettingsTest {
    @Test
    void supportedRuntimeFieldsAreSerialized() throws Exception {
        var config = provider();
        config.setSupportsReasoning(true);
        config.setSupportsReasoningEffort(true);
        config.setSupportsTemperature(true);
        config.setReasoningEfforts(List.of("low", "medium", "high"));
        var client = new OpenAiCompatClient(config, "system");
        var settings = new ModelRuntimeSettings();
        settings.setThinkingEnabled(true);
        settings.setReasoningEffort(ReasoningEffort.MEDIUM);
        settings.setTemperature(0.7);
        client.setRuntimeSettings(settings);

        String body = client.buildRequestBody(new ConversationManager().getMessagesForModel(), List.of());
        assertTrue(body.contains("\"reasoning\":true"));
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""));
        assertTrue(body.contains("\"temperature\":0.7"));
    }

    @Test
    void unsupportedRuntimeFieldsAreOmitted() throws Exception {
        var client = new OpenAiCompatClient(provider(), "system");
        var settings = new ModelRuntimeSettings();
        settings.setThinkingEnabled(true);
        settings.setReasoningEffort(ReasoningEffort.HIGH);
        settings.setTemperature(0.7);
        settings.setTopP(0.9);
        settings.setVerbosity("high");
        client.setRuntimeSettings(settings);

        String body = client.buildRequestBody(new ConversationManager().getMessagesForModel(), List.of());
        assertFalse(body.contains("reasoning_effort"));
        assertFalse(body.contains("temperature"));
        assertFalse(body.contains("top_p"));
        assertFalse(body.contains("verbosity"));
    }

    private static ProviderConfig provider() {
        var config = new ProviderConfig();
        config.setName("test");
        config.setProtocol("openai-compat");
        config.setModel("vendor/model");
        config.setApiKey("test-key");
        config.setBaseUrl("https://example.invalid/v1");
        return config;
    }
}