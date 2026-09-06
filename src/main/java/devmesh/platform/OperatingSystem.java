package devmesh.platform;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS, LINUX, MACOS, OTHER;

    public static OperatingSystem detect() {
        String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (value.contains("win")) return WINDOWS;
        if (value.contains("mac") || value.contains("darwin")) return MACOS;
        if (value.contains("nix") || value.contains("nux")) return LINUX;
        return OTHER;
    }
}