# From Java Backend to Agent Runtime: DevMesh Learning Guide

This guide is a learning path rather than a feature checklist. It helps you explain DevMesh at three levels: how to use a capability, why it is designed that way, and how the implementation is validated. For the full platform architecture, see [AI coding platform architecture](ai-coding-platform-architecture.md).

## 1. The Agent runtime mental model

A chat application usually performs one call:

```text
User input -> Prompt -> Model -> Text response
```

An Agent adds a loop. The model can request a tool, the runtime executes it, and the result becomes input to the next model turn:

```text
User task
  -> Build context and available tool schemas
  -> Call model
     -> Direct answer -> Done
     -> Tool call
        -> Permission / Hook / Sandbox
        -> Execute tool
        -> Append result to context
        -> Call model again
```

In DevMesh, the main components are:

- [Agent.java](../src/main/java/devmesh/agent/Agent.java): loop entry point.
- [llm/](../src/main/java/devmesh/llm/): model protocol adapters.
- [ToolRegistry.java](../src/main/java/devmesh/tool/ToolRegistry.java): tool registration and schemas.
- [StreamingExecutor.java](../src/main/java/devmesh/agent/StreamingExecutor.java): tool scheduling.
- [ConversationManager.java](../src/main/java/devmesh/conversation/ConversationManager.java): conversation state.

An Agent runtime is similar to a small operating system for models:

| Operating-system concept | Agent-runtime equivalent |
| --- | --- |
| Process scheduling | Agent loop, SubAgent, Team |
| System call | Tool call |
| Driver/protocol | Anthropic, OpenAI, MCP adapters |
| Memory management | Context budget, compaction, memory |
| Permission system | Permission, Hook, Sandbox |
| Monitoring | Trace, metrics, evaluation |

The engineering challenge is to place uncertain model behavior inside a system that is controllable, observable, and recoverable.

## 2. Context Engineering

The context window is the token limit for one inference request. It is not a database and it is not permanent model memory. A request generally contains:

```text
Total input ~= System prompt
            + History
            + Tool schemas
            + Tool results
            + Memory / Skill / MCP instructions
            + Temporary control state
```

Tool schemas are easy to overlook. Every tool contributes a name, description, and JSON Schema even when it is not called. This becomes expensive when many MCP tools are connected.

### Durable transcript and ephemeral context

`ConversationManager` separates two kinds of state:

```text
ConversationManager
|- history                 durable transcript
|  |- user messages
|  |- model responses
|  `- tool calls and results
`- ephemeralContext        named, replaceable request state
   |- available-deferred-tools
   `- plan-mode
```

Use `setEphemeralContext` for current control state and `removeEphemeralContext` when it expires. `getMessages()` is used for persistence and recovery; `getMessagesForModel()` adds the current ephemeral slots to the request envelope. Updating one key repeatedly still produces one slot instead of many permanent history messages.

This design keeps the durable prefix stable for prompt caching while allowing the current plan state and deferred-tool index to change. It does not remove the need for layered compaction:

1. Spill very large tool results to disk and retain a preview.
2. Prefer provider-reported usage as the token anchor.
3. Compact older history near the context threshold while preserving the recent tail.
4. Attach recent file and Skill recovery information after compaction.

## 3. Progressive tool discovery

The model does not execute Java methods directly. DevMesh exposes a schema, receives a structured tool call, validates the arguments, checks permission, and invokes the implementation.

MCP tools can be deferred: they remain registered in the runtime but their complete schemas are loaded only after `ToolSearch` finds them. This is a two-stage retrieval process: search a cheap index first, then inject only the definitions needed for the next turn.

| Concept | Purpose | Example |
| --- | --- | --- |
| Tool | Structured function callable by the model | ReadFile, Bash |
| MCP | Protocol for external tools and data | GitHub MCP, database MCP |
| Skill | Reusable task procedure or knowledge package | Review or release workflow |
| Agent | Execution entity with a model, state, loop, and tools | Main Agent, reviewer Agent |

## 4. Observability

Logs answer what happened at one moment. Metrics show aggregate trends. Traces explain the ordered steps of one request:

```text
invoke_agent devmesh              2.8 s
|- context_budget                 0 ms
|- chat model-A                   1.4 s
|- execute_tool ReadFile          8 ms
|- execute_tool Grep              15 ms
`- chat model-A                   1.2 s
```

DevMesh records model calls, tool execution, usage, retries, compaction, and context pressure without storing prompts, secret values, file contents, or tool output by default. `TraceAnalyzer` and `TraceEvaluator` can then produce offline reports and CI gates.

## 5. Permission and recovery

Every tool passes through the permission and hook boundary before execution. Command tools can additionally run inside the OS-level Sandbox. Sessions persist enough information to resume after a restart, while compaction boundaries prevent the full pre-compaction transcript from being replayed unnecessarily.

## 6. Practical exercises

1. Run `./gradlew test` and inspect the ToolSearch and compaction tests.
2. Run the Remote Web mode and observe streaming messages and tool blocks.
3. Generate a trace with `--trace-report`, then evaluate it with `--trace-eval`.
4. Compare the eager and deferred Tool Schema figures in `benchmarks/engineering-validation.md`.
5. Read `SkillEvolutionService` and follow a candidate from quarantine to verification.

The goal is not to memorize names. Explain one concrete tradeoff: why durable history and ephemeral control state are separate, how tool-result spill protects the context window, and how traces prove that an execution path satisfied its policy.
