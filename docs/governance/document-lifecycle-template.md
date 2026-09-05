# Noval 文档生命周期模板

> 文档 ID：`document-lifecycle-template`
>
> 状态：`current`
>
> 最后审核：`2026-08-17`

新增或实质修改受控文档时，先在 `catalog.json` 登记，再使用本模板。字段定义以 catalog 为准，正文头部只保留读者最需要的信息。

## Catalog 条目模板

```json
{
  "id": "stable-document-id",
  "path": "docs/path/to/document.md",
  "title": "文档标题",
  "kind": "design",
  "status": "draft",
  "publication": "private",
  "owner": "owning-module-or-team",
  "last_reviewed": "YYYY-MM-DD",
  "review_interval_days": 90,
  "supersedes": [],
  "superseded_by": [],
  "evidence": {
    "implementation": [],
    "acceptance": [],
    "verified_at": null,
    "verified_commit": null
  }
}
```

`kind` 可使用 `index`、`catalog`、`design`、`spec`、`plan`、`report`、`guide`、`runbook` 或 `template`。首次创建默认 `draft/private`；只有完成证据与发布审核后才改成 `current/repository`。

`evidence` 为可选对象；出现时必须同时提供 `implementation`、`acceptance`、`verified_at` 和 `verified_commit`。路径必须是仓库相对路径，公开文档的证据路径必须存在。未形成可重复验收时使用 `null`，不要填写猜测的 commit 或时间。

## Markdown 正文模板

```markdown
# <文档标题>

> 文档 ID：`<stable-document-id>`
>
> 状态：`draft`
>
> 最后审核：`YYYY-MM-DD`

## 目标

说明本文解决的问题、读者和边界。

## 当前事实

列出已由源码、迁移、测试或运行验证支持的事实，并给出相对路径。

## 设计或流程

描述决策、约束、异常路径和取舍。

## 实现状态

明确区分已实现、部分实现、未实现和不在范围内。

## 验收

列出可重复的命令、测试、指标或人工检查。

## 取代关系

记录取代和被取代文档；没有时写“无”。
```

## 状态转换

```text
draft -> current -> historical -> archived
                  -> superseded -> archived
```

- `draft -> current`：内容、证据、负责人、复审周期和发布边界均已审核。
- `current -> historical`：内容仍能描述过去时点，但不能代表当前系统。
- `current -> superseded`：存在更明确的新文档，且双方关系已在 catalog 双向登记。
- `* -> archived`：不再参与日常导航，只保留审计价值。

不能通过复制旧文档并修改标题来绕过取代关系；不能仅凭计划完成勾选就宣称 current。
