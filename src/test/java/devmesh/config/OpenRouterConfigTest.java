package devmesh.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenRouterConfigTest {

    @Test
    void acceptsOpenRouterProviderWithoutBaseUrl(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        Files.writeString(configPath, """
                providers:
                  - name: openrouter
                    protocol: openrouter
                    model: openai/gpt-4o-mini
                    api_key: test-key
                    headers:
                      HTTP-Referer: https://devmesh.example
                      X-Title: DevMesh
                """);

        AppConfig config = ConfigLoader.load(configPath.toString());
        ProviderConfig provider = config.getProviders().getFirst();

        assertEquals("openrouter", provider.getProtocol());
        assertNull(provider.getBaseUrl());
        assertEquals("https://devmesh.example", provider.getHeaders().get("HTTP-Referer"));
        assertEquals("DevMesh", provider.getHeaders().get("X-Title"));
    }
}
