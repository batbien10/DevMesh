# DevMesh 工程验证基准

本报告由 `./gradlew engineeringValidation` 离线生成，使用真实 Tool Schema、真实 `ToolSearch` 注册状态和真实 `ContextCompactor.manage`，不调用外部模型。

## 1. Tool Schema 按需发现

Schema token 是**估算值**：先按工具名排序并序列化为 canonical compact JSON，再计算 `ceil(JSON 字符数 / 4)`。这与 Agent trace 使用同一实现，不声称等同于任一模型的私有 tokenizer。

Baseline 是交互式模式的 21 个内置工具全部提前发现、每轮全量注入。固定发现输入为 `ToolSearch(select:AskUserQuestion,EnterWorktree,ExitWorktree,ProposeSkillCandidate,TaskCreate,TaskUpdate)`。

| 协议 | 场景 | Schema 数 | JSON 字符 | 估算 token | 较全量下降 |
| --- | --- | ---: | ---: | ---: | ---: |
| anthropic | 全量 baseline | 21 | 17152 | 4288 | — |
| anthropic | 冷启动常驻 | 13 | 11902 | 2976 | 30.6% |
| anthropic | 发现 6 个延期工具后 | 19 | 16755 | 4189 | 2.31% |
| openai-compat | 全量 baseline | 21 | 17488 | 4372 | — |
| openai-compat | 冷启动常驻 | 13 | 12110 | 3028 | 30.74% |
| openai-compat | 发现 6 个延期工具后 | 19 | 17059 | 4265 | 2.45% |

## 2. 50 轮上下文压缩

输入由 `benchmarks/fixtures/context-50-turns.yaml` 固定：50 轮，每轮 700 字符用户消息和 1,200 字符助手消息；每 5 轮增加一次 `ReadFile` tool_use、5,000 字符 tool_result 和 600 字符跟进消息，共 120 条消息、10 组工具调用。窗口为 32768 token，最大输出预留为 4096 token。

Baseline 对同一输入完全关闭 `ContextCompactor.manage`；处理组在每轮模型请求前调用真实 `manage`。为排除外部 API 波动，摘要客户端返回固定文本，因此本基准验证的是触发、重建与工具配对完整性，不评估摘要语义质量。统计口径为每轮压缩管理结束后的 `消息估算 token + 固定常驻 Schema 估算 token`。

| 指标 | 不压缩 baseline | 自动压缩 | 下降 |
| --- | ---: | ---: | ---: |
| 50 轮平均估算上下文 | 24966.88 | 11082.82 | 55.61% |
| 50 轮峰值估算上下文 | 47367 | 18529 | 60.88% |
| 第 50 轮结束值 | 47367 | 5664 | — |

自动压缩触发 3 次；每轮检查 tool_use/tool_result 配对，共 50 次，失败 0 次。

原始逐轮序列和输入 SHA-256 见 `benchmarks/results/engineering-validation.json`。这些数字只对应上述固定输入和估算器，不能外推为所有模型、任务或上下文窗口的普遍收益。
