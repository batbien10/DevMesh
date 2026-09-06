package devmesh.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {
    @Test
    void defaultCommandsRegisterAndAliasesResolve() {
        var registry = new CommandRegistry();
        assertTrue(registry.find("help").isPresent());
        assertEquals("help", registry.find("h").orElseThrow().name());
        assertTrue(registry.listVisible().stream().anyMatch(c -> c.name().equals("session")));
    }

    @Test
    void searchPrioritizesPrefixAndHandlesNoResults() {
        var registry = new CommandRegistry();
        assertEquals("model", registry.search("mo").get(0).name());
        assertTrue(registry.search("zzzz").isEmpty());
    }

    @Test
    void unknownCommandIsReported() {
        var registry = new CommandRegistry();
        assertEquals("Unknown command: missing", registry.execute("missing", null));
    }
}