# Noval 通用 Agent 与领域 Skills 对照实验

> 文档 ID：`autonomous-research-experiment`
>
> 状态：`current`（本地实验及历史榜单修正已实现，广泛回答质量验收未通过，未部署）
>
> 发布边界：`repository`
>
> 最后审核：`2026-10-08`
>
> 负责人：`agent-runtime`

## 结果

新增显式选择的 `autonomous` 实验路径：主模型按需加载领域 skill、自主调用现有只读工具、观察结果后继续行动，最后提交回答。现有问答入口默认仍为 `workflow`。

同模型、同合成资料、同预算的小样本对照中，实验路径四轮均通过预设的最低任务契约检查，现有流程两轮通过。实验路径同时消耗更多 tokens 和时间。该结果支持继续验证模型自主执行的方向，尚不足以支持默认切换或宣称全面质量提升。

后续已接入真实 Pi SDK、复用 Node 进程，并新增显式启用的历史证据快照。仅更换循环未显示明显提速；快照对照中，一组完整章节任务由 21.593 秒降到 10.872 秒，其中追问由 6 次模型调用降到 1 次。第二遍章节任务因连接失败未完成，且创作连续性仍有缺陷，不能据此宣称稳定提速或质量问题已解决。

9 月 28 日扩展已使用完整 `pi-coding-agent` 会话、原生 skills、工具历史与独立审稿。审稿确实补上过章节方案的因果缺口，但仍有漏检和误判；同题四轮的总耗时由 31.808 秒升到 56.115 秒，tokens 由 44484 增至 99328。

10 月 8 日新增审稿来源标记、逐项覆盖及引文校验。同稿小样本中预设缺陷的识别由 3/5 增至 5/5，但修正建议仍有错误；完整链路出现额外误报、无效审稿和预算中止，仅两轮返回完成回答。当前仍未证明整体回答质量稳定提升，不支持默认切换或据此给 Pi、DSH、Codex 作质量排名。

随后围绕历史榜单完成原 workflow、自写循环、完整 Pi 会话和 DSH 会话的同题实测。两道核心比较题中，三个循环各完成一道，原 workflow 均未完成，未出现 Pi 或 DSH 的稳定优势。原流程暴露出的单名次误判和历史追问取数缺失已在本地修复；修复后按日期选择快照、按作品 ID 计算变化，两道比较及缺档判断均由确定性逻辑完成。下文保留原版对照与修复后验证的区别。

## 实现契约

- Python 构造入口：`NovelResearchAgent(runtime_mode="autonomous")`。没有新增公开 HTTP 参数、前端开关或部署配置。
- 两条路径共享现有 Harness 的请求上下文压缩、运行准入、Provider 选择、预算、取消与工具账本。实验路径的图仅包含自主研究与确定性提交两个阶段。
- 模型先看到 ACTIVE skills 的目录，通过 `skill_lookup` 获取某个 skill 的完整方法；加载技能不会扩大工具权限。
- 工具适配器为现有榜单、书籍资料、参考资料检索、项目解析及项目知识查询提供 Pydantic 参数契约。工具名映射到兼容 Provider 的下划线形式。
- 身份、项目和作品范围由宿主绑定，不属于模型参数。项目读取必须先获得有效的解析结果；输出中明确冲突的范围字段在进入工具账本和模型上下文前被拒绝。含歧义的解析不解锁后续读取。
- 工具观察按不可信资料传入；技能方法通过现有注册表加载。工具输出有显式长度界限，截断会标识。
- 纯创作任务可直接使用用户给定信息；对已有作品的事实判断需要检索。引用编号必须对应本轮已取得或显式恢复的证据；追问中沿用失效编号时，最多反馈一次校验问题，继续使用剩余模型轮次和工具预算补查。恢复的历史快照不代表最新事实。
- 非正常停止、空回答或越界引用不会被当作成功完成。引用校验不替模型捏造或自动重编号。
- 工具进度沿现有流传递，最终答案仍使用既有响应契约。实验明确不支持检查点续跑，且使用独立的检查点 thread ID 前缀。

主要实现：[自主研究](../../../langgraph-worker/app/services/harness/autonomous_research.py)、[工具适配](../../../langgraph-worker/app/services/harness/autonomous_tools.py)、[Harness](../../../langgraph-worker/app/services/harness/webnovel_harness.py)、[入口](../../../langgraph-worker/app/services/novel_research_agent.py)。

## 对照方法

使用现有 `GoldenEvalCase` / `GoldenEvalRunner`，没有另建评分框架。新增[对照执行器](../../../langgraph-worker/scripts/agent_comparison.py)和[合成资料](../../../langgraph-worker/tests/fixtures/agent_comparison_v1.json)。

两组任务分别为原创开篇及对话追问、项目章节事实核对及后续创作方案。每组两轮，两条路径分别延续自身实际上一轮答案；按任务交替执行路径顺序。全部资料来自同一份本地合成小说，不读取生产作品、会话或数据库。

最终 r3 参数：

| 参数 | 值 |
| --- | --- |
| 模型 | `deepseek-flash`，模型目录及实际生成均确认 |
| 每次输出上限 | 2048 tokens |
| 温度 | 0.2 |
| 每轮总 token 预算 | 80000 |
| 每轮工具预算 | 8 |
| 每轮时间上限 | 150 秒 |
| 整组 Provider 调用上限 | 40 |
| 问答运行 | 8 次 |

术语、引用编号、模型成功及降级标志为自动检查项；场景完整性、语义忠实度和文学质量需要人工复核。报告保留每次模型调用的实际 usage，缺失计量不会被当成已证实的零成本。

## 最终观测

| 路径 | 最低契约通过 | Provider 尝试（成功 / 失败） | 累计已报告 tokens | 累计问答耗时 |
| --- | ---: | ---: | ---: | ---: |
| 现有 workflow | 2 / 4 | 3（3 / 0） | 11200 | 22.28 秒 |
| 实验 autonomous | 4 / 4 | 10（9 / 1） | 49347（不完整） | 65.19 秒 |

计量更正：实验路径创作首轮有一次 13.904 秒的失败尝试，之后重试成功。失败记录只有 `RuntimeError`，没有诊断详情或 usage，无法据此判断具体原因或计费。该轮最终回答通过最低契约检查，但 `usageComplete` 和 `validModelExecution` 均为 false；4 / 4 不能解释为四轮全程无失败且计量完整。r3 两条路径合计是 13 次尝试、12 次成功，60547 为已报告 tokens 的合计。

这是一轮小样本观测，未计算统计显著性。总 tokens 包含重复输入，不能直接按倍率推算货币费用；缓存命中、输入输出定价与不同交付结果都会影响成本。现有流程第一轮在模型调用前被阻断，因此这也不是相同交付质量下的成本比较。

具体发现：

1. 现有流程将明确要求不查榜单的原创任务送入榜单分支，缺少榜单证据后未生成开篇。对应的第二轮即使通过术语检查，也没有真实的上一轮第二场景可供延续。
2. 现有流程的项目章节核对选中了拆书路径，并返回本地降级结果；实验路径解析项目、读取章节并提供本轮证据引用。
3. 实验路径在最终观测中完成三场开篇、对话续写、章节核对和后续方案。创作首轮只加载两项 skills，没有额外检索项目或榜单。
4. 实验路径仍存在创作约束扩张：例如将用户给出的记忆损耗补充为必须永久、不可控、不可选。这类新增设定需要作为建议交由作者判断，不能由最低契约通过率掩盖。

### 耗时与上下文拆分

实验路径累计问答耗时 65.194 秒，逐次 Provider 调用耗时之和为 64.580 秒，两者差值为 0.614 秒。现有路径对应为 22.283、22.074 和 0.209 秒。Provider 计时包含网络与适配器处理等，差值包含本地工具、调度及其他开销；这不是纯模型推理或运行时 CPU 基准。实验使用合成 Backend，不能据此推断真实检索的耗时。

创作第二轮两条路径都只调用模型一次：现有路径输入 1626、输出 928 tokens，耗时 7.048 秒；实验路径输入 5124、输出 1773 tokens，耗时 11.750 秒。两条路径延续各自不同的首轮回答，输出长度也不同，因此不能将差异全部归因于工具定义或框架。

实验路径每次调用携带技能目录和授权工具的参数定义，循环中再累积已加载技能及工具观察。r3 跨用户轮次只恢复问答文本，没有持久化完整工具消息；章节追问中出现先生成、再因历史引用失效而补查和重答的额外调用。优化重点是上下文连续性、重复生成和模型调用次数。以上 r3 尚无 Pi 对照组；后续 SDK 对照见下节。

## Pi SDK 对照

新增仅用于评测的 [Pi 内核适配器](../../../langgraph-worker/app/services/evaluation/pi_kernel.py)和 [Node 桥接](../../../langgraph-worker/scripts/pi-eval/bridge.mjs)。通过锁文件固定 `@earendil-works/pi-agent-core` 与 `pi-ai` 0.85.0，实测使用 Node 24.19.0。实际循环由 Pi 的 `Agent` 执行；通过 SDK 的 `streamFn` 将模型请求交给现有 Python Provider，工具仍经过既有授权、Pydantic 参数与项目范围检查。子进程不继承 Provider 凭据，也不初始化遥测导出器。

这项对照保留相同的领域指令、skills、工具定义、串行工具执行及跨轮问答历史；不使用 Pi 编程工具、原生 Provider 传输或长期会话存储。每次内核调用创建新的 Pi Agent，最终实现复用其 Node 进程。它验证的是 Pi 循环替换及桥接成本，不代表完整 Pi 产品的性能上限。

### 首组：三条路径，各重复两遍

使用同一合成资料与 `deepseek-flash`，每次输出上限 1536 tokens、温度 0.2；所有问题附加相同的约 350–500 字要求。每条路径独立延续自己的实际回答，按任务和重复次数反转执行顺序。原始证据名为 `comparison-pi-r1.json`，保留在私有实验目录。

| 路径 | 最低逐轮检查 | Provider 尝试 | 已报告 tokens | 输出 tokens | 累计耗时 |
| --- | ---: | ---: | ---: | ---: | ---: |
| workflow | 4 / 8 | 6 | 19328 | 2547 | 24.251 秒 |
| autonomous | 8 / 8 | 20 | 92019 | 5785 | 59.511 秒 |
| Pi，逐次启动进程 | 8 / 8 | 18 | 80678 | 5229 | 68.366 秒 |

44 次尝试均成功且返回 usage，没有输出达到 token 上限。四组自主路径与 Pi 的首轮首次请求，messages/tools 摘要哈希完全相同。后续历史、工具决策和输出会分化。两条自主路径各有四组两轮任务通过最低检查；workflow 各组首轮均失败，不能作为同等交付的速度基准。

长度指令未被严格遵守：autonomous 最终回答为 565–748 个非空白字符，Pi 为 581–689；字符包含标点和格式，并非严格汉字计数。两者总字符数相差约 6%，不能声称完全等长或严格控制输出。

Pi 的 Provider 调用耗时为 53.364 秒；调用外耗时为 15.002 秒，其中 14.583 秒来自 10 次 Node/SDK 启动（含引用纠正的额外内核调用）。因此修正评测接入，使整组实验复用一个进程，同时每次仍重建独立 Agent 上下文。测试验证复用不会泄漏上一任务的工具历史，且显式传入的纠正上下文能够保留。

### 补测：复用进程

补测沿用相同模型、资料和长度要求，仅比较 autonomous 与 Pi，最多 20 次尝试；证据名为 `comparison-pi-r2.json`。实际发生 19 次尝试：18 次成功，一次 autonomous 创作首轮 `ReadTimeout`，该失败耗时 64.546 秒且无 usage。其后续轮次停止，因此只收集到 7 / 8 轮，CLI 返回非零状态；这组不能用于完整创作任务的速度比较。

双方均完成的章节核对及方案任务：

| 路径 | 两轮最低检查 | 模型调用 | 已报告 tokens | 输出 tokens | 两轮累计耗时 |
| --- | ---: | ---: | ---: | ---: | ---: |
| autonomous | 2 / 2 | 8 | 39423 | 2107 | 19.864 秒 |
| Pi，复用进程 | 2 / 2 | 7 | 33997 | 1971 | 19.679 秒 |

两条路径相差 0.185 秒（约 0.9%），样本与执行次数不足以证明速度优势。Pi 本组仅启动一次，启动耗时 1.210 秒已计入其创作首轮；后续章节任务没有重复启动。两轮章节任务在 Provider 调用之外的差值分别为 autonomous 0.093 秒、Pi 0.101 秒，表明当前桥接与循环本身在该样本中已不构成主要耗时。

本次扩展共 63 次 Provider 尝试，其中 62 次成功；合计已报告 277779 tokens，超时尝试的消耗未知，不据此推算完整费用。原始失败未删除，也未用失败耗时夸大 Pi 的速度收益。

### 质量结论与适用边界

最低检查只覆盖输出术语、引用编号和降级标志。人工复核发现两条自主路径都会改变记忆损耗的触发规则，或将已忘记的信息再次作为新代价；Pi 某次双人对话还让买主短暂出场，补测的追问也没有忠实扩写先前的第二场景。章节核对中的“期限已过”等判断超出了所给摘要的确定信息。不能将最低检查全部通过解释为语义、连续性或文学质量全部合格。

可确认的工程结果是：Pi 可以接入当前领域 skills 和只读工具，进程复用可去掉重复启动成本。当前对照未证明仅更换通用循环就能明显加快回答。完整工具会话持久化、自动证据新鲜度验证、并行真实检索、原生 Pi Provider 传输和首字延迟均未验证；本实验的 Backend 为合成数据。历史快照的有界复用见下节。

## 历史快照与领域约束改进

### 实现与适用边界

新增 [AutonomousConversationContext](../../../langgraph-worker/app/services/harness/autonomous_context.py)，由调用方为单个会话分支持有并显式传入：

```python
from app.services.harness.autonomous_context import AutonomousConversationContext
from app.services.novel_research_agent import NovelResearchAgent

context = AutonomousConversationContext()
agent = NovelResearchAgent(runtime_mode="autonomous", autonomous_context=context)
```

同一对象用于该分支的顺序调用。请求必须携带正整数用户 ID、会话 ID，以及与上轮实际问答一致的完整历史和摘要。用户、会话、项目、作品、参考作品或偏好 skill 变化，历史不匹配、过期或回答失败时，不复用原快照。默认存活 30 分钟，连续追问不会滚动延长旧证据的存活时间；没有跨进程保存或自动会话存储。

证据最多保留 20 条、序列化总计 16000 字符。每条须匹配显式项目/作品范围，含 generationId、chapterVersion、contentHash 和 preview；整组不合格或超限时放弃证据缓存，不截取部分后重编号。恢复前通过现有项目解析工具重新核验访问范围，并消耗既有工具预算；无权限、解析歧义或预算不足时不恢复。快照深拷贝，不与响应对象共用可变引用。

恢复的证据标记 `historicalReference=true`、`freshness=prior_turn_snapshot`，以不可信资料传给模型，明确 `currentStateVerified=false`。当前解析接口不返回版本清单，因而这里验证的是访问范围，不是章节是否发生变化。新检索的版本与历史快照保持独立引用。新鲜度要求目前依靠来源标记和模型指令处理，尚未实现确定性的修订检测；它适用于接着前文分析或起草，不能当作最新资料缓存。

最多保留 8 项已加载 skill 的版本 pin，只有当前注册表 pin 完全一致才恢复方法正文，正文总计不超过 24000 字符。响应包含 `continuationContext` 元数据，记录是否启用、恢复 skill/证据数量和项目复核结果。

共享领域包同步更新：开篇与章节规划 1.1.0、项目知识问答 1.2.0。方法要求保留能力主体、对象、触发、限制与代价，跟踪已经失去的知识；扩写指定场景时遵守用户限定的角色和动作，区分已发生事实、推断及创作提案，并优先遵守用户格式/长度。三个包是现有注册表的共享内容，因此也会影响加载它们的 workflow。自主循环和快照仍是显式实验入口，默认路由未变。这些方法指令不构成确定性的语义约束验证器。

### Pi 与 Pi context 对照

评测新增 `pi_context` 路径，每个任务和重复次数使用独立快照对象。`pi` 仅延续问答文本，`pi_context` 可恢复历史证据和 skill；两者共用实际 Pi SDK、Python Provider、合成资料和更新后的领域包，并延续各自实际答案。两组系统提示中的上下文策略从首轮即有差别，所以这不是完全相同首轮请求的缓存基准，也不能单独归因于领域包升级。

`comparison-context-r1.json` 使用 `deepseek-flash`，重复两遍，计划 16 轮，最多 40 次 Provider 尝试，每次输出上限 1536 tokens，沿用相同约 350–500 字指令。报告升级为 schemaVersion 3，逐路径记录 historyPolicy。实际记录 16 行，其中 14 行生成答案，最后两行错误；CLI 非零退出。

| 路径 | 最低检查（通过 / 失败 / 错误） | 尝试（成功 / 失败） | 已报告 tokens | 全部行累计耗时 |
| --- | ---: | ---: | ---: | ---: |
| Pi | 5 / 2 / 1 | 25（20 / 5） | 97065 | 85.568 秒 |
| Pi context | 7 / 0 / 1 | 15（12 / 3） | 57186 | 64.981 秒 |

合计 40 次尝试、32 次成功、8 次 `ConnectError`，诊断均为 `upstream=connect_error`；154251 是已报告 tokens，失败尝试的 usage 未知。最后的 context 轮只记录了三次连接失败，随后达到总调用上限，最终 `RuntimeError` 与执行器的上限守卫一致。Pi 进程在前一个失败轮后回收，后一轮重新启动；两次启动共 2.432 秒。上表包含不同数量的失败和重试，不能拿累计耗时算整体提速。

首遍双方均完成的章节核对与方案任务：

| 范围 | Pi | Pi context |
| --- | ---: | ---: |
| 两轮累计耗时 | 21.593 秒 | 10.872 秒 |
| 两轮模型调用 | 9 | 4 |
| 两轮已报告 tokens | 48816 | 20846 |
| 两轮输出 tokens（含中间生成） | 2000 | 1048 |
| 仅追问耗时 | 14.257 秒 | 3.861 秒 |
| 仅追问模型调用 | 6 | 1 |
| 仅追问已报告 tokens | 34177 | 6000 |

该追问恢复了 1 项 skill 和 3 条历史证据，仅重新执行项目解析；普通 Pi 重新检索四次。这里的收益主要来自减少重复检索、失效引用纠正和重答。两组使用各自首轮回答，生成长度不同；Backend 是合成实现，不能外推真实检索服务的绝对耗时。第二遍章节追问双方都失败，没有第二组完整任务验证稳定性。

纯创作追问两遍都只调用模型一次，context 分别为 4.578、5.161 秒，普通 Pi 为 4.045、4.241 秒，未显示提速。全部成功调用都未达到输出 token 上限，但最终回答为 Pi 575–649、context 552–844 个非空白字符，仍超出共同长度要求，不能声称输出长度严格受控。

### 内容复核

普通 Pi 的两次最低检查失败分别是没有使用“怀表”和“记忆”这两个字面词；正文使用了“表”或具体遗忘描写，不能据此断言没有交付相关内容。独立检查全文后，两组仍有实质问题：

- 创作追问会把要求扩写的第二场景换成事后汇报或另一场盘问，重复使用已经失去的记忆，或新增“看得越深忘得越多”“缩短回溯可省代价”等机制。context 虽改善了部分代价连续性，仍未稳定保持场景和规则。
- 章节事实核对总体区分了“尚未归还”和“未来计划”，但仍有将“本次未交还”扩大成“看守从未接触过”的断言，以及对钟声条件的不严谨解释。
- context 的章节方案保留了既付记忆代价，并给新回溯分配不同代价，但其中一案把“钟声响起前归还”误解成先响钟再归还；普通 Pi 的另一案让失明看守直接“看见”回溯影像，未建立这种能力。
- 历史引用编号通过校验只说明编号可用，不能证明引用支持相邻的全部创作情节。仍需要事实、提案和语义支持的独立判断。

因此本轮确认的是有界历史快照能够减少部分追问的重复调用；领域方法已补充，但未证明约束保持或文学质量达到可默认切换的标准。保留全部失败和原答，不追加模型调用追求全通过结果。

## 完整 Pi 会话与独立审稿

评测器增加 `pi_full` 和 `pi_full_review`，通过固定的 `@earendil-works/pi-coding-agent@0.85.0` 调用实际 `createAgentSession`。旧 `pi` / `pi_context` 使用 `pi-agent-core`，只接入 Agent 循环；两者不能代表完整产品能力的对照。

完整接入包含以下机制：

- `DefaultResourceLoader` 原生发现由宿主注册表生成的 `SKILL.md`；`read` 仅接受这些已授权文件的精确路径，并映射回宿主 skill 加载器。默认文件、shell、编辑工具及工作目录自动发现均关闭。
- `SessionManager` 在私有评测目录写入 JSONL，同一任务追问复用实际 `AgentSession` 和工具调用/结果。用户、会话、作品范围、历史、skill pin 或固定 TTL 失配时新建会话；项目资料恢复仍先核验权限。历史标记通过 `sendCustomMessage` 同时进入活动上下文和会话记录。
- `pi_full_review` 启用审稿交付条件。`review_draft` 通过内联扩展启动独立 `AgentSession`，由宿主提供原始问题、历史、资料和草稿；模型未主动调用时，宿主在交付前调用同一扩展执行器并要求父会话修订。审稿会话无工具，最多每用户轮一次，消耗共享工具、委派及模型预算。预算不足以完成审稿时返回未完成；引用修正不重复审稿。它是同一 Node 进程内的独立会话，不是官方子进程示例的直接安装。
- Pi 原生压缩启用，压缩模型调用仍走宿主 Provider，计入调用和 token 预算。离线长历史测试验证实际压缩记录及后续回答；这不等同于已经验证压缩后的语义质量。
- Node 子进程不接收 Provider 凭据，禁用联网模型发现与遥测；Provider 传输、重试、能力校验和工具授权继续归 Python 宿主。取消包括正在审稿及轮间退出时的进程回收。

发布包兼容性：0.85.0 的 SDK 入口间接导出实验 server，但未声明所需 `pi-server`，本评测依赖显式固定补齐同版本包；不启动该 server。安装使用 `--ignore-scripts --no-audit --no-fund`。实际测试为 Node 24.19，源码、安装版本及锁文件分别记录；上游源码 checkout 不被当作已安装包版本。

官方能力边界也支持区分“循环”与“完整运行时”：

| 项目 | 官方能力与本项目适配点 |
| --- | --- |
| DSH（DeepSeek Agent Harness） | 标准 SDK/base 包含 skills、subagents、压缩、持久化与权限策略；`sdk-minimal` 示例刻意省略多项机制。仍属 developer preview，应留意接口变更。 |
| Pi | 完整 coding-agent SDK 提供会话、资源、扩展与压缩；子 Agent 可由扩展添加。适合由 Noval 保留 Provider/权限控制并组合领域工作方式。仅安装基础包不会自动获得专业审稿工作流。 |
| Codex | SDK 驱动完整 Codex 运行时，支持 skills 和子 Agent 配置；产品能力不能用裸循环实验推断。本轮没有运行 DSH/Codex 的同题质量对照。 |

以上是架构比较，不是质量排名。官方参考：[Pi SDK](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sdk.md)、[Pi subagent 示例](https://github.com/earendil-works/pi/tree/main/packages/coding-agent/examples/extensions/subagent)、[DSH](https://github.com/deepseek-ai/deepseek-harness)、[Codex SDK](https://learn.chatgpt.com/docs/codex-sdk)、[Codex 子 Agent](https://learn.chatgpt.com/docs/agent-configuration/subagents)。

实现：[完整会话适配](../../../langgraph-worker/app/services/evaluation/pi_session.py)、[SDK bridge](../../../langgraph-worker/scripts/pi-eval/session-bridge.mjs)、[完整会话测试](../../../langgraph-worker/tests/test_pi_session.py)。本次固定 SDK 的实际接口与上游 main 文档存在差异，代码以安装包为准。

### 完整会话实验结果

两批均使用相同 `deepseek-flash`、合成资料、1536 输出 token 上限和约 350–500 汉字的长度提示，按任务反转组间顺序，各组沿用自身上一轮回答。网络预检一次成功，报告 9 tokens。两批合计 20 个回答、44 次 Provider 尝试、零传输失败；含预检共 45 次生成、270188 reported tokens，未超过两批共用的 48 次对照尝试额度。未继续追加调用。

首批 r1 仅提供可选审稿工具，四次都未调用，因此不能用于验证审稿收益：

| 路径（各四轮） | 模型调用 | reported tokens | 总耗时 | 实际审稿 |
| --- | ---: | ---: | ---: | ---: |
| Pi core＋历史快照 | 7 | 33759 | 24.550 秒 | 0 |
| 完整 Pi | 7 | 47360 | 31.626 秒 | 0 |
| 完整 Pi＋可选审稿 | 7 | 45248 | 29.840 秒 | 0 |

完整 SDK 的两个任务分别启动进程，总启动开销约 7.6 秒，core 组共享一次启动约 1.2 秒；所以以上不是相同进程策略下的纯循环性能比较。三组的所有追问都只用一次模型调用，完整会话在此样本中没有进一步减少调用数。完整会话追问实际保留工具历史，历史资料标记进入模型上下文。core 唯一最低检查失败只是没有使用“记忆”字面词，不能据此判定没有写遗忘。

修正审稿交付条件后的 r2 单独比较完整 Pi 与必须审稿的完整 Pi：

| 路径（各四轮） | 模型调用 | reported tokens | 总耗时 | 实际审稿 | 最低检查 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 完整 Pi | 7 | 44484 | 31.808 秒 | 0 | 4/4 |
| 完整 Pi＋审稿交付条件 | 16 | 99328 | 56.115 秒 | 4 | 4/4 |

审稿组约增加 76% 总耗时、123% tokens。完整答案的非空白字符区间分别为 565–674 和 524–768；长度提示不是严格等长控制。原生压缩未在真实短任务中触发，只有离线长历史测试覆盖。两组使用同模型但不同初稿，仅两个任务、各一遍，不能将全部组间差异归因于审稿。

执行者对完整答案、审稿原文和修订内容进行非盲语义复核，结论如下：

- 无审稿组仍出现具体硬错：没有新回溯却新增记忆损失；失明看守判断“脸色”；把能力目标由物件改成人；转交怀表后无法确认看守收到，却声称方案能兑现承诺。
- 审稿组的四次审稿有三次判为“未发现实质问题”，漏掉了凭空增加“只剩一次记忆额度”、失明看守判断脸色等缺陷。摘要记录遗忘门牌，不等于证明永久不可恢复；审稿仍把这一额外断言当成来源事实。
- 章节方案的审稿发现从塔下到找到看守缺少进入动作，修订确实补上侧门；也弱化了回溯结果必然令看守相信的预设。这是可观察的局部修正。
- 同一次审稿把已经明确标为创作计划的叙述误判为已发生事实，纠偏过度；修订后的第三步又出现重复交还结果，仍未完全形成三个独立、相接的行动。

因此“安装完整 Agent＋挂审稿”提供了有用机制，但不是质量保证。应先建立明确的规则/场景/因果验收，区分审稿的漏检与误判，再评估是否值得承担额外调用成本。原始答案、会话、逐条复核和分批源码快照均保留在私有工作证据中，公开报告不复制全文。

## 审稿来源与引文校验（2026-10-08）

### 已实现的机制

此前审稿接收扁平的 question/history/sources/draft，任意正常结束的文字都计为完成审稿。现在由宿主标明用户指令、旧回答提案、带版本的来源摘录及摘要线索，保留原文和稳定 entry ID。旧回答可用于用户选定场景的连续性核对，但不能独立证明能力规则；摘要不能独立充当事实证据，历史摘录也不等于当前状态。

审稿需返回 requirements、continuity、world_rules、causality、evidence 五项状态，以及带草稿原文、来源原文、解释和建议的结构化问题。宿主验证 JSON 结构、检查项覆盖、引文确实出现和来源类别。缺项、编造引文、把摘要作为证据或仅凭旧稿认定世界规则，都会产生 `invalid` 结果，设置 `reviewRequiredButMissing`，不计为通过审稿。`uncertain` 单独保留，不包装成无问题。

这只是出处和结构校验：引用真实存在不代表推论成立，结构完整也不证明审稿意见正确。实现仍仅接入本地 `pi_full_review` 评测路径。默认问答、Provider 设置、共享领域 skill 文本及部署均未改变。

实现：[审稿契约](../../../langgraph-worker/scripts/pi-eval/fiction-review.mjs)、[Pi bridge](../../../langgraph-worker/scripts/pi-eval/session-bridge.mjs)、[宿主会话适配](../../../langgraph-worker/app/services/evaluation/pi_session.py)。测试：[Node 契约测试](../../../langgraph-worker/scripts/pi-eval/fiction-review.test.mjs)、[原生 Pi 集成测试](../../../langgraph-worker/tests/test_pi_session.py)、[六组成对合成样例](../../../langgraph-worker/tests/fixtures/fiction_review_v1.json)。

### 同稿对照的局部收益

有效批次固定同一 `deepseek-flash`、fast 模式、1536 输出 token 上限，对五组缺陷/无错草稿分别运行旧版与新版审稿，共 20 次调用；按样例反转先后顺序。两版使用完全相同的草稿，新版同时改变了资料组织和审稿方法，不能将差异归因于单一提示词。预设标签未发送给模型，执行者做非盲语义复核。检查维度字段不是自动质量评分。

| 预设缺陷 | 旧版识别 | 新版识别 | 复核说明 |
| --- | --- | --- | --- |
| 失明角色直接看见脸色 | 是 | 是 | 旧版另把叙述性动作误判为违反写作形式；检出主要问题不代表所有意见正确。 |
| 把旧回答自创的次数余额当成设定 | 否 | 是 | 新版虽指出无依据的限制，建议却仍允许保留部分余额表述，修正不彻底。 |
| 未再使用能力却重复扣除同一份记忆 | 是 | 是 | 两版均识别明确的重复代价。 |
| 把尚未发生的情节写成章节事实 | 是 | 是 | 两版均指出事实与资料冲突。 |
| 改换用户指定扩写的场景 | 否 | 是 | 新版定位了时间、地点及当时动作的变化。 |

五份无错草稿两版均未报告实质问题，包括合法的非视觉感知、已有遗忘的表现以及明确标注的创作方案。这里只能说这五份无错稿没有误报，不能据此推断所有审稿意见的精确率。

| 同稿审稿方法（各 10 次） | reported tokens | Provider 耗时合计 | 预设缺陷检出 | 无错稿告警 |
| --- | ---: | ---: | ---: | ---: |
| 旧版 | 3496 | 21.157 秒 | 3/5 | 0/5 |
| 来源标记＋结构化审稿 | 10537 | 22.328 秒 | 5/5 | 0/5 |

新版 10 份报告均通过结构与引文校验。以上是直接的一次模型审稿调用，未测 Pi 进程开销；样例短、数量少、没有重复或独立盲评，不是完整任务质量、稳定耗时或产品排名结论。第六组条件期限样例保留在测试资料中，因预算安排未纳入本次直接模型对照。

### 完整链路验收未通过

随后实际运行 Pi SDK 的原有两个两轮任务，共 15 次成功 Provider 调用、92337 reported tokens，结果如下：

| 任务 | 状态 | 调用数 | 实际观察 |
| --- | --- | ---: | --- |
| 开篇三场景 | answered | 6 | 审稿有一处有效的来源状态收窄，但也错误反对合法的重复能力使用，并臆测来源要求人物独处。 |
| 指定场景扩写 | insufficient_evidence | 2 | 审稿引文未通过 `reference_quote` 校验，未进入修订；未完成草稿仍含感知、连续性及失效引用问题。 |
| 第三至第五章事实核对 | answered | 6 | 归还状态基本正确，但把已发生遗忘写成“不可回退”，超出来源；审稿未指出这一断言。 |
| 第六章方案追问 | error | 1 | 此后下一次调用被全局额度挡住，未形成最终回答。属于实验预算中止，不是第 36 次真实请求失败。 |

完整答案和审稿原文复核显示：新版在长上下文中仍把“第二次/第三次行动”误当成新增的次数限制，把资料未提到陪同者误读成必须独自行动，并可能把与同一人物相关的不同记忆误判为同一份代价。这些错误推论引用的文字确实存在，因此通过了机械校验；其中部分错误建议也进入了修订稿。无效审稿只保留了错误类别，未持久保存子会话原始文字，不能声称已经逐条定位其失败引文。

因此本轮交付的是可追踪、可拒绝无效报告的审稿机制，以及同稿样例上的检出收益；完整多轮回答尚未通过质量验收。后续应验证审稿推论和修正建议、减少无关资料读取、补足失败审稿证据，并在独立预算下重新跑完多轮任务。

### 验证、额度与证据边界

- 相关 Python 回归 52 项通过；Node 审稿契约测试 10 项通过。前者覆盖实际 Pi 会话、工具历史、审稿、授权、预算、取消和压缩。模拟 Provider 证明机制，不证明文本质量。初次取消测试曾在审稿启动前超时，隔离复跑和完整回归均通过，未修改产品超时。
- 第一批直接对照漏传 fast 模式，只有两次完成、3216 reported tokens，随后三次 RuntimeError 停止；缺失的终态及 usage 无法补证。离线参数核对确认未指定模式会省略 reasoning 控制，而主链路 fast 会发送 effort=none。这批单独保留，不并入有效同稿对照。
- 第二批的 35 次 Provider 请求全部成功，106370 reported tokens；安全记录确认全部发送 fast 控制且返回 completed。两批共 40 次尝试、37 次成功，合计 109586 reported tokens，另有第一批三次失败的未知 usage。未追加生成，不推算实际账单。
- 原始草稿、审稿全文、回答、失败、逐次用量、源码快照和复核笔记保留在私有工作证据；本报告只发布方法、合成样例的汇总与限制。

## 历史榜单任务：workflow、自写循环、Pi 与 DSH（2026-10-08）

### 接入与对照范围

本轮问题是“请求拿往期榜单比较，却得不到实际比较结果”。先冻结编辑前完整 worker 源树，确认 workflow 的业务依赖未变，再扩展三个 agent 循环共享的工具：`rank_lookup` 增加成对日期范围或滚动天数，宿主据此启用历史查询；观察保留作品 ID、名次、榜单身份及快照日期。总行数上限为 60，日期范围返回多份快照时必须分别检查完整性。仅切换循环、继续使用原先强制 latest 的工具不能完成历史任务。

Pi 使用已固定的 `@earendil-works/pi-coding-agent` 0.85.0 原生会话。DSH 使用已发布的 0.1.0-rc.6 AgentLoop、SessionStore、JSONL 持久化、SkillRegistry、ToolRuntime 与压缩组件，固定 Cordis 4.0.1；实际运行于 Node 24.19.0。DSH 是与 Noval 领域工具组合的运行时，未加载默认 CLI 的 shell、用户配置或遥测插件，不能称作原封不动的 DSH CLI 产品对照。Python 宿主继续控制身份、权限、预算与 Provider，实际 DSH 循环在 Node 中执行。

四组统一使用 `deepseek-flash`、fast、temperature 0.2、每次最多 3072 输出 tokens、每轮 8 次工具调用和 80000 tokens；保留各自的提示词组织与执行流程。原 workflow 的既有路径作为原版基线，三个 agent 循环使用同一套扩展后的历史工具。每个用例轮换执行顺序，各组保留自己的真实对话历史，预留独立的组别及用户轮次额度。

资料是专门编写的合成榜单，不是线上市场样本。四份 Top10 快照包含周内干扰日期、改名但 ID 不变的作品，以及三本新进、三本离开前十的作品；正确周对比是 10 月 4 日至 10 月 8 日，共同作品 7 本，留存率 70%。三个用例分别为完整比较请求、先查最新再追问历史，以及请求一个没有存档的一月日期段，共四个用户轮次、四组共 16 轮。来源覆盖和引用编号只做机械检查，最终日期、数字及引用对应另由执行者非盲复核。

### 原版实测结果

| 路径 | 完整请求比较两周 | 承接前轮比较历史 | 最新 Top10 | 指定日期无存档 |
| --- | --- | --- | --- | --- |
| 原 workflow | 误答当前第 1 名 | 没有执行历史查询，未完成比较 | 只给第 1 名 | 没查一月，却宣称一月无存档 |
| 自写自主循环 | 取齐资料，但错选 9 月 28 日 | 日期、升降、新进/离开及 70% 留存正确 | 完整且日期正确 | 实际查询后正确停止比较；把 10 月 2—8 日误称本周 |
| 完整 Pi 会话 | 日期、身份、名次及引用正确 | 错把 10 月 5 日当上周，得到错误的 90% | 完整且日期正确 | 实际查询后正确停止比较；同样出现日历周口径错误 |
| DSH 会话 | 取齐资料，但错选 9 月 28 日 | 日期、升降、新进/离开及 70% 留存正确 | 完整且日期正确 | 实际查询后正确停止比较；“本周仅一份”与列出另一份快照的表述矛盾 |

三个循环在两道核心历史比较中均为一题正确、一题错误；Pi 第一题的引文已经逐项核对，不能把重复查询形成的引用组误判为错误。自写与 DSH 第一题还将离开前十的作品放入“共同作品”表，并误报共同作品数量。原 workflow 的追问则出现“尝试调取”的文字与零次历史调用不一致。16 轮均标为 `answered`，并不代表这些问题已经完成。

| 路径（各四轮） | 模型调用 | reported tokens | 总耗时 |
| --- | ---: | ---: | ---: |
| 原 workflow | 3 | 13641 | 11.937 秒 |
| 自写自主循环 | 11 | 67154 | 30.397 秒 |
| 完整 Pi 会话 | 13 | 94655 | 47.114 秒 |
| DSH 会话 | 10 | 71812 | 30.520 秒 |

耗时包含本地 SDK 开销与 Provider 等待，且只有一次小样本执行；失败任务耗时不能用于宣称更高效率。原生 Pi/DSH 在这组榜单追问上没有复用原生会话：宿主的历史证据缓存目前只保留经过项目/作品版本授权的来源，不恢复榜单来源。因此本轮没有测得原生榜单工具历史复用的收益，也没有证明任何框架在更多任务上的质量或性能排名。

### 原 workflow 的本地修正与单独验证

修正发生在上述原版测量之后：

- `IntentRouter` 保留明确历史榜单比较的两个日历时间段，以及追问里的榜单范围和 Top N；“往期”未指定日期时使用上周与本周，并在答案列出实际范围。明确日期段对本周的请求按给定日期查询。
- 单名次快捷查询要求明确的名次，不再因“列出作品、排名”中的“排名”二字默认查第 1 名。比较请求继续进入两期查询。
- 每个时间段通过现有授权和工具账本查询，取该段最后一份快照；逐期检查 Top N 名次完整、作品身份有效及榜单相同，再按 bookId 计算升降、新进、离开和留存。即使较早快照完整，也不替代用户指定的最后一份不完整快照。
- 缺档、查询失败、榜单不一致及不完整快照分别记录；只有实际成功查询到空结果才称为无存档。证据不齐时不计算变化。比较表和派生统计引用选中的两份快照，继续通过原有引用校验。

同一四轮夹具复测得到两道正确比较、完整最新 Top10，以及实际查询后的缺档回答。两道比较均选择 10 月 4 日与 10 月 8 日，改名作品由第 3 升至第 1，留存率为 70%；各保留 20 条有效来源。缺档回答为预期的 `insufficient_evidence`，不属于执行失败。四轮总共调用模型一次、7993 reported tokens，用于最新列表；比较和缺档判断均为零模型调用。最新列表仍保留既有的较冗长格式，这次验收关注榜单事实与任务执行，不声称通用文本质量已解决。

这是领域取数和计算逻辑的修复，不能归因为某个 agent 框架更聪明。当前证据支持先确保工具能拿到正确资料、把可精确计算的榜单差异做成可验证的能力，再决定是否为会话、技能或维护成本迁移运行时。默认选择仍为 `workflow`；本次本地代码未部署。

实现：[日期与比较事实](../../../langgraph-worker/app/services/rank_comparison.py)、[意图路由](../../../langgraph-worker/app/services/intents/intent_router.py)、[问答接入](../../../langgraph-worker/app/services/novel_research_agent.py)、[DSH 宿主](../../../langgraph-worker/app/services/evaluation/dsh_session.py)、[DSH bridge](../../../langgraph-worker/scripts/dsh-eval/session-bridge.mjs)、[比较执行器](../../../langgraph-worker/app/services/evaluation/ranking_comparison.py)。验收：[历史比较回归](../../../langgraph-worker/tests/test_historical_rank_comparison.py)、[比较夹具和执行器测试](../../../langgraph-worker/tests/test_ranking_comparison.py)、[DSH 原生会话测试](../../../langgraph-worker/tests/test_dsh_session.py)、[合成快照夹具](../../../langgraph-worker/tests/fixtures/rank_comparison_v1.json)。

### 验证和调用账本

历史比较回归 11 项通过，包含改名、缺档、工具失败、不完整快照、不同榜单、重复名次、跨年日历周及完整最新列表；与意图、市场分析和数据规划合并共 94 项通过。既有问答的 rank/trend/followup 选择集 82 项通过。原生排名对照、DSH 与自主循环合并 28 项通过，覆盖真实 SDK 会话、授权、取消和固定日期；较早相关集 60 项通过。不同选择集有重叠，不相加为独立测试数量。

按 intent/market/data 三模块直接加载时遇到的 `AgentSupervisor` 循环导入，在编辑前冻结源码上同样复现；按现有 Harness 初始化顺序加载后上述 94 项正常通过，未进行无关导入重构。新原生测试初次曾因 Pi 的旧包命名空间而跳过，修正检测后实际执行，跳过不算通过。

新实验上限为 100 次尝试，最终只使用 40 格：首次预检因缺少必填 `require_json` 在发送前发生本地 TypeError；修正后原版比较及预检共 38 次真实成功调用，修复后验证另一次。实际成功调用 39 次，共 255264 reported tokens；首个本地参数错误没有构建网络请求，不混入 agent 质量评价或未知远端计费。39 次真实请求全部使用 fast 控制并返回 completed。各批次原始回答、工具请求、原生 JSONL、逐次 usage、非盲复核及冻结源码均保留为私有证据，没有覆盖旧实验。

## 验证及中间失败

- 自主路径和对照执行器：最终 18 项行为测试通过。覆盖按需 skill、多轮历史、修正检索、身份参数拒绝、项目解析与输出范围、歧义、预算、失效引用补查、取消、流事件以及公平性约束。
- 内核、流进度与已有 Golden 评测器：与第一版自主测试合计 84 项通过；后续改动集中在自主路径并由上述最终测试复核。
- 现有问答、trace、API、准入及检查点：首次 315 项通过、5 项失败；失败来自绕过构造函数的检查点测试对象缺少新增模式字段。补充类级默认值后，检查点与自主/评测子集 31 项通过。
- 首轮真实调用被本机非公网 DNS 解析挡在 Provider 地址校验之前，不能用于质量比较。只在本地评测进程中采用验证后的公网解析，保持地址检查和 TLS 主机名校验，单次预检成功后继续实验。
- r2 发现不必要的项目读取和跨轮失效引用，补充通用行动相关性指引及有界反馈后运行 r3。原始失败报告保留为私有证据，没有覆盖。
- r3 后仅做格式整理，AST 摘要一致；未改动已测行为。
- Pi 扩展：真实 SDK 离线测试覆盖模型/工具消息往返、无权限工具、参数拒绝、调用预算、取消后进程回收、复用进程的上下文隔离及 Harness 评分接入；与原自主行为、对照公平性测试合并验证。Provider 尝试摘要记录请求哈希、失败、输入输出 usage 和输出上限触达情况。
- 快照扩展：context/自主/skill 注册表子集 53 项通过；Pi/context/自主/对照子集 38 项通过。覆盖用户与会话隔离、历史变更、固定 TTL、整组引用界限、深拷贝、授权失败、零工具预算、历史与当前版本共存、skill pin 失效，以及实际 Pi 下的追问恢复。
- 共享 skill 回归：既有问答选定子集 59 项通过、1 项旧输出模板断言失败；只更新该过期断言，既有内容表格检查保持，随后该用例与检查点子集 15 项通过。报告 schemaVersion 更新后 context/对照子集 14 项通过。以上测试集有重叠，不将通过数相加。
- 快照实验的 14 个来源文件 SHA-256 与执行前记录一致；报告与私有复核使用同一份未覆盖的原始实验 JSON。
- 完整 Pi 初版合并子集 48 项通过，包含 10 项完整会话测试及既有 Pi、快照、自主路径和对照测试。审稿交付条件修正后，受影响的完整会话子集 12 项全部通过，增加漏调补审稿和引用修正不重复委派两项。覆盖原生 skill 与工具历史、范围/历史/TTL/授权/skill pin 失效、预算、取消及实际压缩。仅有既存 multipart 弃用警告；测试集有重叠，不将通过数相加。模拟 Provider 的测试证明机制正确，不证明回答更好。
- 文档全检仅报告既有 `phase51-tasks9-14-acceptance` 超过复核期限；基线目录与当前该条目完全一致。本实验的元数据、索引、链接和发布边界未产生新错误；路径级空白检查通过。

回归入口：[自主行为测试](../../../langgraph-worker/tests/test_autonomous_research.py)、[对照测试](../../../langgraph-worker/tests/test_agent_comparison.py)。

本次持久文件清单：

| 范围 | 文件 |
| --- | --- |
| 默认兼容入口 | `langgraph-worker/app/services/novel_research_agent.py`、`langgraph-worker/app/services/harness/webnovel_harness.py` |
| 自主执行与工具 | `langgraph-worker/app/services/harness/autonomous_research.py`、`langgraph-worker/app/services/harness/autonomous_tools.py`、`langgraph-worker/app/services/harness/autonomous_context.py` |
| 可重复对照 | `langgraph-worker/scripts/agent_comparison.py`、`langgraph-worker/scripts/run_agent_comparison.py`、`langgraph-worker/tests/fixtures/agent_comparison_v1.json` |
| 行为测试 | `langgraph-worker/tests/test_autonomous_research.py`、`langgraph-worker/tests/test_agent_comparison.py`、`langgraph-worker/tests/test_autonomous_context.py`、`langgraph-worker/tests/test_novel_research_agent.py` 的输出契约断言 |
| 共享领域方法 | `langgraph-worker/app/services/skills/packs/` 下的 `webnovel-opening-hook.md`、`webnovel-chapter-outline.md`、`webnovel-project-knowledge-qa.md` |
| Pi 评测接入 | `langgraph-worker/app/services/evaluation/pi_kernel.py`、`langgraph-worker/scripts/pi-eval/bridge.mjs`、同目录 `package.json`、`package-lock.json`、`.gitignore`、`langgraph-worker/tests/test_pi_evaluation.py` |
| 完整 Pi 会话 | `langgraph-worker/app/services/evaluation/pi_session.py`、`langgraph-worker/scripts/pi-eval/session-bridge.mjs`、`langgraph-worker/tests/test_pi_session.py` |
| DSH 会话及排名对照 | `langgraph-worker/app/services/evaluation/dsh_session.py`、`ranking_comparison.py`、`langgraph-worker/scripts/dsh-eval/` 的 bridge、固定依赖及 ignore 文件、`langgraph-worker/tests/test_dsh_session.py`、`test_ranking_comparison.py`、`fixtures/rank_comparison_v1.json` |
| 历史榜单修正 | `langgraph-worker/app/services/rank_comparison.py`、`langgraph-worker/app/services/intents/intent_router.py`、`langgraph-worker/tests/test_historical_rank_comparison.py` |
| 文档与边界 | 本报告、`docs/README.md`、`docs/governance/catalog.json`、`.gitignore` |

## 重复执行

在 `langgraph-worker` 目录中，使用环境变量提供既有 Provider 凭据和端点配置：

```powershell
python -m pytest tests/test_autonomous_research.py tests/test_agent_comparison.py -q
python scripts/run_agent_comparison.py --model deepseek-flash --output ../.codex-research/autonomous-agent-20260928/comparison-new.json --max-calls 40 --output-tokens 2048
```

第二条命令会调用真实模型；需使用全新的输出路径。执行器使用内存检查点和合成 Backend，不连接生产 Backend 或 MCP。若本地网络将 Provider 域名解析成非公网地址，原有地址校验会阻止调用；不得将这类失败或旧流程降级回答计为模型效果。CLI 成功退出表示数据收集完成，不表示每轮内容通过检查。

Pi 评测需要 Node >=22.19。在 `langgraph-worker/scripts/pi-eval` 执行 `npm ci --ignore-scripts --no-audit --no-fund`；随后回到 `langgraph-worker`：

```powershell
$env:NOVAL_PI_NODE = (Get-Command node).Source
python -m pytest tests/test_pi_evaluation.py tests/test_agent_comparison.py tests/test_autonomous_research.py -q
python scripts/run_agent_comparison.py --model deepseek-flash --runtimes workflow autonomous pi --repeats 2 --pi-node $env:NOVAL_PI_NODE --max-calls 64 --output-tokens 1536 --answer-length-hint "回答长度控制在350—500个汉字左右；保留本题要求的所有场景、行动和引用，直接给出内容。" --output ../.codex-research/autonomous-agent-20260928/comparison-pi-new.json
```

当前代码默认复用 Pi 进程，早期逐次启动的观测仅作为历史接入成本记录。`NOVAL_PI_NODE` 用于离线 SDK 测试，CLI 的 `--pi-node` 必须显式提供；启动前会校验 Node 与 SDK 版本。私有 JSON 包含逐轮答案、每次 Provider 尝试和 `piRuns` 的启动/复用计时。未安装 SDK 或未配置 Node 时，Pi 专项测试会跳过，不能把跳过当成通过。

仅比较上下文策略（第二条同样会调用真实模型，需新输出路径）：

```powershell
python -m pytest tests/test_autonomous_context.py tests/test_pi_evaluation.py tests/test_agent_comparison.py tests/test_autonomous_research.py -q
python scripts/run_agent_comparison.py --model deepseek-flash --runtimes pi pi_context --repeats 2 --pi-node $env:NOVAL_PI_NODE --max-calls 40 --output-tokens 1536 --answer-length-hint "回答长度控制在350—500个汉字左右；保留本题要求的所有场景、行动和引用，直接给出内容。" --output ../.codex-research/autonomous-agent-20260928/comparison-context-new.json
```

完整会话/审稿对照（第二条调用真实模型，必须使用新输出路径）：

```powershell
python -m pytest tests/test_pi_session.py tests/test_pi_evaluation.py tests/test_autonomous_context.py tests/test_autonomous_research.py tests/test_agent_comparison.py -q
python scripts/run_agent_comparison.py --model deepseek-flash --runtimes pi_full pi_full_review --repeats 1 --pi-node $env:NOVAL_PI_NODE --max-calls 27 --output-tokens 1536 --answer-length-hint "回答长度控制在350—500个汉字左右；保留本题要求的所有场景、行动和引用，直接给出内容。" --output ../.codex-research/autonomous-agent-20260928/comparison-full-session-new.json
```

报告 `schemaVersion=4`，`piRuns` 记录会话复用、原生技能数、实际审稿次数/状态、压缩次数和模型调用用途。完整会话文件保存在结果 JSON 同级的 `*-sessions` 私有目录。`reviewPolicy=required_before_final` 表示启用审稿交付条件；首版只有可选工具，不应与修正版混同。

## 边界与后续方向

已交付的是可运行、可重复比较的本地实验，包含显式启用的历史快照、完整 Pi/DSH 会话、可选审稿，以及三个共享领域包的修改。历史榜单查询和比较已在默认 workflow 的本地实现中修正，运行时的默认选择仍为 workflow。完整会话的工具历史写入私有评测 JSONL；本实验未实现进程重启后的会话恢复、生产会话存储/删除策略、完整生产证据治理迁移、作品写入操作或多作品引用范围，也未部署。

后续应扩大真实任务和重复样本，再决定哪些生产链路迁移到自主循环；创作侧仍需独立的语义约束、场景连续性及章节修订校验。榜单来源跨轮恢复和模型自主选择日期的可靠性也未解决。评测完整回答、失败记录及网络诊断保留为私有工作证据；本公开报告仅保留合成实验的汇总结论。
