# DevMesh 功能演示指南

这套流程用于在约 10 分钟内展示 DevMesh 的工程完整性、Agent 交互能力、可观测性和 Skill 进化机制。

## 1. 构建与测试

Windows PowerShell：

```powershell
.\gradlew.bat test shadowJar
```

预期结果：

- Gradle Wrapper 校验通过；
- 120 项自动化测试通过；
- 生成 `build/libs/devmesh.jar`。

## 2. Remote Web 交互

先复制配置模板，并在本地填写 Provider 信息。真实配置已被 `.gitignore` 排除，不要提交 API Key。

```powershell
Copy-Item .\.devmesh\config.yaml.example .\.devmesh\config.yaml
java -jar .\build\libs\devmesh.jar .\.devmesh\config.yaml --remote=127.0.0.1:18888
```

浏览器访问 <http://127.0.0.1:18888>，可以使用下面的无工具提示验证流式输出：

```text
请用三点简短介绍 DevMesh：多模型接入、MCP 工具、上下文治理与运行轨迹评测。不要调用工具。
```

如需演示代码分析，请只选择允许发送给模型的公开文件，并在提示中明确“只读、不修改、不读取配置文件”。

## 3. Trace 报告与质量门禁

完成一次任务后执行：

```powershell
java -jar .\build\libs\devmesh.jar --trace-report .\.devmesh\traces

java -jar .\build\libs\devmesh.jar `
  --trace-eval .\.devmesh\traces `
  --trace-policy .\evals\agent-reliability.yaml
```

重点说明：轨迹记录操作名、耗时、token、状态和参数键名，默认不记录提示词、API Key、文件内容和工具返回值。

## 4. Verified Skill Evolution

先运行无需模型 API 的核心生命周期测试：

```powershell
.\gradlew.bat test --tests "devmesh.evolution.*"
```

然后查看以下可复现输入：

- `examples/skill-evolution/safe-java-refactor.yaml`
- `examples/skill-evolution/baseline-outcomes.jsonl`
- `examples/skill-evolution/candidate-outcomes.jsonl`
- `evals/skill-evolution.yaml`

演示时可重点说明 `quarantine → canary → outcome/trace/non-regression gates → verified → promoted` 的状态变化，以及失败后的拒绝和回滚路径。

## 5. 建议演示顺序

1. 从 `devmesh.DevMesh` 说明启动与模式选择；
2. 从 `Agent` 说明 ReAct 与流式 Tool Calling；
3. 从 `ToolRegistry`、`PermissionChecker`、`HookEngine` 和 `Sandbox` 说明安全执行链；
4. 从 `AgentTracer`、`TraceEvaluator` 说明可观测性和质量门禁；
5. 从 `SkillEvolutionService` 说明候选 Skill 如何验证、发布和回滚。
