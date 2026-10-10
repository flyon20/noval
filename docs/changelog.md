# Noval 文档治理变更记录

> 文档 ID：`changelog`
>
> 状态：`current`
>
> 最后审核：`2026-09-08`

本记录只汇总已经通过 Phase Closeout 的文档治理变化；产品版本发布说明仍由对应产品流程维护。

## 2026-09-08

- 新增 [Agent Harness 实施验收](superpowers/reports/2026-09-08-noval-agent-harness-implementation-acceptance.md)，记录工具进度、同 cursor 恢复、缓存保护和诊断版本的本地交付；真实命中率、质量 A/B 及生产发布继续单独验收。
- 同步当前架构与 Redis continuity 契约，为两份外部源码研究标注 Noval 实施前基线；不改写外部版本证据，不新增取代关系。
- 原实施计划和 Runbook 保持 private；只为已审核验收报告增加单文件 allowlist。迁移覆盖校验扩展到 Phase31，并核对新库初始化、Compose 顺序和 verify 规则。

## 2026-08-17

### P1 authority migration

- 发布已审核的 [Noval AI 问答当前架构](superpowers/findings/2026-08-16-deepseek-harness-review/01-noval-ai-qa-current-architecture.md) 和[Redis continuity Shadow 实现](superpowers/findings/2026-08-16-deepseek-harness-review/06-redis-cache-continuity-shadow-implementation.md)；含外部来源但缺 provenance 的关联报告继续 private。
- 将 5 个 design/plan/report/runbook 样本纳入 lifecycle catalog，并补充 implementation/acceptance evidence；未重跑的历史验收保持 `verified_at=null`。
- 修订 private J3160 Runbook 的 Phase23-30 migration、verify 和 rollback 说明，并增加 Compose/init/Runbook 静态 coverage 检查。
- validator 现在识别 tracked private 文档、拒绝 public-to-private 链接并检查公开 evidence 路径。
- 本批只修改文档和治理校验，没有修改或部署产品运行时。

### Documentation governance foundation

- 建立 [Noval 文档中心](README.md)、生命周期模板、Phase Closeout 模板和机器可读 catalog。
- 保持 `docs/` 默认私有，只公开逐文件审核通过的治理文档。
- 将 V2 总设计和旧 RAG runtime flow 标记为 historical/private，并记录取代关系。
- 增加 [文档治理与优化设计](governance/noval-documentation-governance-design.md)、[术语表](glossary.md) 和[故障排查](troubleshooting.md)。
- 增加确定性 docs validator 和标准库回归测试；本次变更未修改产品运行时。

## 记录规则

每个条目必须能追溯到 `progress.md` 的 Phase Closeout，并链接到一个 current 文档或验收证据。未完成、私有或仅有建议的工作不写入本页。
