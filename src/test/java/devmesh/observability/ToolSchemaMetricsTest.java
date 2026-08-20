package devmesh.observability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaMetricsTest {

    @Test
    void canonicalJsonSortsSchemasAndMapKeys() {
        var schemas = List.<Map<String, Object>>of(
                Map.of("name", "Zulu", "description", "last", "input_schema", Map.of("type", "object")),
                Map.of("name", "Alpha", "description", "first", "input_schema", Map.of("type", "object"))
        );

        String json = ToolSchemaMetrics.canonicalJson(schemas);

        assertTrue(json.indexOf("Alpha") < json.indexOf("Zulu"));
        assertEquals(json, ToolSchemaMetrics.canonicalJson(schemas.reversed()));
    }

    @Test
    void estimateUsesCanonicalJsonCharactersDividedByFourRoundedUp() {
        var schemas = List.<Map<String, Object>>of(
                Map.of("name", "Read", "description", "Read a file", "input_schema", Map.of("type", "object"))
        );

        var measurement = ToolSchemaMetrics.measure(schemas);

        assertEquals(1, measurement.schemaCount());
        assertEquals((measurement.jsonCharacters() + 3) / 4, measurement.estimatedTokens());
    }

    @Test
    void emptyListHasNoEstimatedTokens() {
        var measurement = ToolSchemaMetrics.measure(List.of());
        assertEquals(0, measurement.schemaCount());
        assertEquals(2, measurement.jsonCharacters());
        assertEquals(0, measurement.estimatedTokens());
    }
}
