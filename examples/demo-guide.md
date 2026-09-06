# DevMesh Demo Guide

This workflow demonstrates DevMesh's engineering completeness, Agent interaction, observability, and Skill Evolution in about 10 minutes.

## 1. Build and test

Windows PowerShell：

```powershell
.\gradlew.bat test shadowJar
```

Expected results:

- Gradle Wrapper verification passes;
- 120 automated tests pass;
- `build/libs/devmesh.jar` is generated.

## 2. Remote Web interaction

Copy the configuration template and fill in the Provider information locally. Real configuration is excluded by `.gitignore`; never commit an API key.

```powershell
Copy-Item .\.devmesh\config.yaml.example .\.devmesh\config.yaml
java -jar .\build\libs\devmesh.jar .\.devmesh\config.yaml --remote=127.0.0.1:18888
```

Open <http://127.0.0.1:18888> in a browser and use the following no-tool prompt to verify streaming output:

```text
Briefly introduce DevMesh in three points: multi-model access, MCP tools, context management, and run-trace evaluation. Do not call tools.
```

For a code-analysis demo, select only public files that may be sent to the model and state clearly in the prompt: "read-only, do not modify files, do not read configuration files."

## 3. Trace reports and quality gates

After completing a task, run:

```powershell
java -jar .\build\libs\devmesh.jar --trace-report .\.devmesh\traces

java -jar .\build\libs\devmesh.jar `
  --trace-eval .\.devmesh\traces `
  --trace-policy .\evals\agent-reliability.yaml
```

Emphasize that traces record operation names, durations, tokens, status, and parameter names, and do not record prompts, API keys, file contents, or tool return values by default.

## 4. Verified Skill Evolution

First run the core lifecycle tests, which do not require a model API:

```powershell
.\gradlew.bat test --tests "devmesh.evolution.*"
```

Then inspect these reproducible inputs:

- `examples/skill-evolution/safe-java-refactor.yaml`
- `examples/skill-evolution/baseline-outcomes.jsonl`
- `examples/skill-evolution/candidate-outcomes.jsonl`
- `evals/skill-evolution.yaml`

During the demo, highlight the state transitions from `quarantine → canary → outcome/trace/non-regression gates → verified → promoted`, as well as rejection and rollback after failures.

## 5. Suggested demo order

1. Use `devmesh.DevMesh` to explain startup and mode selection;
2. Use `Agent` to explain the ReAct loop and streaming Tool Calling;
3. Use `ToolRegistry`, `PermissionChecker`, `HookEngine`, and `Sandbox` to explain the secure execution chain;
4. Use `AgentTracer` and `TraceEvaluator` to explain observability and quality gates;
5. Use `SkillEvolutionService` to explain how candidate Skills are verified, published, and rolled back.
