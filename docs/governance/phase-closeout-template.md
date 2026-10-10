# Noval Phase Closeout 模板

> 文档 ID：`phase-closeout-template`
>
> 状态：`current`
>
> 最后审核：`2026-08-17`

Phase Closeout 是工作记录进入长期文档的唯一晋升关口。把以下段落追加到 `progress.md` 对应 Phase，并据此更新正式文档、catalog 和索引。

## 可复制模板

```markdown
## Phase <N> Closeout - <标题> (<YYYY-MM-DD>)

### 结果

- 目标：
- 最终状态：complete / partial / blocked
- 用户可见变化：

### 实现与证据

| 结论 | 实现路径 | 测试/运行证据 | 可信度 |
| --- | --- | --- | --- |
| <结论> | `<path>` | `<command or report>` | confirmed |

### 文档晋升

| 来源 | 决定 | 目标文档 | 状态/发布边界 |
| --- | --- | --- | --- |
| findings/progress 条目 | promote / merge / keep-private / defer / discard | `<path or none>` | current/repository |

### 生命周期变化

- 新增 current：
- 标记 historical/superseded：
- `supersedes` / `superseded_by`：
- catalog/index 更新：

### 验证

- `<command>` -> `<result>`

### 遗留项

- 明确未完成内容、风险、负责人或建议 Phase；无则写“无”。

### 发布审核

- 凭据/用户内容/生产细节检查：pass / private
- 外部内容与许可检查：pass / private
- `.gitignore` 边界检查：pass
```

## Closeout 规则

1. `task_plan.md` 的完成状态不等同于产品验收，必须引用实现和验证证据。
2. findings 中的假设、失败尝试和外部建议默认不晋升。
3. 同一事实只选择一个 current 权威文档，其他文档通过链接引用。
4. 旧文档被取代时保留原正文，只加明确状态和指向；需要安全删除时另开任务。
5. repository 发布必须逐文件确认，不允许以目录规则批量放行。
6. Closeout 后运行 `python tools/docs/validate_docs.py` 和 `git diff --check`。
