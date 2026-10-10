# Learn Harness Engineering：方法、示例边界与 Noval 映射

> 文档 ID：`learn-harness-engineering-analysis`
>
> 状态：`current`（指定课程快照的研究报告）
>
> 发布边界：`repository`
>
> 研究开始：`2026-09-07`；最后审核：`2026-09-08`
>
> 负责人：`agent-runtime`

> 本文的 Noval 映射记录实施前的对照与建议；2026-09-08 已完成的本地增量见 [工具实时进度与缓存保护实施验收](2026-09-08-noval-agent-harness-implementation-acceptance.md)。课程源码范围与局限仍按本报告固定版本理解。

## 1. 这个仓库值得学什么

它适合作为一套设计和评估 harness 的课程，帮助把“让模型多想一会儿”转成可检查的任务边界、外部状态、验证路径和反馈循环。课程示例不是可以直接替换 Noval Worker 的生产运行时。

需要区分两个应用层次：

1. **开发 Noval 时使用的 coding-agent harness**：仓库说明、任务计划、验收脚本、工作区隔离和交接记录。
2. **Noval 产品内的问答 harness**：用户／项目权限、检索证据、工具调用、预算、上下文、Run 恢复和回答校验。

第一层的“把知识写入仓库”，迁移到第二层通常意味着“让 agent 通过受控接口访问带版本和来源的业务事实”，并不意味着把每个用户的问题和小说内容写入 Markdown。

## 2. 固定来源与阅读范围

- 仓库：[`walkinglabs/learn-harness-engineering`](https://github.com/walkinglabs/learn-harness-engineering/tree/77e7a3e21469dcbece2558086c8d91657abeaa40)。
- 本次固定提交：`77e7a3e21469dcbece2558086c8d91657abeaa40`，提交时间 `2026-08-26T01:08:58Z`。
- 通过 GitHub API 确认提交，再下载对应 codeload ZIP；归档 SHA-256：`7a722b56dd9d580942e1867fb76860be69fd57e7b334c4259bc41a6cbe34cd5a`。
- [LICENSE](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/LICENSE#L1) 是 MIT，署名 WalkingLab。本文为独立分析，不批量复制课程、完整提示词或用户材料。
- 核对了 14 讲的目录／开篇，以及 8 个项目的结构；重点深读五子系统、最小 loop、feature validator、Codex 设计章、第 14 讲图示例、Project 05 问答实现、Project 06 日志与 benchmark。
- `projects/` 下实际有 Project 01–06 的代码工程；Project 07–08 主要通过课程文档、模板和图示例组织练习。不要把“8 个项目”理解成 8 套可直接运行的生产 agent。

Codex 运行时的实际源码核验另见 [Codex Harness 与 SDK 拆解](2026-09-07-codex-harness-sdk-source-analysis.md)。该报告使用固定 Codex 提交，不能被课程中的二手概括替代。

## 3. 五个子系统如何变成 Noval 的工程问题

课程第 2 讲提出 Instructions、Tools、Environment、State、Feedback 五个子系统。[L02]

| 子系统 | 课程关注 | 在 Noval 问答中对应什么 | 应检查的不变式 |
| --- | --- | --- | --- |
| 指令 | 任务说明、AGENTS.md、分层文档 | 固定系统约束、当前用户目标、阶段 Skill | RAG／记忆／Skill 正文不能覆盖授权和系统边界 |
| 工具 | 清晰工具接口与执行权限 | 书籍／章节／榜单／项目知识检索及采集 | 只有通过权限、预算与 schema 校验的调用才可执行 |
| 环境 | 可复现的依赖、初始化、工作区 | Provider 能力、知识索引版本、运行资源、租户环境 | 当前模型与工具使用同一版本的受信运行配置 |
| 状态 | 外部进度、feature list、handoff | conversation、Run/Event、checkpoint、EvidenceCommit、TaskProgressCheckpoint | 恢复不能跨用户／项目，不能复活失效证据或重复未知写操作 |
| 反馈 | 测试、evaluator、可观察结果 | 来源验证、覆盖度、引用检查、停止原因和评测 | completed 必须有可检查的依据，不能只接受模型自报成功 |

对 Noval，反馈的优先改进不是默认再加一个 LLM reviewer，而是让检索缺项、引用无效、结果未知、预算终止等事实能驱动确定的路由和清晰的用户反馈。现有修复与校验代码已经覆盖其中一部分，应通过测试和效果实验识别剩余问题。

## 4. 14 讲的学习导航

本表是按章节目录和概述整理的阅读导航；源码级结论集中在下一节实际检查过的示例。

| 讲次 | 主题 | 向 Noval 转移的具体问题 |
| --- | --- | --- |
| 01 | 强模型仍会执行失败 | 把失败归因到目标、证据、工具、状态或验证，不只归因到模型 |
| 02 | 五子系统 | 先画出现有 harness 的职责，避免遗漏执行控制和反馈 |
| 03 | 可访问的事实来源 | 用受控检索提供事实来源、版本与引用，不依赖隐含背景 |
| 04 | 分层指令 | 固定约束常驻；阶段知识按需进入；减少无关 Skill 正文 |
| 05 | 跨会话连续性 | 会话历史之外保存当前任务状态，明确何时恢复、何时失效 |
| 06 | 初始化独立阶段 | 在执行前冻结模型、能力、授权与预算，快速发现不可执行条件 |
| 07 | 范围控制 | 修复只能调整原计划允许的查询，不能自行扩大作品／章节范围 |
| 08 | 结构化完成清单 | 将“分析三本书”转成三个有证据要求的任务项 |
| 09 | 阻止过早宣布完成 | 验证最终答案和引用，不把已有文字当作成功终态 |
| 10 | 全链路验收 | 校验检索 → 证据封存 → 生成 → 校验 → 事件展示和重放 |
| 11 | 运行反馈 | 记录阶段耗时、调用结果、命中／未命中和失败原因，避免记录正文 |
| 12 | 可恢复的交接 | 明确已完成、待完成、未知结果、预算余额和证据版本 |
| 13 | 有界循环 | 每次继续必须有进展或新的必要信息，并有次数／成本出口 |
| 14 | 显式图结构 | 把确定节点、模型节点、修复边和停止条件画出来，复用已有 LangGraph |

其中第 3 讲把 agent 信息来源高度简化为仓库。现代 agent 也可以通过 MCP／连接器访问外部系统；对 Noval 应保留的是“信息必须可授权访问、可溯源和可验证”，不是“信息必须位于 Git”。

## 5. 从实际示例里区分方法与保证

### 5.1 最小 loop 是教学占位

[minimal-harness-loop.ts](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/lectures/lecture-02-what-a-harness-actually-is/code/minimal-harness-loop.ts#L1) 预设一次 `read_file`，生成模拟结果，再返回新 messages。

它展示了“动作 → 观察 → 上下文”的形状，但没有真正的模型决策循环、持久化、授权、取消和预算控制。不能因为文件名叫 loop，就把它当成 Codex 主循环的等价实现。

Noval 已有真实 AgentKernel。该示例的价值是解释最小概念，生产改进应在现有 kernel 上进行。

### 5.2 Feature validator 检查的是声明，不是证据真实性

[feature-list-validator.ts](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/lectures/lecture-08-why-feature-lists-are-harness-primitives/code/feature-list-validator.ts#L1) 能检查字段形状，并标记 `passes=true` 但 verification 数组为空的条目。

verification 中写了“运行测试”并不证明测试真的运行、针对的是当前版本，也不证明结果通过。向 Noval 转移时，任务完成应引用当前有效的 EvidenceCommit、来源身份和验证结果；不能只检查有一个非空说明字符串。

### 5.3 Project 05 的问答实现是 mock

实际检查的 [plan-gen-eval/qa-service.ts](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/projects/project-05/solution/plan-gen-eval/src/services/qa-service.ts#L1) 使用：

- 问题关键词与 chunk 文本重叠计分。
- 前两个候选作为引用。
- `MOCK_PATTERNS` 生成预设回答。
- 按是否有引用设置固定 confidence。
- 问题和回答写入本地历史，并在处理日志中记录问题。

它不是模型回答质量、语义检索效果或置信度校准的实现证据。课程 [Project 05 说明](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/projects/project-05-grounded-qa-verification/index.md#L1) 中的不同角色评分，也不能作为 Noval 增加 planner／reviewer 后必然提升的依据。

可采用的是“生成者不能独占完成判定权”。Noval 应先用确定性来源／引用／覆盖校验，必要时再引入有独立证据输入的模型评审；固定 confidence 和完整问题日志不应照搬。

### 5.4 图示例的恢复和终止条件需要补全

[maker_checker_graph.py](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/lectures/lecture-14-graph-engineering/code/maker_checker_graph.py#L1) 有清晰的 StateGraph、节点和条件边，很适合说明图结构。但源码也明确暴露了边界：

| 代码现状 | 不能声称的保证 | Noval 应保留的机制 |
| --- | --- | --- |
| `call_model` 抛出 NotImplementedError | 示例已经接好真实 Provider | 现有 ProviderClient、能力契约和模型预算 |
| `tests_pass` 只查找 `def test` 字符串 | 确定性测试真的通过了 | 真实规则／测试产物及版本绑定 |
| reviewer 文本含 `approved` 被视为通过 | 文本关键词代表独立有效审查 | 类型化验证结果和保留 `unknown` 的路由 |
| 使用 `MemorySaver` | 进程退出后自动恢复 | 现有持久 checkpoint／Run Event 和恢复校验 |
| attempts 声明了累加 reducer，但节点没有更新它，路由也没有次数门 | 循环有业务级重试上限 | Run budget、一次修复槽、明确停止原因 |
| 路由仅判断 `fail`，其他值走 merge | 所有未知状态都安全处理了 | 对 unknown、取消、失败、待补充信息分别路由 |

上述判断来自静态源码阅读；本次没有执行该模板，也没有把课程注释中的持久化描述当成运行验收。

### 5.5 可观察性要连接真实链路

[Project 06 logger](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/projects/project-06/solution/src/services/logger.ts#L1) 提供统一日志结构，但对 data 没有 Noval 的权限与脱敏约束。[benchmark.sh](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/projects/project-06/solution/scripts/benchmark.sh#L1) 主要模拟文件复制、chunk 估算和关键词查询，不能代替真实检索、模型调用和事件恢复的验收。

Noval 应复用现有 Trace 与安全摘要，只记录已经授权的诊断字段，如 stage、duration、token、source count、版本指纹、stop reason。完整问题、章节正文和工具原始结果不应成为公共日志默认内容。

## 6. 课程 Codex 章节需要怎样校正

[课程 Codex 设计章](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/harness-designs/codex/index.md#L1) 混合了工程实践文章、社区上下文方法和 CLI 功能说明。阅读时应区分工程建议、产品运行模式和源码保证。

| 章节概括 | 本次源码对照 |
| --- | --- |
| AGENTS.md 像目录，知识分层管理 | 可作为文档设计建议；固定行数不是通用正确性门槛 |
| 每个任务有 worktree 隔离 | 适用于特定开发工作流；不能推导 SDK 每次 turn 都自动创建工作树，更不能代替安全沙箱 |
| 状态依靠文件／约定 | Codex runtime 另有 ContextManager、RolloutRecorder、压缩 checkpoint 与 replay |
| 使用独立子 agent 上下文 | 不自动意味着独立文件系统、权限或数据租户 |
| Write / Select / Compress / Isolate | 是有帮助的组织框架；具体上下文结构、压缩位置和工具接口需看对应源码版本 |
| Codex 是一个 monolithic binary | 二进制打包形态不等于源码没有模块边界；当前 Rust 工程已拆出 rollout、skills、协议等 crate |

同样不能把“验证命令写进说明文件”解释成“harness 保证已经执行验证”。Noval 的完成状态应依赖真实执行事件和结果校验。

## 7. 八个项目如何转成 Noval 的练习

| 项目 | 课程实验 | Noval 中可复用的实验方式 |
| --- | --- | --- |
| P01 基线与最小 harness | 控制变量比较 | 同模型、同题目、同工具 fixture、同总预算比较能力开关 |
| P02 Agent-readable workspace | 建立可导航知识 | 比较全量 Skill 正文与现有阶段激活的 token、质量和遗漏率 |
| P03 多会话连续性 | 中断后恢复 | Run 重试、事件重放、压缩前后目标及证据版本一致 |
| P04 增量索引 | 用反馈推进状态 | 新章节／新版本索引后，旧证据正确失效，修复不越范围 |
| P05 Grounded Q&A | 生成与校验分离 | 无证据、伪引用、跨书引用、修订后旧校验结果等回归 |
| P06 完整工作区与消融 | 端到端和观测 | 将质量失败与检索、模型、工具、状态、展示阶段关联 |
| P07 自动 loop | 继续与停止规则 | 保留一次修复槽，测有效修复率、无进展比例和调用成本 |
| P08 图结构 | 显式路由与并发 | 在既有 LangGraph 上标清修复边、unknown 出口与 fan-in 规则 |

可参考课程的 [P01 对照](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/projects/project-01-baseline-vs-minimal-harness/index.md#L1)、[P03 连续性](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/projects/project-03-multi-session-continuity/index.md#L1)、[P06 消融](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/projects/project-06-runtime-observability-and-debugging/index.md#L1) 和 [P08 图练习](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/projects/project-08-graph-engineering-first-graph/index.md#L1)。

消融实验用于测量某项机制在给定任务上的边际贡献，不能单凭“移除后下降最多”就认定它是当前瓶颈。还需要失败归因；安全、授权和租户边界也不能在真实用户流量中被关闭用于实验。[L02]

## 8. 建议优先练习的三个 Noval 场景

### 场景 A：三本书的对照拆解

任务必须逐书获得可归属的证据；重复使用同一个工具名但参数不同，应能继续。某本书缺证据时，只在原授权范围内消耗一次修复额度。验收同时看任务覆盖、引用归属、重复调用数和最后的完成状态。

这个场景已与 Noval 的 task checkpoint、进展账本和 bounded repair 首版能力重叠，应复用既有测试并补效果评测。

### 场景 B：长对话中继续当前章节任务

在当前任务未完成时触发压缩，并在之后恢复。要求当前用户目标和未完成章节仍在，旧章节版本不能被摘要“复活”，最近的工具调用／结果保持配对。仅比较摘要长度不足以证明成功。

### 场景 C：工具执行中切换会话，再回到原会话

区分订阅断开、业务取消与 Run 继续执行。迟到的事件只能回到所属 Run／item；回放不得重复增加文字；只有真实终态才能结算回答。适合结合 Codex Python SDK 的身份分发设计与 Noval 现有恢复防串线测试。

## 9. 本次研究的交付边界

已完成课程方法与所列关键示例的拆解，并与实际 Codex 源码和 Noval 现有机制对照。没有安装课程应用、执行课程初始化／清理脚本、启动自动循环、调用真实模型或引用其评分作为本地实验结果。

应采用的核心方法是：明确任务状态、用可检查证据驱动完成、让失败进入有界反馈循环，并用固定条件的实验验证收益。具体落地应继续使用 Noval 现有 runtime、治理与持久化，而不是新建一套课程式 agent。

L02 来源：[第 2 讲：What a Harness Actually Is](https://github.com/walkinglabs/learn-harness-engineering/blob/77e7a3e21469dcbece2558086c8d91657abeaa40/docs/en/lectures/lecture-02-what-a-harness-actually-is/index.md#L1)。
