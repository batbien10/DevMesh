package devmesh.llm;

import devmesh.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OpenRouterCompatibilityTest {

    @Test
    void usesOpenRouterDefaultEndpointWhenBaseUrlIsOmitted() {
        ProviderConfig config = config();

        assertEquals("https://openrouter.ai/api/v1", OpenAiCompatClient.resolveBaseUrl(config));
        assertInstanceOf(OpenAiCompatClient.class, LlmClient.create(config, ""));
    }

    @Test
    void trimsConfiguredEndpointSlashes() {
        ProviderConfig config = config();
        config.setBaseUrl("https://openrouter.example/v1///");

        assertEquals("https://openrouter.example/v1", OpenAiCompatClient.resolveBaseUrl(config));
    }

    @Test
    void appliesCustomHeadersWithoutAllowingManagedHeadersToBeOverridden() {
        var requestBuilder = HttpRequest.newBuilder(URI.create("https://example.test"))
            .header("Authorization", "Bearer real-key")
            .header("Content-Type", "application/json");
        OpenAiCompatClient.applyCustomHeaders(requestBuilder, Map.of(
                "HTTP-Referer", "https://devmesh.example",
                "X-Title", "DevMesh",
                "Authorization", "Bearer attacker-value",
                "Content-Type", "text/plain"
        ));
        var request = requestBuilder.build();

        assertEquals("https://devmesh.example", request.headers().firstValue("HTTP-Referer").orElseThrow());
        assertEquals("DevMesh", request.headers().firstValue("X-Title").orElseThrow());
        assertEquals("Bearer real-key", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
    }

    private static ProviderConfig config() {
        var config = new ProviderConfig();
        config.setName("openrouter");
        config.setProtocol("openrouter");
        config.setModel("openai/gpt-4o-mini");
        config.setApiKey("test-key");
        return config;
    }
}
