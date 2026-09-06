package devmesh.platform;

@FunctionalInterface
public interface ProcessListener {
    void onEvent(ProcessEvent event);
}