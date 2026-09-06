package devmesh.sandbox;

/**
 */
public class SandboxFactory {

    private SandboxFactory() {}

    /**
     */
    public static Sandbox create() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            return new SeatbeltSandbox();
        }
        if (os.contains("linux")) {
            return new BwrapSandbox();
        }
        return null;
    }
}
