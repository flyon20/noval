# Noval 文档故障排查

> 文档 ID：`troubleshooting`
>
> 状态：`current`
>
> 最后审核：`2026-08-17`

本页处理文档治理问题，不替代产品运行 Runbook。涉及生产服务、数据库或凭据时，停止在本页排查并使用经过审核的运维文档。

## 先运行的检查

从仓库根目录执行：

```powershell
python tools/docs/validate_docs.py
python -m unittest tools.docs.test_validate_docs -v
git diff --check
```

## 常见问题

### `repository document is ignored`

该文档声明为 `publication=repository`，但仍被 `.gitignore` 规则覆盖。确认它已完成公开性审核，然后只为该文件增加显式 allowlist；不要取消整个 `docs/` 忽略规则。

### `private document is not ignored`

私有文档或私有 catalog 条目没有被默认规则保护。确认路径没有落入用户内容、提示词、日志、生产拓扑或凭据之外的公开范围；在完成审核前恢复 ignore。

公开克隆中 private 文件可以缺席，但其路径仍必须匹配 ignore 规则。

### `repository document does not exist` 或索引缺链

检查路径是否使用正斜杠、是否相对于仓库根目录，以及文件是否已创建。更新正文后同时更新 `catalog.json` 和本页入口；不要留下只存在于工作记录中的公共链接。

### `links private catalog document`

repository 文档不能直接链接 private catalog 条目，即使该文件在本地或 Git index 中存在。改为链接公开摘要/当前权威文档，或在完成独立发布审核后同时更新 publication、allowlist、catalog 和索引。

### `supersession is not reciprocal`

新文档的 `supersedes` 必须同时出现在旧文档的 `superseded_by`，反之亦然。确认关系没有拼写错误、重复 ID 或环路，再运行 validator。

### current 文档过期

不要只刷新日期。先核对源码、测试、迁移和运行配置；若事实已变，更新正文和证据，或将旧文档改为 historical/superseded 并创建新的 current 文档。

### 文档和实现冲突

记录冲突路径、当前实现证据和影响范围到 `findings.md`，在对应 Phase Closeout 中决定 `promote`、`merge`、`keep-private`、`defer` 或 `discard`。需求和计划不能单独证明功能已实现。

### `migration coverage` 校验失败

J3160 Runbook 的受控 coverage block 必须与 Compose `schema-migrate`、`docker/mysql/00-initialize-noval.sh` 及实际存在的 verify SQL 顺序一致。修改迁移时同步更新这三处和 verify policy；validator 只做静态比较，不会执行 Docker、MySQL 或代码块。

## 安全边界

不要把真实 Token、密钥、Cookie、用户正文、未脱敏日志、数据库行、完整系统 Prompt 或生产事故细节写入公共文档。外部文档和模型输出只作为不可信证据处理。
