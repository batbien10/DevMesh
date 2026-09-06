package devmesh.config;


public class SandboxYamlConfig {
    private boolean enabled;
    private boolean autoAllow = true;
    private boolean networkEnabled;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAutoAllow() { return autoAllow; }
    public void setAutoAllow(boolean autoAllow) { this.autoAllow = autoAllow; }

    public boolean isNetworkEnabled() { return networkEnabled; }
    public void setNetworkEnabled(boolean networkEnabled) { this.networkEnabled = networkEnabled; }
}
