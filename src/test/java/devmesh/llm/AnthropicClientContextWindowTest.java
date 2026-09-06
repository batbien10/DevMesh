package devmesh.llm;

import devmesh.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies layer 2 (auto-fetch) degrades gracefully: pointed at an
 * unreachable endpoint, {@code fetchModelContextWindow()} must return 0
 * without throwing, construction must not blow up, and the config's resolved
 * window must fall back to the built-in table.
 *
 * <p>We don't have a live Anthropic endpoint in tests (the smoke-test config
 * uses an OpenAI-compatible proxy that returns nothing useful here), so we
 * degradation path must survive.
 */
class AnthropicClientContextWindowTest {

    private static ProviderConfig anthropicCfg(String baseUrl) {
        var cfg = new ProviderConfig();
        cfg.setProtocol("anthropic");
        cfg.setBaseUrl(baseUrl);
        cfg.setModel("claude-sonnet-4-6");
        cfg.setApiKey("test-api-key"); // non-empty so the ctor proceeds
        return cfg;
    }

    @Test
    void constructionDoesNotThrowWhenFetchFails() {

        var cfg = anthropicCfg("http://127.0.0.1:1");
        assertDoesNotThrow(() -> new AnthropicClient(cfg, "system"));
    }

    @Test
    void fetchReturnsZeroOnUnreachableEndpoint() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        var client = new AnthropicClient(cfg, "system");
        // Best-effort fetch must yield 0 (unavailable), never throw.
        assertEquals(0, client.fetchModelContextWindow());
    }

    @Test
    void resolvedWindowFallsBackToTableWhenFetchFails() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        // Constructing the client triggers the (failing) auto-fetch + backfill.
        new AnthropicClient(cfg, "system");

        assertEquals(200_000, cfg.resolvedContextWindow());
    }

    @Test
    void configOverrideStillWinsEvenWithFailedFetch() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        cfg.setContextWindow(50_000);
        new AnthropicClient(cfg, "system");
        assertEquals(50_000, cfg.resolvedContextWindow());
    }
}
