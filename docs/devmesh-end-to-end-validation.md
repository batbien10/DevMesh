# DevMesh Flight Recorder, Trace Eval, and Verified Skill Evolution Validation Guide

This guide uses Windows-friendly steps and a small number of required CLI commands. It validates the complete chain from Agent execution to trace analysis, quality gates, and Skill lifecycle governance.

## 1. What this validates

1. Agent Flight Recorder events: `invoke_agent`, `chat`, and `execute_tool`.
2. Default trace redaction of prompts, tool argument values, and tool output.
3. Offline Trace Eval metrics: tokens, cache, tool success, p50/p95 latency, retry, compaction, and context pressure.
4. YAML quality gates that correctly pass or fail.
5. Verified Skill lifecycle: `QUARANTINED -> VERIFIED -> PROMOTED -> ROLLED_BACK`.
6. Where human review happens and what evidence a reviewer should inspect.

Automatic evaluation provides evidence. Human review decides whether a candidate becomes a production Skill.

## 2. Relevant project locations

Flight Recorder:

```text
src/main/java/devmesh/observability/AgentTracer.java
src/main/java/devmesh/agent/Agent.java
.devmesh/traces/
```

Trace Eval:

```text
src/main/java/devmesh/observability/TraceAnalyzer.java
src/main/java/devmesh/observability/TraceEvaluator.java
evals/agent-reliability.yaml
--trace-report
--trace-eval
--trace-policy
```

Verified Skill Evolution:

```text
src/main/java/devmesh/evolution/
.devmesh/evolution/
.devmesh/skills/
evals/skill-evolution.yaml
examples/skill-evolution/safe-java-refactor.yaml
```

## 3. Build and configure

Prepare Java 21 and a local `.devmesh/config.yaml`, then run:

```powershell
.\gradlew.bat clean test shadowJar
```

Do not commit API keys, sessions, memories, traces, or model outputs.

## 4. Generate and inspect a trace

Run a normal Agent task, then produce a text report:

```powershell
java -jar .\build\libs\devmesh.jar --trace-report .\.devmesh\traces
```

The trace should show an Agent span, model spans, and tool spans. It should include operation names, status, latency, token usage, cache usage, retries, and compaction data. It should not include prompts, secret values, command text, file contents, or tool output.

For a JSON report:

```powershell
java -jar .\build\libs\devmesh.jar --trace-report .\.devmesh\traces --output-format json
```

## 5. Run a reliability gate

```powershell
java -jar .\build\libs\devmesh.jar `
  --trace-eval .\.devmesh\traces `
  --trace-policy .\evals\agent-reliability.yaml
```

Exit code `0` means the policy passed; exit code `2` means the gate failed. Inspect the report before changing the policy. A failure is evidence about the run, not a reason to hide the failure by weakening the gate.

## 6. Verify Skill Evolution

Create a quarantined candidate without a model API:

```powershell
$proposal = java -jar .\build\libs\devmesh.jar `
  --skill-evolution-propose .\examples\skill-evolution\safe-java-refactor.yaml `
  --workspace . --output-format json | ConvertFrom-Json
$candidateId = $proposal.id
```

Collect baseline traces in a fresh directory:

```powershell
$runRoot = ".devmesh/evolution-runs/$candidateId-$(Get-Date -Format yyyyMMddHHmmss)"
$env:DEVMESH_TRACE_DIR = "$runRoot/baseline-traces"
Remove-Item Env:DEVMESH_CANARY_SKILL -ErrorAction SilentlyContinue
java -jar .\build\libs\devmesh.jar .\.devmesh\config.yaml `
  -p "Run the first fixed benchmark case"
```

Run exactly the same cases as a canary:

```powershell
$env:DEVMESH_TRACE_DIR = "$runRoot/candidate-traces"
$env:DEVMESH_CANARY_SKILL = $candidateId
java -jar .\build\libs\devmesh.jar .\.devmesh\config.yaml `
  -p "Run the same first benchmark case"
```

The candidate is loaded from quarantine into an ephemeral slot. The `skill_canary` span must contain the candidate ID, version, and content hash.

Prepare paired outcome files using a deterministic harness. The examples are:

```text
examples/skill-evolution/baseline-outcomes.jsonl
examples/skill-evolution/candidate-outcomes.jsonl
```

The evaluator must reject mismatched `case_id` sets, missing provenance, trajectory failures, and unacceptable reliability or cost regression. Only a `VERIFIED` candidate may be explicitly promoted. Keep the release backup and test rollback after promotion.

## 7. Human review checklist

Before promotion, inspect:

- Candidate instructions, trigger conditions, failure modes, and validation checks.
- Whether the candidate contains secrets, prompt-injection instructions, repository-specific assumptions, or permission bypasses.
- Exact baseline and candidate case IDs, model/provider configuration, and trace directories.
- Candidate provenance in `skill_canary` spans.
- Outcome, trajectory, and system gate results.
- The release backup and rollback path.

A passing gate does not replace human judgment. It proves that the candidate met explicit measurable constraints for the evaluated cohort.

## 8. Troubleshooting

- No trace appears: verify `DEVMESH_TRACE` is not `false` and inspect `DEVMESH_TRACE_DIR`.
- The gate cannot find a policy: pass `--trace-policy` with a valid YAML path.
- Candidate evidence is rejected: check candidate ID, version, content hash, and separate baseline/candidate directories.
- A task is not reproducible: fix the model, prompt, permissions, environment, and case cohort before comparing outcomes.
