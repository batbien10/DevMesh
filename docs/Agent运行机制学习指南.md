# 从 Java 后端到 Agent Runtime：DevMesh 新知识衔接指南

这份文档不是功能清单，而是一条学习路径。目标是让你能够回答三个层次的问题：

1. **会使用**：知道新增能力怎么运行、怎么看结果。
2. **懂原理**：知道为什么要这样设计，它解决了什么工程问题。
3. **能讲清楚**：能够从问题、取舍、实现和验证四个角度说明设计，而不是只背名词。

建议先通读第 1～4 章建立心智模型，再完成第 10 章的实验。完整架构说明见 [智能编程平台架构说明.md](智能编程平台架构说明.md)。

---

## 1. 先建立一个心智模型：Agent 到底是什么

普通聊天应用通常只有一次调用：

```text
用户输入 → 拼接 Prompt → 调用模型 → 返回文本
```

Agent 多了一个循环：模型不仅能回答，还能决定使用工具；工具结果又会成为下一轮模型输入。

```text
用户任务
  ↓
构造上下文 + 可用工具说明
  ↓
调用模型
  ├─ 模型直接回答 ──────────────→ 结束
  └─ 模型请求 Tool Call
          ↓
     权限检查 / Hook / Sandbox
          ↓
       执行工具
          ↓
     工具结果写回上下文
          └─────────────────────→ 下一轮模型调用
```

对应到 DevMesh：

- 循环入口：[Agent.java](../src/main/java/devmesh/agent/Agent.java)
- 模型协议适配：[llm/](../src/main/java/devmesh/llm/)
- 工具注册与 schema：[ToolRegistry.java](../src/main/java/devmesh/tool/ToolRegistry.java)
- 工具调度：[StreamingExecutor.java](../src/main/java/devmesh/agent/StreamingExecutor.java)
- 对话状态：[ConversationManager.java](../src/main/java/devmesh/conversation/ConversationManager.java)

因此 Agent Runtime 可以类比为一个面向大模型的“小型操作系统”：

| 操作系统概念 | Agent Runtime 中的对应物 |
| --- | --- |
| 进程调度 | Agent loop、子 Agent、Team |
| 系统调用 | Tool Call |
| 驱动/协议 | Anthropic、OpenAI、MCP adapter |
| 内存管理 | Context budget、compact、memory |
| 权限系统 | Permission、Hook、Sandbox |
| 监控系统 | Trace、Metrics、Eval |

这个类比很重要：Agent 开发岗位通常不只是“会写 Prompt”，而是要把不确定的模型行为放进一个可控制、可观察、可恢复的软件系统中。

---

## 2. Context Engineering：上下文不是聊天记录那么简单

### 2.1 Context window 不等于长期记忆

模型每次调用只看到本次请求携带的内容。Context window 是本次推理能容纳的 token 上限，不是数据库，也不是模型永久记住了前文。

一次 Agent 请求的 token 大致来自：

```text
总输入 ≈ System Prompt
       + 历史消息
       + Tool Schema
       + Tool Result
       + Memory / Skill / MCP Instructions
       + 本轮临时控制信息
```

其中 Tool Schema 很容易被忽视。每个工具都需要把名称、描述、参数 JSON Schema 发给模型；接入几十个 MCP 工具时，即使一个工具都没调用，它们也会持续消耗上下文。

### 2.2 原来的隐性问题：每轮提醒都变成永久历史

Agent 循环每轮都需要告诉模型一些“当前状态”，例如：

- 还有哪些 deferred tools 可以通过 `ToolSearch` 加载；
- 当前处于 Plan Mode；
- 计划文件在哪里；
- 这是第几轮。

如果每轮调用 `addSystemReminder()`，历史会变成：

```text
用户任务
第 1 轮：Plan Mode reminder
第 2 轮：Plan Mode reminder
第 3 轮：Plan Mode reminder
...
```

这些旧提醒已经失效，却在后续每次请求中反复发送，带来四个问题：

1. **token 泄漏**：轮数越多，固定控制信息占用越大。
2. **状态冲突**：模型可能同时看到第 2 轮和第 10 轮的不同状态。
3. **压缩提前**：无效消息促使 context compact 更早发生。
4. **缓存前缀不稳定**：频繁变化的内容可能降低 prompt cache 的复用价值。

### 2.3 新设计：durable transcript 与 ephemeral context 分离

现在 `ConversationManager` 保存两类数据：

```text
ConversationManager
├─ history                 持久历史
│  ├─ 用户消息
│  ├─ 模型回答
│  └─ 工具调用与结果
└─ ephemeralContext        瞬时、具名、可替换
   ├─ available-deferred-tools
   └─ plan-mode
```

核心 API 在 [ConversationManager.java](../src/main/java/devmesh/conversation/ConversationManager.java)：

```java
conversation.setEphemeralContext("plan-mode", currentReminder);
conversation.removeEphemeralContext("plan-mode");

List<Message> durable = conversation.getMessages();
List<Message> request = conversation.getMessagesForModel();
```

关键区别：

- `getMessages()`：用于持久化、恢复和历史压缩，不包含 ephemeral slot。
- `getMessagesForModel()`：发送给模型的 request envelope，包含当前 slot 的最新值。

同一个 key 更新十次，最终仍然只有一个 slot。可以把它理解成：

```java
// 错误心智模型：事件日志
history.add(new PlanState(iteration));

// 新心智模型：状态表
currentState.put("plan-mode", new PlanState(iteration));
```

这就是所谓 **Context Control Plane**：业务对话是 data plane，控制 Agent 当前行为的瞬时状态是 control plane。

### 2.4 为什么这也有利于 Prompt Cache

很多模型服务会缓存请求中的稳定前缀。直观理解：如果多轮请求前面很长一段内容完全相同，服务端可能复用这部分计算。

```text
请求 1：稳定 System + 稳定 Tools + 稳定 History + 新内容 A
请求 2：稳定 System + 稳定 Tools + 稳定 History + 新内容 B
        └────────────── 可复用的稳定前缀 ──────────────┘
```

优化重点不是“所有内容都不变”，而是让长且稳定的内容尽量靠前，把会变化的状态放到 request envelope 的尾部。Ephemeral context 不写进 transcript，能避免无效状态永久破坏后续历史结构。

注意：不同模型供应商的缓存实现和计费方式不同，所以项目最终使用服务商返回的 `cache_read_tokens`、`cache_creation_tokens` 验证，而不是只凭理论宣布优化成功。

### 2.5 为什么还需要分层 Compact

Ephemeral slot 只解决“无效控制消息累积”，不能消除真实任务产生的大量内容。因此项目还保留分层上下文治理：

1. **Tool-result budget**：单个巨大工具结果先落盘，只把预览留在上下文。
2. **Usage anchor**：优先使用供应商返回的真实 token usage，之后仅估算新增消息。
3. **Auto compact**：接近阈值时，总结较早历史，保留最近消息原文。
4. **Recovery attachment**：压缩后附上最近读取的文件和激活的 Skill，避免模型失去工作状态。

为什么不一开始就总结所有内容？因为摘要是有损压缩：变量名、错误行号、约束细节可能丢失，而且额外需要一次模型调用。正确策略是先删除或外置低价值的大块内容，只有逼近窗口时再做语义摘要。

---

## 3. 渐进式工具发现：为什么不是把所有工具都发给模型

### 3.1 Tool Call 的本质

模型并不会直接执行 Java 方法。Runtime 先把工具描述成 schema：

```json
{
  "name": "ReadFile",
  "description": "Read a text file",
  "input_schema": {
    "type": "object",
    "properties": {
      "file_path": { "type": "string" }
    },
    "required": ["file_path"]
  }
}
```

模型输出结构化的 tool call，Runtime 再校验参数、检查权限并执行对应实现。

这说明工具数量增加会产生两种成本：

- **上下文成本**：每轮都要发送 schema。
- **决策成本**：候选工具越多，模型选错或混淆相近工具的概率可能增加。

### 3.2 Deferred Tool + ToolSearch

MCP 工具默认设置为 deferred：注册在 Runtime 中，但 schema 不立即发送给模型。模型只先看到工具名索引和 `ToolSearch`：

```text
需要查数据库
  ↓
ToolSearch("database postgres")
  ↓
Runtime 找到 mcp__postgres__query
  ↓
下一轮才注入完整 schema
```

这类似搜索引擎的两阶段检索：

1. 先用低成本索引召回候选能力。
2. 再加载少量候选的完整定义。

项目测试 [ToolSearchTest.java](../src/test/java/devmesh/tool/ToolSearchTest.java) 用 50 个模拟工具验证 schema 延迟加载带来的体积节省。

### 3.3 Tool、MCP、Skill 不要混淆

| 概念 | 它是什么 | 解决什么问题 | 示例 |
| --- | --- | --- | --- |
| Tool | 模型可调用的结构化函数 | 让模型对外部世界执行动作 | ReadFile、Bash |
| MCP | Client/Server 能力协议 | 用统一协议连接外部工具和数据源 | GitHub MCP、数据库 MCP |
| Skill | 可复用的任务 SOP/知识包 | 教模型按特定流程完成任务 | 代码审查流程、发布流程 |
| Agent | 带循环、状态、模型和工具的执行实体 | 自主完成多步目标 | 主 Agent、reviewer Agent |

关系可以概括为：Skill 告诉 Agent“应该怎么做”，Tool/MCP 让 Agent“有能力去做”。

---

## 4. Observability：为什么有日志还要 Trace

### 4.1 Log、Metric、Trace 的区别

| 信号 | 回答的问题 | 例子 |
| --- | --- | --- |
| Log | 某一时刻发生了什么 | `Tool ReadFile failed` |
| Metric | 系统整体趋势如何 | 工具成功率 98%、p95 300 ms |
| Trace | 一次请求经过了哪些步骤 | Agent → LLM → Tool → LLM |

Agent 的一次任务可能包含多轮模型和几十个工具，只看零散日志很难还原因果关系。Trace 把一次任务表示为一组有层级关系的 span。

```text
invoke_agent devmesh                    2.8 s
├─ context_budget                       0 ms
├─ chat model-A                         1.4 s
├─ execute_tool ReadFile                8 ms
├─ execute_tool Grep                    15 ms
└─ chat model-A                         1.2 s
```

### 4.2 Trace、Span、Attribute

- **Trace**：一次端到端任务，有统一 `trace_id`。
- **Span**：任务中的一个有开始、结束和耗时的操作。
- **Attribute**：用于筛选和聚合 span 的结构化字段。

项目参考 OpenTelemetry GenAI 语义，使用：

- `invoke_agent`：Agent 生命周期；
- `chat`：模型推理；
- `execute_tool`：工具执行；
- `gen_ai.request.model`：模型；
- `gen_ai.usage.input_tokens`：输入 token；
- `gen_ai.tool.name`：工具名。

实现在 [AgentTracer.java](../src/main/java/devmesh/observability/AgentTracer.java)，接入点在 [Agent.java](../src/main/java/devmesh/agent/Agent.java) 和 [StreamingExecutor.java](../src/main/java/devmesh/agent/StreamingExecutor.java)。

### 4.3 为什么本项目先写本地 JSONL，而不是直接上监控平台

完整 OpenTelemetry SDK + Collector + Grafana/Jaeger 更适合生产环境，但简历项目首先需要：

1. 零配置可运行；
2. 不依赖外部服务；
3. 轨迹可以放进测试和 CI；
4. 以后能够映射到标准语义。

因此先采用“一行一个 span”的 JSONL。它是一种工程取舍，不代表 JSONL 就等于 OpenTelemetry 协议。准确说法应是：**字段对齐 OpenTelemetry GenAI semantic conventions 的本地 flight recorder**。

### 4.4 为什么默认不记录 Prompt 和工具参数值

可观测性和安全存在冲突。Agent 内容中可能包含：

- API Key 和认证头；
- 用户代码；
- 文件路径；
- Bash 命令；
- 数据库结果；
- 模型 reasoning 内容。

这些内容一旦进入 trace，可能被长期保留或上传到第三方系统。因此项目默认只记录：

```text
记录：工具名、参数 key、状态、耗时、token
不记录：参数 value、Prompt、文件内容、工具输出、API Key
```

这叫 **content-safe by default**。它牺牲了一部分调试细节，换取更安全的默认行为。生产设计中应把内容采集做成显式 opt-in，并增加脱敏、访问控制和保留周期。

### 4.5 Flight Recorder 为什么不能影响主业务

可观测性是旁路能力。如果写 trace 失败就导致 Agent 失败，相当于“监控系统让业务系统宕机”。所以 `AgentTracer` 对目录创建和写入错误采用降级策略：停止或忽略记录，但不打断 Agent loop。

这是一种常见可靠性原则：

> 非关键旁路失败，不应扩大为核心调用链失败。

---

## 5. Trace Analyzer：指标是怎么来的

[TraceAnalyzer.java](../src/main/java/devmesh/observability/TraceAnalyzer.java) 离线读取 JSONL，不再调用模型。

### 5.1 工具成功率

```text
tool_success_rate = (tool_calls - tool_errors) / tool_calls
```

成功率下降只能说明工具链不稳定，不能直接说明原因。还需要结合 `error.type` 区分参数错误、权限拒绝、Hook 拒绝、MCP 网络错误等。

### 5.2 Prompt cache read ratio

项目当前定义：

```text
cache_read_ratio = cache_read_input_tokens / input_tokens
```

前提是 `input_tokens` 已包含 cached input。供应商 usage 口径可能不同，所以跨供应商比较前要先做规范化，不能只比较一个百分比。

### 5.3 Context pressure

```text
context_pressure = estimated_context_tokens / context_window
```

例如压力为 `0.82`，表示估算已使用窗口的 82%。它适合判断 compact 是否过早或过晚，但不是精确 tokenizer 结果；冷启动阶段使用字符启发式估算，有真实 usage 后再通过 anchor 修正。

### 5.4 p50 和 p95 延迟

- p50：一半请求比它快，一半比它慢，代表典型体验。
- p95：95% 请求比它快，最慢的 5% 在它之后，代表尾延迟。

平均值容易被少数极慢请求扭曲。比如九次 100 ms、一次 5 s，平均值约 590 ms，但绝大多数用户实际体验是 100 ms；同时 p95 又能提醒你存在慢尾问题。

样本太少时 percentile 没有统计意义，所以不要用一次运行的 p95 宣称性能提升，应重复运行并说明样本规模。

---

## 6. Eval：为什么“能跑”不等于“可靠”

### 6.1 Agent 评测至少有三个层次

| 层次 | 评测对象 | 示例 |
| --- | --- | --- |
| Outcome Eval | 最终结果是否正确 | 测试是否通过、答案是否正确 |
| Trajectory Eval | 过程是否符合要求 | 是否调用 ReadFile、是否误用危险工具 |
| System Eval | 系统是否稳定高效 | token、延迟、错误、重试、成本 |

本次新增的 [TraceEvaluator.java](../src/main/java/devmesh/observability/TraceEvaluator.java) 主要覆盖确定性的 Trajectory/System Eval，不应夸大成完整的结果正确性评测。

### 6.2 为什么离线门禁有价值

如果每次回归都重新调用模型，会遇到：

- 成本高；
- 速度慢；
- 模型输出有随机性；
- 供应商或网络不可用；
- 很难在 CI 中稳定复现。

记录真实运行轨迹后，可以对同一批 trace 重复执行确定性规则：

```yaml
min_agent_runs: 1
max_failed_runs: 0
max_tool_errors: 0
max_retries: 3
max_malformed_records: 0
min_tool_success_rate: 0.95
max_peak_context_pressure: 0.95
required_tools: []
forbidden_tools: []
```

默认策略见 [agent-reliability.yaml](../evals/agent-reliability.yaml)。门禁失败返回退出码 `2`，因此 CI 可以直接判断失败。

### 6.3 required_tools 和 forbidden_tools 的意义

假设场景要求 Agent 在修改文件前必须先读取文件：

```yaml
required_tools: [ReadFile]
```

又或者一个只读审查 Agent 绝不能写文件：

```yaml
forbidden_tools: [WriteFile, EditFile]
```

这不是判断最终代码是否正确，而是验证“行为路径是否满足安全和流程约束”。成熟评测通常会把轨迹门禁和单元测试、静态检查、人工或 LLM judge 组合起来。

### 6.4 为什么空 trace 不能通过

一个容易忽略的漏洞是：空目录中失败数为 0、错误数为 0，看起来所有上限都满足。项目因此增加 `min_agent_runs: 1`，避免“什么都没执行”被判为成功。

这个例子体现了 Eval 设计的本质：不仅要定义成功条件，还要防止指标被无意义地满足。

---

## 7. Multi-Agent：多一个 Agent 不一定更好

### 7.1 为什么使用多 Agent

多 Agent 适合：

- 能明确拆分、可以并行的子任务；
- 需要不同权限或工具集合的角色；
- 需要独立上下文，避免主 Agent 被大量探索信息污染；
- reviewer 与 implementer 之间需要职责隔离。

不适合：

- 任务本身很小；
- 子任务高度依赖，无法并行；
- 协调成本大于执行成本；
- 只是为了在简历中出现“multi-agent”。

### 7.2 本项目有哪些多 Agent 机制

- 一次性同步子 Agent；
- 后台子任务；
- Team mailbox；
- shared task store；
- Git worktree 文件隔离；
- 工具白名单/黑名单；
- coordinator mode。

本次为主 Agent、`subagent:{type}`、`teammate:{name}` 写入不同 `gen_ai.agent.name`，从而可以区分它们的调用和成本。

### 7.3 当前边界：独立 trace 不等于分布式 trace

现在每个 Agent 生成独立 trace。要形成：

```text
主 Agent trace
└─ 子 Agent span
   └─ MCP span
```

还需要在 spawn、mailbox 或远程协议中传播 `trace_id`、`parent_span_id`。这叫 **context propagation**，是下一步演进方向。介绍设计时明确边界比声称“已经全链路追踪”更可信。

---

## 8. 安全执行链：Permission、Hook、Sandbox 各自做什么

三者不是同一个概念：

```text
Tool Call
  ↓
Permission：这个操作是否允许？是否需要用户确认？
  ↓
Pre Hook：是否满足组织/项目的额外规则？
  ↓
Sandbox：即使代码恶意或失控，操作系统层面能访问什么？
  ↓
Tool Implementation
  ↓
Post Hook：记录、检查或触发后续动作
```

- Permission 是策略决策。
- Hook 是生命周期扩展点。
- Sandbox 是执行隔离边界。

只做 Prompt 约束不能替代它们，因为模型可能误解或忽略文字指令。安全边界应尽量落在确定性代码和操作系统能力上。

### Windows Bash 修复背后的知识

Windows 的 `C:\Windows\System32\bash.exe` 可能只是 WSL launcher，并不代表某个 Linux 发行版可用。原代码直接执行 `bash -c`，因此会出现“命令存在但无法真正启动”的假阳性。

新逻辑在 [HookEngine.java](../src/main/java/devmesh/hook/HookEngine.java) 中：

1. 优先尊重 `DEVMESH_BASH`；
2. Windows 上寻找 Git Bash；
3. 使用 `git --exec-path` 推导 Git 安装目录；
4. 命令超时时终止完整 descendants，而不只杀掉外层 bash。

为什么要杀进程树？Shell 往往会再启动真正的子进程。只结束 Shell，`sleep`、编译器或脚本可能继续运行，造成资源泄漏和幽灵任务。

---

## 9. Java 工程知识：Virtual Thread 与 `--release 21`

### 9.1 为什么 Agent Runtime 适合虚拟线程

Agent 大部分时间在等待：

- 等待模型流式网络响应；
- 等待 MCP；
- 等待文件或进程；
- 等待用户授权；
- 等待子 Agent。

这些是 I/O-bound 工作。Java 21 虚拟线程可以让代码继续使用直观的阻塞写法，同时比“一请求一平台线程”更容易承载大量并发等待任务。

虚拟线程不会让模型推理或 CPU 密集计算自动变快；它优化的是线程占用和并发可扩展性。

### 9.2 Tool 并行不是越多越好

`StreamingExecutor` 只并发执行相邻的只读工具，写操作和命令保持顺序。这是因为：

- 两个独立 ReadFile 通常可以并行；
- 两个 EditFile 可能修改同一文件；
- Bash 可能依赖上一个命令生成的文件；
- 并行写会带来竞态和不可复现问题。

因此并发策略依据工具的副作用，而不是简单把所有 tool call 放入线程池。

### 9.3 Toolchain、sourceCompatibility 和 release

原构建声明“必须找到精确 JDK 21 toolchain”，所以本机只有 JDK 22 时 Gradle 会拒绝构建。现在：

```kotlin
sourceCompatibility = JavaVersion.VERSION_21
targetCompatibility = JavaVersion.VERSION_21
options.release = 21
```

含义是：可以用 JDK 21 或更高版本的编译器，但编译结果只能使用 Java 21 提供的语言和标准库 API，并生成 Java 21 可运行的字节码。

`--release 21` 比只设置 target bytecode 更严格，因为它也限制可引用的 JDK API，避免在 JDK 22 编译时误用 Java 22 才有的类，最后放到 JDK 21 运行时报错。

---

## 10. 五个动手实验

这些实验都可以不调用真实模型 API。

### 实验一：验证 ephemeral slot 不污染历史

运行：

```powershell
.\gradlew.bat test --tests devmesh.conversation.ConversationManagerEphemeralContextTest
```

阅读 [ConversationManagerEphemeralContextTest.java](../src/test/java/devmesh/conversation/ConversationManagerEphemeralContextTest.java)，重点观察：

- 同一个 key 更新两次，旧值消失；
- `size()` 仍然只有一条持久消息；
- `getMessagesForModel()` 比 `getMessages()` 多一个 request envelope。

### 实验二：阅读一条脱敏 Trace

正常运行 Agent 后：

```powershell
Get-Content .\.devmesh\traces\*.jsonl | Select-Object -First 3
```

检查是否只有 operation、duration、token、tool name 和 argument keys，没有真实 Prompt 与参数值。

### 实验三：生成聚合报告

```powershell
java -jar .\build\libs\devmesh.jar --trace-report .\.devmesh\traces
java -jar .\build\libs\devmesh.jar --trace-report .\.devmesh\traces --output-format json
```

思考：如果 `tool_success_rate` 很低，你还需要什么维度才能定位问题？答案通常是按工具名、错误类型、MCP server、Agent name 分组。

### 实验四：故意让 Eval 失败

复制一份策略，然后把运行中使用过的工具加入 `forbidden_tools`：

```powershell
Copy-Item .\evals\agent-reliability.yaml .\evals\local-learning.yaml
```

例如：

```yaml
forbidden_tools: [ReadFile]
```

运行：

```powershell
java -jar .\build\libs\devmesh.jar `
  --trace-eval .\.devmesh\traces `
  --trace-policy .\evals\local-learning.yaml

$LASTEXITCODE
```

预期门禁失败，退出码为 `2`。实验后可删除本地策略，不要把场景策略误当成全局可靠性策略。

### 实验五：验证完整工程

```powershell
.\gradlew.bat clean test shadowJar
java -jar .\build\libs\devmesh.jar --trace-report .\.devmesh\traces
```

你应该能解释：第一条命令验证代码和打包，第二条验证离线可观测 CLI；它们仍不能证明真实模型完成任务的质量，所以还需要真实场景数据集和 outcome eval。

---

## 11. 如何讲清设计，而不是背功能

建议使用“问题—方案—取舍—验证”结构。

### 例一：Context Control Plane

> 我发现 Agent 每轮把 Plan Mode 和 deferred tools reminder 追加到历史，长任务中旧状态会反复占用 token，还可能影响 prompt cache。于是我把 durable transcript 和 ephemeral named slots 分离：session/compact 只操作 transcript，推理前再构造包含最新 slot 的 request envelope。它避免了控制消息随轮数线性增长。为了不只做理论优化，我又把 history、ephemeral、tool schema 的估算 token 和 context pressure 写进 trace，后续可以用真实运行对比 compact 次数和 cache usage。

### 例二：Agent Flight Recorder

> Agent 的问题通常跨越多轮 LLM 和工具，普通日志无法还原一次任务的完整路径。我参考 OpenTelemetry GenAI 的 invoke_agent、chat、execute_tool 语义实现本地 JSONL flight recorder，并默认不采集 Prompt、参数值和工具输出。再用离线 Analyzer 统计 token、cache、工具成功率和 p50/p95，既能调试，也能为 CI Eval 提供稳定输入。取舍是目前没有直接导出 OTLP，也没有跨子 Agent 传播 trace context，这两点是后续演进方向。

### 例三：Trace Eval

> 我把评测拆成 outcome、trajectory 和 system 三层。本次先实现确定性 trajectory/system gate，例如工具错误、重试、上下文压力、required/forbidden tools，并显式要求 min_agent_runs，防止空数据通过。它便宜且可复现，但不能替代单元测试或 LLM judge 对最终结果正确性的评估。

进一步讨论时，优先说明真实边界和验证方法。不要说“接入了 OpenTelemetry”——目前准确表述是“字段语义对齐 OpenTelemetry GenAI 的本地 recorder”；不要说“实现了完整 MCP”——目前主要接入 MCP tools。

---

## 12. 七天快速学习计划

| 天数 | 学习内容 | 产出 |
| --- | --- | --- |
| Day 1 | 阅读 Agent loop、ConversationManager、ToolRegistry | 手画一次 tool call 时序图 |
| Day 2 | 学 token、context window、tool schema、prompt cache | 能解释 durable 与 ephemeral 的区别 |
| Day 3 | 阅读 ContextCompactor、ToolResultBudget | 画出分层 compact 流程 |
| Day 4 | 学 trace/span/attribute、阅读 AgentTracer | 手动解释一行 JSONL |
| Day 5 | 运行 Analyzer/Eval 五个实验 | 保存一次报告并解释每个指标 |
| Day 6 | 阅读 MCP、Skill、SubAgent/Team 调用链 | 完成 Tool/MCP/Skill 对比表复述 |
| Day 7 | 按第 11 章组织项目介绍 | 录制 3～5 分钟介绍并复盘 |

判断自己是否真正掌握，可以用下面五个问题自测：

1. 为什么 ephemeral context 不能直接替代所有 history？
2. 为什么 deferred tool 能节省 token，但也可能增加一次 ToolSearch 轮次？
3. 为什么 trace 默认不记录参数值？
4. 为什么 trajectory eval 通过仍不能证明最终代码正确？
5. 为什么只读工具可以并行，而写工具默认不并行？

如果你能结合本项目代码回答，而不是只给概念定义，就已经具备比较扎实的 Agent Runtime 入门能力。

---

## 13. 术语速查

| 术语 | 一句话解释 |
| --- | --- |
| Context Window | 单次模型请求能够处理的 token 上限 |
| Transcript | 可持久化、可恢复的对话与工具历史 |
| Request Envelope | 某一次真正发送给模型的完整消息集合 |
| Ephemeral Context | 只服务当前推理、可替换、不持久化的控制信息 |
| Prompt Cache | 服务端对稳定请求前缀的计算复用 |
| Tool Schema | 描述工具名称、功能和参数结构的 JSON Schema |
| MCP | Agent 应用连接工具和上下文服务的标准协议 |
| Span | Trace 中一个有时间范围的操作 |
| Trajectory | Agent 完成任务时走过的模型与工具路径 |
| Eval Gate | 根据规则自动判定某批运行是否满足质量要求 |
| Context Propagation | 在进程、Agent 或服务之间传递 trace 父子关系 |
| Tail Latency | 少数最慢请求的延迟，常用 p95/p99 表示 |

## 14. 推荐的一手资料

- [OpenTelemetry GenAI span definitions](https://github.com/open-telemetry/semantic-conventions/blob/main/model/gen-ai/spans.yaml)
- [Model Context Protocol specification](https://modelcontextprotocol.io/specification/)
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [Gradle Java Toolchains](https://docs.gradle.org/current/userguide/toolchains.html)

阅读官方资料时不要追求一次看完。先带着本项目中的具体问题去查：例如“execute_tool span 应记录什么”“MCP tool schema 如何协商”“`--release` 与 toolchain 有什么差别”，学习效率会高很多。
