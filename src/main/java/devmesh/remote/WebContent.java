package devmesh.remote;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 */
public final class WebContent {

    private WebContent() {}

    public static final String INDEX_HTML;

    static {
        try (InputStream is = WebContent.class.getResourceAsStream("index.html")) {
            if (is == null) {
                throw new RuntimeException("index.html resource not found");
            }
            INDEX_HTML = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load index.html", e);
        }
    }
}
