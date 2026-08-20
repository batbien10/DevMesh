package devmesh.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Deterministic size accounting for the Tool Schema list sent to a provider.
 *
 * <p>The estimate intentionally mirrors the lightweight accounting used by the
 * Agent trace instead of claiming model-tokenizer precision: schemas are sorted
 * by name, serialized as canonical compact JSON, and divided by four characters
 * per estimated token (rounded up). The canonical JSON character count is kept
 * alongside the estimate so benchmark results remain independently auditable.
 */
public final class ToolSchemaMetrics {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    private ToolSchemaMetrics() {}

    public record Measurement(int schemaCount, int jsonCharacters, int estimatedTokens) {}

    public static Measurement measure(List<Map<String, Object>> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            return new Measurement(0, 2, 0); // canonical empty list: []
        }
        String json = canonicalJson(schemas);
        int estimatedTokens = Math.max(1, (json.length() + 3) / 4);
        return new Measurement(schemas.size(), json.length(), estimatedTokens);
    }

    public static int estimateTokens(List<Map<String, Object>> schemas) {
        return measure(schemas).estimatedTokens();
    }

    public static String canonicalJson(List<Map<String, Object>> schemas) {
        var ordered = new ArrayList<Map<String, Object>>(
                schemas == null ? List.of() : schemas);
        ordered.sort(Comparator.comparing(schema ->
                String.valueOf(schema.getOrDefault("name", ""))));
        try {
            return MAPPER.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize tool schemas", e);
        }
    }
}
