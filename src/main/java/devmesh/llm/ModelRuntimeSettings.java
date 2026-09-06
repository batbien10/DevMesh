package devmesh.llm;

/** Mutable session-level settings applied by provider adapters at request time. */
public final class ModelRuntimeSettings {
    private ReasoningEffort reasoningEffort;
    private Boolean thinkingEnabled;
    private Double temperature;
    private Double topP;
    private String verbosity;

    public ReasoningEffort reasoningEffort() { return reasoningEffort; }
    public Boolean thinkingEnabled() { return thinkingEnabled; }
    public Double temperature() { return temperature; }
    public Double topP() { return topP; }
    public String verbosity() { return verbosity; }

    public void setReasoningEffort(ReasoningEffort value) { reasoningEffort = value; }
    public void setThinkingEnabled(Boolean value) { thinkingEnabled = value; }
    public void setTemperature(Double value) { temperature = value; }
    public void setTopP(Double value) { topP = value; }
    public void setVerbosity(String value) { verbosity = value; }

    public void resetUnsupported(ModelCapabilities capabilities) {
        if (capabilities.reasoningEffort() != CapabilitySupport.SUPPORTED) reasoningEffort = null;
        if (capabilities.reasoning() != CapabilitySupport.SUPPORTED) thinkingEnabled = null;
        if (capabilities.temperature() != CapabilitySupport.SUPPORTED) temperature = null;
        if (capabilities.topP() != CapabilitySupport.SUPPORTED) topP = null;
        if (capabilities.verbosity() != CapabilitySupport.SUPPORTED) verbosity = null;
        if (reasoningEffort != null && !capabilities.supportedReasoningEfforts().contains(reasoningEffort)) {
            reasoningEffort = null;
        }
    }
}