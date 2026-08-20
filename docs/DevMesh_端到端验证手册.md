# DevMesh Agent Flight Recorder、Trace Eval 与 Verified Skill Evolution 验证手册
## ——Windows 图形界面优先版（小白友好）

> 适用项目：`DevMesh`<br>
> 运行环境：Windows + JDK 21<br>
> 验证目标：
>
> 1. 验证 **Agent Flight Recorder** 是否真的记录 `invoke_agent`、`chat`、`execute_tool`；
> 2. 验证 Trace 是否默认不保存 prompt、工具参数值和工具输出；
> 3. 验证 **Trace Eval** 是否可以脱离大模型离线统计 token、缓存、工具成功率、p50/p95 延迟、retry、compact、context pressure；
> 4. 验证 YAML 质量门禁能否正确 PASS / FAIL；
> 5. 验证 **Verified Skill Evolution** 是否真的存在 `QUARANTINED → VERIFIED → PROMOTED → ROLLED_BACK` 生命周期；
> 6. 理解 **人工审核到底发生在哪里、为什么需要人工审核、人工应该看什么**。
>
> 本手册尽量使用 **鼠标 + Windows 文件资源管理器 + IDE 图形界面**。只有项目本身必须通过 CLI 执行的离线命令，才使用少量终端命令。

---

# 1. 先不要急着操作：先把整个系统想成一条生产线

你现在验证的不是三个完全独立的功能，而是一条前后关联的链路：

```text
用户给 Agent 一个任务
        ↓
Agent 开始工作
        ↓
Agent Flight Recorder 在旁边记录“运行元数据”
        ↓
生成 trace.jsonl
        ↓
Trace Eval 离线分析 trace
        ↓
得到 token / latency / tool success / retry / context pressure 等指标
        ↓
YAML Gate 判断这次运行是否合格
        ↓
如果 Agent 从多次任务中总结出一套可复用经验
        ↓
生成 Candidate Skill
        ↓
Candidate 先进入 QUARANTINED（隔离区）
        ↓
人工初审 Candidate 内容
        ↓
Baseline 与 Candidate 做相同任务 A/B
        ↓
自动检查：
    Candidate 溯源
    Trace Gate
    Paired Outcome
    Non-regression
        ↓
全部通过
        ↓
VERIFIED
        ↓
人工最终审核
        ↓
人工显式执行 Promote
        ↓
PROMOTED，成为正式 Skill
        ↓
上线后发现问题
        ↓
人工执行 Rollback
        ↓
ROLLED_BACK
```

最关键的一句话是：

> **自动评测负责提供证据，人工审核负责决定是否真的让 Candidate 成为正式 Skill。**

这也是 Verified Skill Evolution 与“Agent 自己学完马上生效”的最大区别。

---

# 2. 你项目中这三部分分别在哪里

你上传的项目中，相关核心代码已经存在。

## 2.1 Agent Flight Recorder

主要代码：

```text
src/main/java/devmesh/observability/AgentTracer.java
```

Agent 主循环中的接入：

```text
src/main/java/devmesh/agent/Agent.java
```

工具执行过程还会由相关执行器写 Trace。

你项目中的 Trace 默认放在：

```text
.devmesh/traces/
```

Trace 文件通常是：

```text
xxxxxx.jsonl
```

---

## 2.2 Trace Eval

主要代码：

```text
src/main/java/devmesh/observability/TraceAnalyzer.java
src/main/java/devmesh/observability/TraceEvaluator.java
```

质量规则：

```text
evals/agent-reliability.yaml
```

主程序入口：

```text
src/main/java/devmesh/DevMesh.java
```

项目支持：

```text
--trace-report
--trace-eval
--trace-policy
```

---

## 2.3 Verified Skill Evolution

主要目录：

```text
src/main/java/devmesh/evolution/
```

其中包含：

```text
SkillEvolutionCli.java
SkillEvolutionService.java
SkillEvolutionStore.java
SkillCandidate.java
SkillOutcomeEvaluator.java
SkillFitnessComparator.java
```

默认数据目录：

```text
.devmesh/evolution/
```

正式 Skill：

```text
.devmesh/skills/
```

验证策略：

```text
evals/skill-evolution.yaml
```

项目还自带一个 Candidate 示例：

```text
examples/skill-evolution/safe-java-refactor.yaml
```

---

# 3. 为什么 Agent Flight Recorder 类似“飞机黑匣子”

飞机的 Flight Recorder 不负责让飞机飞。

它只负责记录：

```text
什么时候发生了什么
哪个部件工作了
持续多久
有没有异常
```

Agent Flight Recorder 也是一样。

假设用户对 Agent 说：

```text
读取 README.md，然后总结项目。
```

Agent 可能经历：

```text
invoke_agent
│
├── chat
│   └── 大模型判断：需要读取 README
│
├── execute_tool
│   └── Java 执行 ReadFile
│
└── chat
    └── 模型结合工具结果形成回答
```

Flight Recorder 主要记录：

```text
调用了哪个模型
调用了多少 token
花了多少时间
调用了什么 Tool
Tool 成功还是失败
Context 使用了多少
有没有 retry
有没有 compact
```

但默认不应该记录：

```text
用户具体 prompt
工具参数的具体值
ReadFile 读出来的正文
工具最终输出正文
```

这就是所谓的：

> **Content-safe Telemetry：记录运行状态，而不是把业务内容全部复制一份。**

---

# 4. Trace、Span、trace_id、span_id 到底是什么

这是理解 OpenTelemetry 风格 Trace 最重要的基础。

把一次 Agent 任务想象成一次“旅行”。

整个旅行：

```text
Trace
```

旅行中的每一站：

```text
Span
```

例如：

```text
Trace A
│
├── Span 1：invoke_agent
│
├── Span 2：chat
│
├── Span 3：execute_tool
│
└── Span 4：chat
```

因此：

```text
trace_id
```

表示：

> 这些 Span 属于同一次 Agent 任务。

而：

```text
span_id
```

表示：

> 当前具体是哪一步。

如果还存在：

```text
parent_span_id
```

就可以知道：

> 当前步骤是谁调用出来的。

于是多个独立日志行，就可以重新拼成一棵调用树。

---

# 5. 先做准备工作：尽量全部用鼠标完成

## 5.1 解压项目

在 Windows 中：

1. 找到 `DevMesh.zip`；
2. 右键；
3. 选择“全部解压”；
4. 例如解压到：

```text
D:\Projects\DevMesh
```

之后整个验证过程都在这个目录完成。

---

## 5.2 用 IDE 打开

推荐二选一：

### IntelliJ IDEA

鼠标操作：

```text
File
→ Open
→ 选择 DevMesh 文件夹
→ Open
```

等待 Gradle 导入完成。

### VS Code

鼠标操作：

```text
File
→ Open Folder
→ 选择 DevMesh
```

如果提示安装 Java / Gradle 扩展，可以安装官方 Java Extension Pack。

---

## 5.3 确认 JDK 21

项目 `build.gradle.kts` 明确要求：

```text
JavaVersion.VERSION_21
```

IntelliJ IDEA 中可以：

```text
File
→ Project Structure
→ Project
→ SDK
```

确认是 JDK 21。

如果不是 21：

```text
Add SDK
→ JDK
→ 选择本机 JDK 21
```

---

# 6. 第一个正式实验：验证 Flight Recorder

这部分我们的目标不是先看代码，而是制造一个真实 Agent 运行，然后亲眼找到 Trace。

---

# 7. 用图形界面运行 Agent

项目主类是：

```text
devmesh.DevMesh
```

你可以在 IntelliJ IDEA 中打开：

```text
src/main/java/devmesh/DevMesh.java
```

找到：

```java
public static void main(String[] args)
```

左边通常会出现绿色三角形。

但是第一次为了传入参数，建议建立一个 Run Configuration。

---

## 7.1 IntelliJ IDEA 建立运行配置

点击右上角运行配置：

```text
Edit Configurations...
```

然后：

```text
+
→ Application
```

填写：

```text
Name:
Agent Flight Recorder Test

Main class:
devmesh.DevMesh
```

Program arguments 填：

```text
.devmesh/config.yaml -p "请读取 README.md，然后告诉我这个项目主要做什么"
```

Working directory 选择项目根目录：

```text
D:\Projects\DevMesh
```

点击：

```text
Apply
→ OK
```

最后点击绿色运行按钮。

> 如果你的实际配置文件不是 `.devmesh/config.yaml`，这里换成你平时能正常运行 Agent 的配置文件。

---

## 7.2 为什么故意让 Agent “读取 README”

因为如果只问：

```text
你好
```

Agent 可能只产生：

```text
invoke_agent
chat
```

不一定需要工具。

而：

```text
请读取 README.md
```

通常会迫使 Agent：

```text
大模型判断需要文件
        ↓
调用 ReadFile Tool
        ↓
Java 执行 Tool
```

这样一次实验就更容易覆盖：

```text
invoke_agent
chat
execute_tool
```

---

# 8. 不用命令行，直接用资源管理器检查 Trace

Agent 运行完成以后，不要急着看终端。

直接打开：

```text
D:\Projects\DevMesh
```

如果看不到 `.devmesh`，Windows 资源管理器中打开：

```text
查看
→ 显示
→ 隐藏的项目
```

然后进入：

```text
.devmesh
→ traces
```

正常情况下应该看到新的：

```text
xxxxxxxx.jsonl
```

这一步证明：

> **Agent Flight Recorder 至少真的创建了运行记录，而不是只有 Java 类存在。**

---

# 9. 用 VS Code / IDEA 直接打开 Trace

双击最新的 `.jsonl` 文件。

在编辑器中按：

```text
Ctrl + F
```

依次搜索：

```text
invoke_agent
```

再搜索：

```text
chat
```

再搜索：

```text
execute_tool
```

如果三者都存在，那么你实际验证了：

```text
Agent 整体调用
大模型调用
工具调用
```

都进入了 Flight Recorder。

---

# 10. 为什么 Trace 使用 JSONL，而不是一个大 JSON

JSONL 可以理解为：

```text
一行 = 一条事件
```

例如：

```json
{"operation":"chat", ...}
{"operation":"execute_tool", ...}
{"operation":"chat", ...}
```

这种格式很适合 Trace。

因为 Agent 运行的时候可以：

```text
发生一件事
→ 立刻追加一行

再发生一件事
→ 再追加一行
```

不用等整个任务结束，再构造一个巨大 JSON。

这也意味着：

> 即使程序中途崩溃，之前已经写入的 Trace 通常仍然保留。

---

# 11. 验证“不保存 Prompt”：最好做一次故意泄密实验

这是 Flight Recorder 非常重要的安全验证。

不要只相信代码中的：

```text
content_captured = false
```

最好自己设计一个只有你知道的唯一字符串。

例如：

```text
FLIGHT_SECRET_928374
```

然后把 Agent 运行参数改成：

```text
请读取 README.md。测试标记为 FLIGHT_SECRET_928374。
```

重新运行一次。

运行结束后：

```text
打开 .devmesh/traces
→ 打开最新 jsonl
→ Ctrl + F
```

搜索：

```text
FLIGHT_SECRET_928374
```

正确情况：

```text
0 个结果
```

这就说明：

```text
这个字符串进入了 Agent Prompt
        ↓
但是没有进入 Trace
```

这比单纯看：

```java
content_captured = false
```

更有说服力。

---

# 12. 验证 Trace 里面仍然有“非敏感元数据”

同一个 Trace 中搜索：

```text
devmesh.telemetry.content_captured
```

应该能看到类似：

```json
"devmesh.telemetry.content_captured": false
```

再搜索：

```text
gen_ai.request.model
```

可能看到模型名。

再搜索：

```text
gen_ai.usage.input_tokens
```

再搜索：

```text
gen_ai.usage.output_tokens
```

因此它做的不是：

```text
什么都不记录
```

而是：

```text
不记录正文
+
记录可观测性元数据
```

---

# 13. 验证“工具参数只记录名字，不记录值”

例如 ReadFile 真正的工具调用可能是：

```json
{
  "file_path": "D:\\Projects\\DevMesh\\README.md"
}
```

如果 Flight Recorder 把整段都保存，那么：

```text
具体目录
用户名
内部服务器地址
数据库账号
业务参数
```

以后都可能进入 Trace。

所以你的设计是只保存类似：

```json
"devmesh.tool.argument_keys": ["file_path"]
```

它表达：

```text
Agent 使用了 file_path 这个参数
```

但不知道：

```text
file_path 到底等于什么
```

---

## 13.1 鼠标验证方法

打开最新 Trace。

按：

```text
Ctrl + F
```

搜索：

```text
argument_keys
```

如果调用过文件工具，应该能看到类似：

```text
file_path
```

然后搜索完整路径的一部分，例如：

```text
D:\Projects
```

或者搜索：

```text
README.md
```

理想情况下：

```text
参数 key 能看到
参数具体 value 不应该作为 tool argument 被记录
```

> 注意：如果某些路径因为其他非工具参数原因出现在别的系统字段中，需要具体判断；真正要验证的是 Tool 参数值没有直接作为 Tool 参数 payload 被 Trace 保存。

---

# 14. 工具输出为什么也不应该直接进 Trace

假设：

```text
ReadFile
```

读取的是：

```text
公司内部合同.docx
```

如果工具输出全文也进入 Trace：

```text
业务数据会在正常文件之外再复制一份到 traces/
```

以后：

```text
做 Eval
上传 CI Artifact
排查问题
```

都有可能额外暴露正文。

所以 Flight Recorder 最合理的职责是：

```text
Tool = ReadFile
状态 = OK
耗时 = 85 ms
```

而不是：

```text
Tool Output = 整个文件正文
```

这也是“可观测性数据”和“业务数据”应该分开的原因。

---

# 15. Trace Eval：为什么不再需要调用大模型

现在 Agent 已经跑完，并产生了：

```text
trace.jsonl
```

里面已经有：

```text
token
latency
tool status
retry
compact
context pressure
```

所以 Trace Eval 只是：

```text
读文件
        ↓
做统计
        ↓
和规则比较
```

本质是：

```text
Java 本地程序
```

而不是：

```text
再次问大模型：
“你觉得刚刚跑得怎么样？”
```

所以它具有两个非常重要的特点：

```text
确定性更强
成本更低
```

也适合 CI。

---

# 16. Trace Report 目前有一个地方必须用少量命令

你的项目现在已经提供：

```text
--trace-report
```

它是 CLI 功能。

因此最省事的方法是：

```text
IDE 内置 Terminal
```

而不是单独打开 PowerShell。

在 IntelliJ IDEA：

```text
View
→ Tool Windows
→ Terminal
```

在 VS Code：

```text
Terminal
→ New Terminal
```

输入：

```text
java -jar build/libs/devmesh.jar --trace-report .devmesh/traces
```

这条命令只做一件事：

> **读取 traces 文件夹，然后打印汇总。**

---

# 17. Trace Report 里的指标到底怎么看

典型输出：

```text
Agent trace report

traces/runs
failed runs

model calls
tool calls

tokens
prompt cache

retries/compacts

peak context
```

下面逐个解释。

---

## 17.1 input tokens

可以简单理解为：

> 这次调用让大模型“看了多少内容”。

里面可能包括：

```text
系统 Prompt
历史对话
用户问题
Tool Schema
临时 Context
```

---

## 17.2 output tokens

就是：

> 大模型输出了多少 token。

如果 Candidate Skill 加入以后：

```text
Baseline：10000 input tokens
Candidate：28000 input tokens
```

即使 Candidate 最终成功，也说明它可能让上下文膨胀很多。

所以 token 是：

```text
成本指标
+
上下文效率指标
```

---

## 17.3 Cache Read

假设：

```text
input tokens = 10000
cache read = 6000
```

可以理解为：

> 其中有相当一部分输入命中了模型提供商的 Prompt Cache，不需要完整重复计算。

因此 Cache Read Ratio 越高，有时意味着：

```text
重复上下文利用得更好
```

但它不是“越高越一定好”，还需要结合业务场景理解。

---

## 17.4 Tool Success Rate

假设：

```text
Tool 调用 = 100
Tool Error = 4
```

则大致：

```text
Tool Success Rate = 96%
```

这个指标在 Agent 系统中很重要。

因为 Agent 最终能不能把事情做完，往往不仅取决于模型“会不会想”，还取决于：

```text
工具是否真的执行成功
```

---

# 18. p50 与 p95 是什么

假设你收集了很多次模型调用延迟：

```text
100ms
120ms
150ms
180ms
190ms
210ms
220ms
250ms
300ms
2000ms
```

大部分都不慢。

但是偶尔出现：

```text
2000ms
```

如果只看平均值，有时看不清这种“长尾慢请求”。

---

## 18.1 p50

p50 可以近似理解为：

> 中位水平。

也就是：

```text
大约一半请求比它快
大约一半请求比它慢
```

它代表“日常体验”。

---

## 18.2 p95

p95 可以近似理解为：

> 95% 的请求都不会比这个值更慢。

它更容易暴露：

```text
偶发性卡顿
尾部延迟
```

所以：

```text
p50 = 普通时候快不快
p95 = 倒霉的时候会不会特别慢
```

---

# 19. Retry 是什么

有时候模型调用会失败，例如：

```text
网络错误
限流
上下文过长
输出异常
```

系统可能重新尝试。

这就是：

```text
retry
```

如果 Candidate Skill 加入之后：

```text
retry 明显增加
```

可能意味着：

```text
Skill 让 Agent 更不稳定
```

所以它也是 non-regression 的重要参考。

---

# 20. Compact 是什么

大模型上下文窗口不是无限大的。

假设：

```text
模型最大 Context = 128K
```

Agent 一直执行：

```text
用户消息
模型输出
工具结果
模型输出
工具结果
……
```

内容越来越多。

如果继续无限堆积，最终：

```text
Context 超限
```

因此系统可能把旧历史：

```text
压缩
总结
替换
```

这就是：

```text
compact
```

---

# 21. Context Pressure 是什么

这个指标特别适合理解成手机存储空间。

假设：

```text
上下文窗口 = 128000 tokens
当前估算使用 = 64000 tokens
```

那么：

```text
Context Pressure ≈ 50%
```

如果：

```text
Pressure = 95%
```

就像手机：

```text
128 GB
已经用了 121 GB
```

系统虽然还能运行，但已经非常接近边界。

容易出现：

```text
Context Overflow
Compact
Retry
模型丢失部分有效信息
```

所以你的 YAML 默认有：

```yaml
max_peak_context_pressure: 0.95
```

意思是：

> 峰值 Context Pressure 最好不要超过 95%。

---

# 22. YAML Quality Gate 到底是什么

文件：

```text
evals/agent-reliability.yaml
```

可以直接在 IDE 左侧项目树中双击打开。

你会看到类似：

```yaml
min_agent_runs: 1
max_failed_runs: 0
max_tool_errors: 0
max_retries: 3
max_compactions: 3
max_malformed_records: 0
min_tool_success_rate: 0.95
max_peak_context_pressure: 0.95

required_tools: []
forbidden_tools: []
```

把它想成：

> **Agent 的考试及格线。**

例如：

```text
Agent 失败次数必须为 0
Tool Error 必须为 0
Retry 不能太多
Tool 成功率至少 95%
Context Pressure 不能太高
```

---

# 23. 用鼠标制造一个“故意失败的门禁”

验证系统不能只测：

```text
正常情况 PASS
```

还必须故意制造：

```text
FAIL
```

否则你不知道 Gate 是真的在判断，还是无论如何都 PASS。

---

## 23.1 用资源管理器复制 YAML

进入：

```text
evals
```

复制：

```text
agent-reliability.yaml
```

粘贴后重命名：

```text
agent-reliability-fail-test.yaml
```

---

## 23.2 在 IDE 中修改

例如改成：

```yaml
min_agent_runs: 100
max_failed_runs: 0
max_tool_errors: 0
max_retries: 0
max_compactions: 0
max_malformed_records: 0
min_tool_success_rate: 1.0
max_peak_context_pressure: 0.001

required_tools:
  - ReadFile

forbidden_tools: []
```

注意：

> 这是故意写得特别严格，只用于测试门禁是否真的会拦截。

---

# 24. 执行 Trace Eval

在 IDE Terminal 中：

```text
java -jar build/libs/devmesh.jar --trace-eval .devmesh/traces --trace-policy evals/agent-reliability.yaml
```

正常规则应该看到：

```text
TRACE EVAL: PASS
```

然后换成：

```text
java -jar build/libs/devmesh.jar --trace-eval .devmesh/traces --trace-policy evals/agent-reliability-fail-test.yaml
```

应该看到：

```text
TRACE EVAL: FAIL
```

并且具体告诉你：

```text
哪一条规则 FAIL
```

---

# 25. 为什么 PASS / FAIL 对 CI 很重要

CI 不需要像人一样理解：

```text
为什么 p95 高
为什么 token 多
为什么 Tool Error 增加
```

CI 最擅长看：

```text
程序退出码
```

你的项目已经设计为：

```text
PASS
→ exit 0

FAIL
→ exit 2
```

所以 CI 可以非常简单：

```text
运行 Trace Eval
        ↓
exit 0
        ↓
允许继续

exit 2
        ↓
阻止构建 / PR
```

---

# 26. 目前项目的 CI 状态要说准确

你的项目现在：

```text
Trace Eval
+
YAML Gate
+
非零 Exit Code
```

已经具备。

所以它是：

> **CI-ready：已经具备接入 CI 的能力。**

但是当前仓库中没有看到完整的：

```text
.github/workflows/
```

自动执行配置。

因此现在不要直接说：

> “GitHub Actions 已经自动跑 Trace Eval。”

更准确的表述是：

> **支持基于 YAML 定义 Agent Trace 质量门禁，并通过非零退出码接入 CI 回归检查。**

等以后真正添加 GitHub Actions Workflow，再说：

> **在 CI 中自动执行 Trace Eval 质量门禁。**

---

# 27. 接下来进入最容易混淆的部分：Verified Skill Evolution

先举一个最简单的例子。

Agent 做了很多 Java 重构任务以后，可能总结：

```text
重构前先读实现和测试
先明确不允许改变的行为
一次只做一个变化
先跑局部测试
再跑完整测试
最后检查 diff
```

这是一套可复用“做事方法”。

于是 Agent 可以把它整理成：

```text
safe-java-refactor Skill
```

这就是：

> **把重复成功经验蒸馏成程序性知识。**

---

# 28. 为什么不能“Agent 总结完就自动生效”

因为 Agent 总结出来的 Skill 可能是错的。

例如 Agent 误总结：

```text
Java 重构后只要编译成功，就不需要跑测试。
```

如果系统直接自动加入正式 Skills：

```text
以后所有 Agent 都可能受到错误 Skill 影响
```

这叫：

```text
错误经验被长期固化
```

因此你的项目采用：

```text
Candidate
        ↓
QUARANTINED
        ↓
验证
        ↓
VERIFIED
        ↓
人工 Promote
```

---

# 29. QUARANTINED 可以理解成“隔离区”

就像杀毒软件。

新文件：

```text
不知道是否安全
        ↓
先进入隔离区
        ↓
扫描
        ↓
确认安全
        ↓
恢复 / 放行
```

Candidate Skill 一样。

刚提出时：

```text
QUARANTINED
```

意味着：

> **它存在，但默认不能作为正式 Skill 使用。**

---

# 30. Candidate 文件在哪里看

提出 Candidate 后，可以直接用资源管理器进入：

```text
.devmesh
→ evolution
→ candidates
```

每个 Candidate 一般有自己的文件夹：

```text
safe-java-refactor-v1-xxxxxxxx
```

里面会有：

```text
candidate.json
SKILL.md
```

---

# 31. candidate.json 是干什么的

它是 Candidate 的结构化描述。

里面会包含类似：

```text
id
name
version
description
whenToUse
tags
instructions
failureModes
validationChecks
sourceTrace
createdAt
contentHash
```

你可以理解为：

> **Candidate 的身份证 + 元数据。**

---

# 32. SKILL.md 是干什么的

这是 Candidate 真正准备以后提供给 Agent 使用的“操作说明书”。

例如：

```markdown
# safe-java-refactor

Read the affected implementation and focused tests before editing.
...
```

如果未来 Promote，这些内容会进入正式 Skill。

所以：

> **人工审核最应该认真看的文件之一，就是 SKILL.md。**

---

# 33. 人工审核到底在什么时候发生

这里是整套系统中最重要的一部分。

建议你把人工审核分成三个层次。

```text
Candidate 提出后
        ↓
【人工初审】
        ↓
是否值得进入正式 A/B

自动 A/B + Gate
        ↓
VERIFIED
        ↓
【人工最终审核】
        ↓
人工显式 Promote

正式运行
        ↓
【人工上线观察】
        ↓
有问题则 Rollback
```

其中真正控制“能不能成为正式 Skill”的关键人工审批点是：

> **VERIFIED → PROMOTED 之间。**

因为自动评测只能把 Candidate 变成：

```text
VERIFIED
```

它不能自动等价为：

```text
PROMOTED
```

---

# 34. 第一次人工审核：Candidate 刚提出后的“初审”

这一阶段 Candidate 状态：

```text
QUARANTINED
```

人工主要不是看 A/B 数据，因为 A/B 可能还没正式做。

人工主要回答：

```text
这个 Candidate 本身值不值得测？
```

---

## 34.1 人工打开 SKILL.md

鼠标进入：

```text
.devmesh/evolution/candidates/<candidate-id>/SKILL.md
```

重点读：

```text
description
when_to_use
instructions
Known Failure Modes
Validation Before Completion
```

---

## 34.2 初审时问自己几个非常实际的问题

### 问题 A：这个 Skill 是不是“真正可复用经验”

好的 Skill：

```text
做 Java 行为保持型重构时：
先读实现和测试
先明确不变量
修改后跑局部测试和全量测试
```

不太好的 Candidate：

```text
把 UserService.java 第 87 行改成 xxx
```

因为这只是：

```text
一次具体任务的答案
```

不是：

```text
以后还能复用的方法
```

---

### 问题 B：适用范围有没有写清楚

例如：

```text
Use for internal Java refactors with existing tests.
```

这是合理边界。

如果只写：

```text
Use for Java.
```

范围太大。

因为：

```text
重构
新增功能
修改 API
修 Bug
数据库迁移
```

完全不是同一种任务。

---

### 问题 C：有没有危险操作

例如 Candidate 写：

```text
测试失败时直接删除失败测试。
```

或者：

```text
遇到权限问题就关闭 Sandbox。
```

这种即使后续 A/B 某些指标很好，也不应该接受。

所以人工审核承担一个自动指标很难完全替代的任务：

> **检查 Skill 的语义安全性。**

---

### 问题 D：Known Failure Modes 是否合理

好的 Skill 不应该假装：

```text
我永远正确
```

而应该知道：

```text
我在哪些情况下可能失效
```

例如：

```text
单元测试通过不代表公共 API 一定兼容
```

这种就是好的 Failure Mode。

---

# 35. source_trace 为什么也要人工看

Candidate 中有：

```text
source_trace
```

意思是：

> 这套经验是从哪个任务 / 哪次 Trace 中总结出来的。

人工可以问：

```text
这条经验有真实来源吗？
还是大模型凭空编了一套？
```

这叫：

> **Candidate Provenance（候选来源溯源）**

需要注意：

`source_trace` 是“Candidate 为什么被提出”的来源。

而后面 Canary Trace 中的：

```text
devmesh.skill.candidate_id
```

是“Candidate 在 A/B 运行时是否真的被注入”的运行溯源。

二者作用不同。

---

# 36. 如何提出一个 Candidate

你的项目已经自带：

```text
examples/skill-evolution/safe-java-refactor.yaml
```

可以先在 IDE 中打开它。

内容就是：

```text
name
description
when_to_use
instructions
failure_modes
validation_checks
source_trace
```

建议第一次不要自己从零写。

先用这个示例学习。

---

# 37. Propose 目前属于 CLI，所以保留一条命令

在 IDE Terminal：

```text
java -jar build/libs/devmesh.jar --skill-evolution-propose examples/skill-evolution/safe-java-refactor.yaml --workspace .
```

运行后记下：

```text
candidate-id
```

例如：

```text
safe-java-refactor-v1-ab12cd34
```

---

# 38. 提出后全部用鼠标检查

进入：

```text
.devmesh/evolution/candidates/
```

应该出现：

```text
safe-java-refactor-v1-ab12cd34
```

打开：

```text
candidate.json
SKILL.md
```

然后再进入：

```text
.devmesh/skills/
```

此时不应该已经出现正式：

```text
safe-java-refactor/
```

这就证明：

```text
Candidate 已经存在
但还没有成为正式 Skill
```

也就是：

```text
QUARANTINED
```

真正生效。

---

# 39. 为什么 Content Hash 很重要

Candidate 会保存：

```text
contentHash
```

可以把它理解为 Candidate 内容的“指纹”。

如果 Candidate 建立以后，有人偷偷把：

```text
SKILL.md
```

或候选内容改掉，那么重新计算出来的 Hash 就可能对不上。

这可以帮助防止：

```text
验证的是 A
最后晋升的却偷偷变成了 B
```

这是一种：

> **完整性校验。**

---

# 40. 一个非常重要的负向测试：QUARANTINED 能不能直接 Promote

正确的安全系统应该：

```text
不允许
```

因为：

```text
Agent 提出 Candidate
≠
Candidate 已经证明可靠
```

所以在 Candidate 还是：

```text
QUARANTINED
```

时，可以故意执行一次 Promote。

项目的命令是：

```text
java -jar build/libs/devmesh.jar --skill-evolution-promote <candidate-id> --workspace .
```

正确结果应该类似：

```text
Candidate must be VERIFIED before promotion
```

这一步非常重要。

它验证的是：

> **Agent 自己不能通过“先生成 Candidate，再直接 Promote”绕过评测。**

---

# 41. A/B 测试到底在比较什么

接下来才真正开始验证 Candidate 是否有价值。

你需要两个实验组。

---

## Baseline

```text
普通 Agent
不注入 Candidate
```

---

## Candidate / Canary

```text
同一个 Agent
+
临时注入 Candidate
```

---

必须让二者做：

```text
同一批任务
```

这样才能回答：

> 加这个 Skill 到底变好了，还是变差了？

---

# 42. 为什么 Candidate 实验叫 Canary

Canary 来自“金丝雀发布”。

真实软件系统中通常不会：

```text
新版本直接一次给 100% 用户
```

而是：

```text
新版本
        ↓
少量流量
        ↓
观察
        ↓
正常后再全面上线
```

Candidate Skill 也是一样。

它在 Canary 阶段只是：

```text
临时注入
```

不会成为正式 Skill。

---

# 43. Candidate Provenance 为什么特别重要

假设你运行 Candidate 组。

最后发现：

```text
Candidate 组比 Baseline 好
```

但实际上那次 Agent 根本没有加载 Candidate。

那么这个结果根本不能证明：

```text
Candidate 有用
```

所以 Candidate Trace 中必须明确留下：

```text
这个 Candidate 确实参与了本次运行
```

项目通过：

```text
DEVMESH_CANARY_SKILL
```

临时注入 Candidate。

然后 Trace 中记录 Candidate ID。

Evaluator 再检查：

```text
candidate trace 中有没有正确 candidate-id
```

这就是：

> **Candidate Provenance Gate。**

---

# 44. Canary 运行在 Windows 中如何尽量图形化完成

环境变量本身可以不使用 PowerShell。

Windows 可以通过 IDE Run Configuration 设置。

---

## 44.1 IntelliJ IDEA

打开：

```text
Run
→ Edit Configurations
```

新建一个：

```text
Candidate Canary Test
```

Main class：

```text
devmesh.DevMesh
```

Program arguments：

```text
.devmesh/config.yaml -p "你的固定测试任务"
```

然后找到：

```text
Environment variables
```

点击右边编辑按钮。

添加：

```text
DEVMESH_CANARY_SKILL=<你的 candidate-id>
```

例如：

```text
DEVMESH_CANARY_SKILL=safe-java-refactor-v1-ab12cd34
```

保存后运行。

这样就不需要：

```powershell
$env:...
```

---

# 45. Baseline 与 Candidate Trace 一定要分开保存

这是实验是否可信的关键。

不要让：

```text
Baseline trace
Candidate trace
```

全部混在：

```text
.devmesh/traces
```

否则后面很难保证 A/B 真正对应。

项目支持：

```text
DEVMESH_TRACE_DIR
```

因此推荐再建立两个 Run Configuration。

---

## Baseline Run

环境变量：

```text
DEVMESH_TRACE_DIR=<项目目录>\eval-runs\baseline
```

不设置：

```text
DEVMESH_CANARY_SKILL
```

---

## Candidate Run

环境变量：

```text
DEVMESH_TRACE_DIR=<项目目录>\eval-runs\candidate
DEVMESH_CANARY_SKILL=<candidate-id>
```

这样运行后直接用资源管理器看到：

```text
eval-runs/
│
├── baseline/
│   └── ...
│
└── candidate/
    └── ...
```

这比临时在 PowerShell 中改环境变量更适合你。

---

# 46. Paired Outcome 到底是什么

Trace 评价的是：

```text
过程
```

但 Agent 最终有没有完成任务，还需要：

```text
Outcome
```

例如同一个任务：

```text
case-01：重构 UserService，但行为不能变化
```

Baseline 的结果：

```json
{"case_id":"case-01","passed":true,"score":0.80}
```

Candidate：

```json
{"case_id":"case-01","passed":true,"score":0.92}
```

这里最重要的不是 score 本身。

而是：

```text
两边 case_id 必须一样
```

因为只有：

```text
同一道题
```

才可以公平比较。

---

# 47. 为什么一定叫“Paired”

错误实验：

```text
Baseline 做 case-01、case-02
Candidate 做 case-03、case-04
```

然后比较：

```text
谁的成功率高
```

没有意义。

因为：

```text
题目难度都不同
```

正确实验：

```text
Baseline：
case-01
case-02

Candidate：
case-01
case-02
```

每个 case 一一配对。

所以叫：

> **Paired Outcome。**

---

# 48. Outcome 与 Trace 一定要区分

这是理解与讲解时非常容易混淆的地方。

```text
Trace
=
过程质量
```

回答：

```text
Tool 有没有失败？
耗时高不高？
token 多不多？
有没有 retry？
Context 有没有快爆？
```

而：

```text
Outcome
=
结果质量
```

回答：

```text
任务到底完成了吗？
测试到底通过了吗？
结果得分是多少？
```

例如：

```text
Agent 只用了 100 token
没有 Tool Error
延迟 50ms
```

但是：

```text
把代码改错了
```

那么：

```text
Trace 很漂亮
Outcome 很差
```

所以 Verified Skill Evolution 必须两者都看。

---

# 49. Trace Gate 又在 Skill Evolution 中起什么作用

Candidate 组本身首先还要满足：

```text
evals/skill-evolution.yaml
```

中的可靠性要求。

例如：

```yaml
max_failed_runs: 0
max_tool_errors: 0
max_retries: 2
min_tool_success_rate: 0.95
max_peak_context_pressure: 0.95
```

意思是：

> Candidate 不能为了“结果变好”，把 Agent 的运行过程搞得非常不稳定。

---

# 50. Non-regression 又是什么

Non-regression：

> **Candidate 至少不能明显把系统搞差。**

项目会比较：

```text
agent_failure_rate
tool_error_rate
tokens_per_run
model_p95_latency
```

例如：

```text
Baseline:
失败率        0%
Tool Error    0%
Tokens        10000
p95           2 秒

Candidate:
失败率        0%
Tool Error    0%
Tokens         8500
p95           1.7 秒
```

这是比较理想的 Candidate。

但是：

```text
Candidate:
失败率        20%
Tool Error    10%
Tokens        50000
p95           9 秒
```

即使某几个结果碰巧成功，也不应该晋升。

---

# 51. `skill-evolution.yaml` 中几个重要规则怎么理解

打开：

```text
evals/skill-evolution.yaml
```

会看到：

```yaml
evolution:
  max_failure_rate_regression: 0.0
  max_tool_error_rate_regression: 0.0
  max_token_ratio: 1.20
  max_model_p95_latency_ratio: 1.50
  require_strict_improvement: true
  min_paired_outcome_cases: 2
  max_outcome_failure_rate_regression: 0.0
  max_outcome_score_regression: 0.0
```

---

## max_token_ratio: 1.20

意思是 Candidate 的 token 开销最多允许：

```text
Baseline × 1.2
```

也就是大约最多多 20%。

---

## max_model_p95_latency_ratio: 1.50

Candidate 的模型 p95 延迟不能超过：

```text
Baseline × 1.5
```

---

## require_strict_improvement: true

仅仅：

```text
“不比 Baseline 差”
```

还不够。

至少要在主要指标中出现真正改善。

这是为了避免：

```text
引入了一个复杂 Skill
但实际上完全没收益
```

---

## min_paired_outcome_cases: 2

至少要有两组配对 Outcome。

项目示例为了本地演示要求比较低。

如果以后要做真正严格的生产验证，应该提高数量。

---

# 52. 自动 Evaluate 需要什么材料

完整 Skill Evolution Evaluate 会读取：

```text
Candidate ID

Baseline traces

Candidate traces

Baseline outcomes

Candidate outcomes

evolution policy
```

然后自动进行：

```text
Baseline Evidence
Candidate Provenance
Trace Gate
Paired Outcome Gate
Non-regression
```

---

# 53. Evaluate 命令

这个操作目前也属于项目 CLI，因此保留终端。

格式：

```text
java -jar build/libs/devmesh.jar ^
  --skill-evolution-evaluate <candidate-id> ^
  --baseline-traces <baseline traces> ^
  --candidate-traces <candidate traces> ^
  --baseline-outcomes <baseline outcomes> ^
  --candidate-outcomes <candidate outcomes> ^
  --evolution-policy evals/skill-evolution.yaml ^
  --workspace .
```

如果你在 IDE Terminal 里单行写，也可以全部写在一行。

通过后应该看到类似：

```text
SKILL EVOLUTION EVAL: PASS

baseline evidence: PASS
candidate provenance: PASS
trace gate: PASS
paired outcome gate: PASS
non-regression: PASS
```

然后 Candidate 状态进入：

```text
VERIFIED
```

---

# 54. 自动验证 PASS 后，为什么还不能马上 Promote

这就是“人工审核”的真正价值。

自动程序擅长检查：

```text
数字
规则
配对
哈希
Candidate ID
Tool Error
Token
Latency
```

但是它不一定能够可靠回答：

```text
这条 Skill 的指导思想是不是合理？
有没有隐藏的危险行为？
适用范围是不是太大？
会不会诱导 Agent 绕过权限？
虽然这批 Benchmark 好，但是否存在明显业务风险？
```

所以：

```text
VERIFIED
```

只代表：

> **机器证据满足当前规则。**

不是：

> **已经获得最终上线许可。**

---

# 55. 第二次人工审核：VERIFIED → PROMOTED 前的最终审核

这是整套系统中真正的“人工审批门”。

建议你此时不要只看一句：

```text
PASS
```

而是依次看下面这些材料。

---

## 55.1 看 Candidate 的 SKILL.md

位置：

```text
.devmesh/evolution/candidates/<candidate-id>/SKILL.md
```

重新读：

```text
适用范围
操作步骤
Failure Modes
Validation Checks
```

问自己：

```text
我真的愿意以后让 Agent 根据这份说明做事吗？
```

如果答案是不确定，就不要 Promote。

---

## 55.2 看 candidate.json

确认：

```text
Candidate ID
Version
Source Trace
Content Hash
```

尤其确认：

```text
现在准备 Promote 的 Candidate
就是刚刚 A/B 验证过的那个 Candidate
```

---

## 55.3 看 Baseline 与 Candidate Trace Report

分别跑：

```text
--trace-report baseline
--trace-report candidate
```

不要只看最终 PASS。

人工最好观察：

```text
Token 为什么下降 / 上升？
p95 为什么变化？
Tool 调用数量是否异常？
有没有 Candidate 为了减少 token 而少做了必要验证？
```

例如：

```text
Candidate token 下降 50%
```

看起来很好。

但如果原因是：

```text
它把“运行完整测试”这一步偷偷省略了
```

那反而是坏事。

这就是为什么：

> **自动指标需要人工解释。**

---

## 55.4 看 Outcome

打开：

```text
baseline outcomes
candidate outcomes
```

一一看：

```text
case_id
passed
score
```

重点确认：

```text
是不是同一批 Case
Candidate 是否真的没有某些边缘 Case 退化
```

---

## 55.5 看 YAML Policy

打开：

```text
evals/skill-evolution.yaml
```

人工要确认：

> **当前门槛本身是不是合理。**

因为如果有人把：

```yaml
max_token_ratio: 100
```

那 Candidate 几乎随便都能通过。

所以：

```text
评测程序正确
```

不代表：

```text
评测标准一定合理
```

Policy 本身也需要人负责。

---

# 56. 最终人工审核可以做一个简单“审批结论”

你不一定需要开发复杂审批系统。

最简单的工程实践可以是：

```text
Reviewer: 张三
Candidate: safe-java-refactor-v1-ab12cd34
Review Date: 2026-08-09

Candidate content: PASS
Source / Provenance: PASS
Trace Gate: PASS
Paired Outcome: PASS
Non-regression: PASS
Safety / Permission risk: PASS
Scope is reasonable: PASS

Decision:
APPROVE FOR PROMOTION
```

可以把它保存在：

```text
docs/reviews/
```

例如：

```text
docs/reviews/safe-java-refactor-v1-ab12cd34-review.md
```

这一步目前不是你代码强制要求的字段。

但作为真实项目实践，非常有价值。

---

# 57. 为什么 Promote 必须“显式执行”

系统不是：

```text
Evaluate PASS
        ↓
自动 Promote
```

而是：

```text
Evaluate PASS
        ↓
VERIFIED
        ↓
等待一个明确 Promote 操作
```

这就形成了：

> **Human-in-the-loop。**

实际负责人确认后才执行：

```text
java -jar build/libs/devmesh.jar --skill-evolution-promote <candidate-id> --workspace .
```

这条命令本身就是：

> “我明确批准这个 VERIFIED Candidate 成为正式 Skill。”

---

# 58. Promote 后怎么用鼠标验证

执行 Promote 后，打开资源管理器：

```text
.devmesh/skills/
```

应该出现：

```text
safe-java-refactor/
```

里面有：

```text
SKILL.md
```

说明 Candidate 已经真正进入正式 Skills。

再看：

```text
.devmesh/evolution/events.jsonl
```

应该新增：

```text
PROMOTED
```

---

# 59. 为什么 Promote 还会备份旧版本

假设正式系统原来已经有：

```text
safe-java-refactor v1
```

现在准备上：

```text
v2
```

如果直接：

```text
删除 v1
复制 v2
```

那 v2 出问题后：

```text
原来的 v1 已经找不到了
```

所以你的 Store 会把原正式 Skill 备份到：

```text
.devmesh/evolution/releases/
```

可以理解为：

```text
上线前做快照
```

---

# 60. Event Log 是干什么的

打开：

```text
.devmesh/evolution/events.jsonl
```

你可能看到：

```text
PROPOSED
EVALUATION_PASSED
PROMOTED
ROLLED_BACK
```

它没有只保存：

```text
status = ROLLED_BACK
```

而是保存整个历史。

这叫：

> **Append-only Event Log。**

---

# 61. 为什么叫 Event Replay

假设系统现在只看 events：

```text
PROPOSED
        ↓
QUARANTINED

EVALUATION_PASSED
        ↓
VERIFIED

PROMOTED
        ↓
PROMOTED

ROLLED_BACK
        ↓
ROLLED_BACK
```

从第一条事件重新播放到最后一条：

```text
就能恢复当前状态
```

这就是：

> **Event Replay / Event Sourcing 思想。**

你的项目现在主要是：

```text
通过事件历史重建 Candidate 生命周期状态
```

它不是一个通用企业级 Event Sourcing 平台。

简历中应该说：

> **通过追加式事件日志记录 Skill 生命周期，并支持基于事件历史重建 Candidate 状态。**

会更准确。

---

# 62. 第三次人工审核：Promote 后的上线观察

人工审核不是 Promote 完就彻底结束。

真实系统中最好还有：

```text
上线观察期
```

例如：

```text
正式 Skill 上线后
        ↓
跑新的任务
        ↓
继续收集 Flight Recorder Trace
        ↓
继续做 Trace Eval
        ↓
观察是否出现生产环境退化
```

因为 Benchmark 不可能覆盖所有真实情况。

---

# 63. 什么情况下应该 Rollback

例如 Promote 后发现：

```text
Tool Error 明显上升
```

或者：

```text
Token 突然暴涨
```

或者：

```text
Agent 在某类任务中开始做危险操作
```

或者：

```text
真实业务 Outcome 下降
```

这时人工应该：

```text
停止继续使用新 Skill
        ↓
执行 Rollback
```

---

# 64. Rollback 命令

项目提供：

```text
java -jar build/libs/devmesh.jar --skill-evolution-rollback <candidate-id> --workspace .
```

执行后：

```text
Candidate 状态
→ ROLLED_BACK
```

如果原来有旧正式 Skill 备份，则恢复旧版本。

---

# 65. 为什么叫“Recoverable Rollback”

不是简单：

```text
把新 Skill 删除掉
```

而是：

```text
Promote 前保留旧状态
        ↓
新版本出错
        ↓
恢复旧状态
```

因此系统具有：

> **可恢复性。**

甚至如果 Promote 过程中：

```text
文件已经换了
但是事件写入失败
```

代码还会尝试进行补偿恢复。

这是一种工程上的：

```text
失败补偿
```

思想。

---

# 66. 最推荐你的整套“鼠标优先验收路线”

你真正自己动手时，可以按下面顺序。

```text
① IDE 打开项目
        ↓
② GUI Run Configuration 跑一个“读取 README”的 Agent
        ↓
③ 资源管理器打开 .devmesh/traces
        ↓
④ IDE 中搜索 invoke_agent / chat / execute_tool
        ↓
⑤ 用特殊字符串验证 Prompt 没有进 Trace
        ↓
⑥ 搜 argument_keys，验证只记录参数名
        ↓
⑦ IDE Terminal 跑 --trace-report
        ↓
⑧ 鼠标复制 YAML，制造严格 Gate
        ↓
⑨ IDE Terminal 跑 Trace Eval：
      正常 YAML → PASS
      严格 YAML → FAIL
        ↓
⑩ 打开 examples/skill-evolution/safe-java-refactor.yaml
        ↓
⑪ CLI Propose Candidate
        ↓
⑫ 资源管理器查看：
      candidate.json
      SKILL.md
      状态仍是 quarantine
        ↓
⑬ 【人工初审】
      Candidate 内容是否合理
        ↓
⑭ 故意直接 Promote
      应该被拒绝
        ↓
⑮ 建立 Baseline Run Configuration
        ↓
⑯ 建立 Candidate Canary Run Configuration
        ↓
⑰ 两边跑相同任务
        ↓
⑱ 准备 paired outcomes
        ↓
⑲ CLI Evaluate
        ↓
⑳ 所有自动 Gate PASS
        ↓
Candidate → VERIFIED
        ↓
㉑ 【人工最终审核】
        ↓
㉒ 人工显式 Promote
        ↓
㉓ 资源管理器查看正式 .devmesh/skills
        ↓
㉔ 查看 events.jsonl
        ↓
㉕ 人工观察上线表现
        ↓
㉖ 故意执行 Rollback
        ↓
㉗ 查看 Skill 与事件是否恢复正确
```

如果这一整条你都亲自跑过，你就不是：

```text
“我项目里好像有这个功能”
```

而是真正能够解释：

```text
功能在哪里
为什么这样设计
怎样证明它有效
异常时如何拦截
人在哪一步做决策
```

---

# 67. 最终验收表

| 验证项目 | 你应该亲眼看到什么 | 说明 |
|---|---|---|
| Flight Recorder 创建 Trace | `.devmesh/traces/*.jsonl` | 记录链路真实运行 |
| `invoke_agent` | Trace 中存在 | Agent 根 Span |
| `chat` | Trace 中存在 | 模型调用被记录 |
| `execute_tool` | Trace 中存在 | Tool 调用被记录 |
| Prompt 安全 | 特殊 Prompt 字符串搜不到 | 默认不采集正文 |
| Tool Argument 安全 | 能见 `argument_keys`，不见参数 value | 只采集参数名 |
| Trace Report | token、cache、latency 等 | 离线统计成立 |
| Trace Eval PASS | 正常 YAML 通过 | 正向验证 |
| Trace Eval FAIL | 故意严格 YAML 拦截 | 负向验证 |
| Exit Code | FAIL 返回非 0 | 可接入 CI |
| Candidate Proposal | candidates 下出现目录 | Candidate 创建 |
| Quarantine | 正式 skills 中还没有 | 未自动激活 |
| 越权 Promote | 被拒绝 | VERIFIED 前不能上线 |
| Canary Provenance | Candidate Trace 有 candidate-id | 确认真正加载 Candidate |
| Paired Outcome | 两边 case_id 一致 | 同题比较 |
| Trace Gate | PASS | Candidate 运行质量合格 |
| Non-regression | PASS | 不允许明显退化 |
| VERIFIED | Evaluate 后状态变化 | 自动证据通过 |
| 人工最终审核 | 人查看 Skill、Trace、Outcome、Policy | Human-in-the-loop |
| Promote | `.devmesh/skills/<name>` 出现 | 正式激活 |
| Event Log | `PROMOTED` 等事件存在 | 生命周期可审计 |
| Rollback | 变为 `ROLLED_BACK` | 回退链路有效 |
| Release Backup | `evolution/releases` 中存在备份 | 可恢复回滚 |

---

# 68. 人工审核和自动审核，最简单的区别

你可以把它记成：

```text
自动审核问：
“数据上有没有通过规则？”

人工审核问：
“即使数据通过，我真的愿意让它长期影响 Agent 吗？”
```

自动系统擅长：

```text
统计
比较
哈希
配对
规则
阈值
```

人更应该负责：

```text
语义合理性
安全边界
适用范围
业务风险
最终责任
```

所以最健康的设计不是：

```text
全部人工
```

也不是：

```text
全部自动
```

而是：

```text
Agent 提案
        ↓
机器收集证据
        ↓
机器做确定性 Gate
        ↓
人工做最终授权
```

---

# 69. 如何解释整个设计

可以这样说：

> 我们没有让 Agent 生成 Skill 后直接写入正式技能目录，而是把新经验先作为 Candidate 存入 quarantine。Candidate 会记录来源 Trace 和内容 Hash，并通过 Canary 方式只在指定实验运行中临时注入。随后使用 Baseline 与 Candidate 的同任务配对结果进行比较，同时检查 Candidate 溯源、Trace 可靠性门禁、Outcome 以及 token、失败率、Tool Error 和 p95 延迟等 non-regression 指标。只有自动验证全部通过后 Candidate 才进入 VERIFIED，但仍不会自动生效；最终需要人工查看 Skill 内容、A/B 证据和风险后显式执行 Promote。生命周期通过 append-only events 记录，正式版本上线前保留旧 Skill 备份，因此出现退化时可以 Rollback。

---

# 70. 三个模块最终一句话记忆

## Agent Flight Recorder

> 给 Agent 装一个“黑匣子”，默认不保存敏感正文，只记录模型、Tool、token、延迟、错误和 Context 状态等运行元数据。

## Trace Eval

> Agent 跑完后不用再调用模型，直接离线分析黑匣子日志，通过 YAML 规则判断成本、性能和可靠性是否达标。

## Verified Skill Evolution

> Agent 可以提出新的 Skill 经验，但先隔离；只有来源可信、同任务 A/B、Outcome、Trace Gate 和 non-regression 都通过，并经过人工最终审核后，才显式 Promote，出问题还能根据历史版本和事件记录 Rollback。

---

# 71. 当前项目状态需要准确表述

结合当前代码，可以表述为：

```text
Agent Flight Recorder              已实现
invoke_agent / chat / execute_tool 已实现
默认不采集 Prompt 正文             已实现
Tool 参数仅记录 key                 已实现
Trace Eval 离线统计                 已实现
YAML Quality Gate                  已实现
Gate 非零退出码                     已实现
Verified Skill Evolution 核心闭环   已实现
Quarantine                         已实现
Candidate Provenance               已实现
Paired Outcome                     已实现
A/B Non-regression                 已实现
显式 Promote                        已实现
事件式生命周期记录                  已实现
Rollback / 恢复机制                 已实现

GitHub Actions 自动 CI              当前尚未真正接入
完整 OpenTelemetry SDK/OTLP 链路     当前不是本项目的实现方式
```

关于 OpenTelemetry 最准确的理解是：

```text
本地 JSONL Flight Recorder
+
采用 OpenTelemetry GenAI 风格的 gen_ai.* 语义字段
```

而不是：

```text
OpenTelemetry SDK
→ OTLP
→ Collector
→ Jaeger / Grafana
```

这两个层次不要混在一起。

---

# 72. 你真正学会这一部分的判断标准

如果以后别人问你：

> Agent Flight Recorder 是什么？

你不应该只回答：

```text
是记录日志的。
```

而应该能继续解释：

```text
记录什么
为什么不记录 Prompt
Trace 和 Span 是什么
为什么能离线 Eval
p50/p95 有什么意义
为什么要 Context Pressure
为什么 YAML 可以做 CI Gate
为什么 Candidate 不能自动 Promote
为什么需要 paired outcome
为什么需要 provenance
为什么自动 PASS 后还要人工审核
为什么 Event Log 能支持 Replay
为什么 Promote 前要备份
Rollback 恢复的到底是什么
```

当这一整条逻辑你能连贯说出来时，你就真正理解了这项设计，而不是只记住了几个名词。
