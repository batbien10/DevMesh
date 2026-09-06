package devmesh.tool;

import devmesh.agent.AgentEvent;

import java.util.function.Consumer;

/** Optional bridge for tools that can emit incremental execution events. */
public interface CommandEventEmitter {
    void setEventSink(Consumer<AgentEvent> sink);
}