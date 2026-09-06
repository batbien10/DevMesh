package devmesh.sandbox;

/**
 */
public interface Sandbox {

    /**
     *
     */
    String wrap(String command, SandboxConfig config);

    /**
     */
    boolean isAvailable();
}
