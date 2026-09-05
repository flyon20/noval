# Noval 文档中心

> 文档 ID：`docs-index`
>
> 状态：`current`
>
> 最后审核：`2026-08-17`

这里是 Noval 受控文档的唯一入口。文档是否公开、当前还是历史，以 [文档目录](governance/catalog.json) 为准；源码、测试和运行配置仍是实现事实源。

## 权威顺序

发生冲突时按以下顺序判断：

1. 当前源码、数据库迁移、测试和运行配置决定“系统实际做了什么”。
2. [根 README](../README.md) 提供当前版本、模块和主链路概览。
3. `status=current` 的受控设计、规范和 Runbook 描述已审核契约。
4. `docs/superpowers/specs`、`plans`、`reports` 保存需求、实施和验收证据，不因日期较新自动成为全局权威。
5. `historical`、`superseded` 和外部参考只能解释历史，不能覆盖当前事实。

## 受控文档

- [文档治理设计](governance/noval-documentation-governance-design.md)
- [文档生命周期模板](governance/document-lifecycle-template.md)
- [Phase Closeout 模板](governance/phase-closeout-template.md)
- [机器可读目录](governance/catalog.json)
- [术语表](glossary.md)
- [文档故障排查](troubleshooting.md)
- [文档治理变更记录](changelog.md)
- [Noval AI 问答当前架构](superpowers/findings/2026-08-16-deepseek-harness-review/01-noval-ai-qa-current-architecture.md)
- [Redis Provider Cache 连续性 Shadow 实现](superpowers/findings/2026-08-16-deepseek-harness-review/06-redis-cache-continuity-shadow-implementation.md)

## 三份工作记录

项目继续使用根目录三份本地工作记录，不新增第二套“每需求一个 Markdown”机制：

| 文件 | 职责 | 是否长期权威 |
| --- | --- | --- |
| `task_plan.md` | 当前 Phase 的步骤、状态、约束和错误 | 否 |
| `findings.md` | 调研发现、源码证据、风险和决策依据 | 否 |
| `progress.md` | 实际修改、验证、部署和验收账本 | 否 |

Phase 完成时，使用 Closeout 把已审核结论晋升到设计、规范、计划、报告、Runbook 或指南；未审核的探索记录继续留在工作文件中。

## 私有与历史内容

以下内容默认不发布：未审核需求、调研草稿、提示词全文、用户内容、生产拓扑与事故细节、凭据相关信息，以及许可边界不明的外部材料。它们继续受 `.gitignore` 的 `docs/` 默认规则保护。

`docs/项目总设计-v2.md` 与 `docs/ai-qa-rag-langgraph-runtime-flow.md` 已标记为历史快照。它们只在本地存在时供追溯，不是当前实现依据。

## 维护流程

1. 从当前 Phase 的三份工作记录收集结论与验收证据。
2. 判断文档类型、负责人、状态、发布边界和取代关系。
3. 更新正文，同时更新 `catalog.json` 与本索引。
4. 运行 `python tools/docs/validate_docs.py`。
5. 在 Phase Closeout 中记录晋升、取代、验证和遗留项。

不得为了让校验通过而批量公开整个 `docs/`。新文档必须经过逐文件发布审核并加入显式 allowlist。
