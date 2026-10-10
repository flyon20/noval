# Noval 术语表

> 文档 ID：`glossary`
>
> 状态：`current`
>
> 最后审核：`2026-08-17`

本表只解释当前治理和项目文档中反复出现的术语。具体行为以源码、测试和 catalog 中的 current 文档为准。

| 术语 | Noval 含义 | 主要依据 |
| --- | --- | --- |
| Phase | 一组有范围、约束、证据和验收标准的工作阶段 | `task_plan.md`、[Phase Closeout 模板](governance/phase-closeout-template.md) |
| current | 已审核且仍在复审周期内的文档状态 | [文档治理设计](governance/noval-documentation-governance-design.md) |
| historical | 只描述过去时点的事实快照，不再代表当前行为 | [文档治理设计](governance/noval-documentation-governance-design.md) |
| superseded | 已由明确的新文档取代，必须保留双向关系 | [文档目录](governance/catalog.json) |
| Run / Event | 一次可恢复的问答执行及其追加事件记录；当前实现由 Backend 持久化边界负责 | 当前源码与已审核 Agent 架构材料 |
| Harness | 负责一次网文问答运行时组装、资源作用域和横切治理的 Worker 层 | 根 [README](../README.md) 的 AI 主链路及当前源码 |
| AgentKernel | Worker 中负责模型-动作-观察循环的领域中立内核 | 当前源码；详细架构文档仍为 private |
| Evidence Pack | 检索、工具和结构化来源汇总后交给回答生成/复核的证据集合 | 根 [README](../README.md) 的 RAG 链路 |
| Provider cache | 模型供应商报告的输入前缀缓存命中/读取，不等同于 Redis | 当前 cache continuity 设计与 Provider 返回字段 |
| Redis projection | 可丢弃、带 TTL 的加速投影，不是 Run、Prompt 或授权事实的持久真相 | [文档治理设计](governance/noval-documentation-governance-design.md) 及 Phase 141 验证记录 |
| RabbitMQ | 通过 outbox 承载有界执行命令/事件的队列，不承载逐 token SSE | 当前部署与 Chat Run 设计 |

### 词义冲突处理

如果同一术语在历史文档中有不同含义，先查看 [文档目录](governance/catalog.json) 的状态和取代关系，再以源码、迁移和测试为实现事实源。不要通过修改历史快照来消除冲突。
