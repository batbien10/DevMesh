package devmesh;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevMeshTest {

    @Test
    void helpDoesNotRequireConfiguration() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            DevMesh.main(new String[]{"--help"});
        } finally {
            System.setOut(originalOut);
        }

        String help = output.toString(StandardCharsets.UTF_8);
        assertTrue(help.contains("Usage: java -jar devmesh.jar"));
        assertTrue(help.contains("--trace-eval"));
        assertFalse(help.contains("Configuration error:"));
    }

    @Test
    void versionDoesNotRequireConfiguration() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            DevMesh.main(new String[]{"--version"});
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("DevMesh 1.0.2"));
    }
}