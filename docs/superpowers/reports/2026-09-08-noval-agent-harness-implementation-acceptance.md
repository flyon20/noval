# Noval Agent Harness：工具实时进度与缓存保护实施验收

> 文档 ID：`noval-agent-harness-implementation-acceptance`
>
> 状态：`current`（生产发布、功能验收与上线后复核完成；效果 A/B 仍未完成）
>
> 发布边界：`repository`
>
> 最后审核：`2026-09-09`（补充零缓存只读排查）
>
> 负责人：`agent-runtime`

## 1. 交付结果与范围

工具执行过程中可以产生开始与终态进度，经 Worker、Backend 和前端现有过程区展示；持久 Run 与快速问答兼容入口都保留工具身份和快照。断线恢复能读取已保存的工具状态，未知结果不会被回答完成事件自动改成工具成功。

本次落实既有实施计划 `noval-agent-codex-harness-adoption` 的 M0–M4，以及 M5 的功能回归、浏览器和目标 MySQL 演练。真实模型质量、成本与缓存 A/B 尚未完成，不能把整份计划标为全部完成。设计依据是 [Codex Harness 与 SDK 拆解](2026-09-07-codex-harness-sdk-source-analysis.md) 与 [Learn Harness Engineering 方法拆解](2026-09-07-learn-harness-engineering-analysis.md)。继续使用 Noval 的 Spring Boot、LangGraph、授权、证据与工具账本，没有引入 Codex SDK 作为问答运行时依赖。

| 阶段 | 本地交付 | 验收结论 |
| --- | --- | --- |
| M0 缓存基线 | 实施前源码快照、真实请求编译器的合成 fixture、旧新 run/stream 对照 | 4 种配置、每种 3 次工具执行与 4 次模型请求；payload 序列、最终回答、token 数一致 |
| M1 事件契约 | version 1 工具 item、白名单、终态单向合并、64 项上限 | 三端 reducer、旧快照及未知版本兼容测试通过 |
| M2 工具实时进度 | 单一内核事件源、ledger 现场进度、图 custom 事件、三端透传与显示 | 门闩工具结束前可消费开始事件；正常用例不增加模型或工具调用 |
| M3 恢复与取消 | 请求冻结、显式关闭、同 cursor 快照、重试 attempt 隔离 | 取消、EOF、重连、错属事件、恢复漂移与未知结果用例通过 |
| M4 缓存诊断 | 补齐 9 个实际请求设置字段，增加诊断覆盖版本 | 能识别原先遗漏的设置变化；缓存 key 与请求编译保持不变 |
| M5 功能验收及发布材料 | 既有 intelligence/golden/evidence/compaction 回归、真实 Chrome、MySQL 8.0.46 迁移演练、发布与回退检查 | 功能、兼容性、部署及线上正向/否定路径验收完成；真实模型 A/B 和生产浏览器全链路计时仍未完成 |

最终隔离发布候选为 `7dfc074140c3c587097e571954d5ebe6d9ed2b4b`，父提交为前置配置发布 `b73a3a5e99226cbceb616891559c5b311dafcd71`，包含 40 个 Harness 差异文件。Nginx 修复及其测试已进入父提交。候选在独立工作树提交，主工作区的其他改动保留，未执行 push。旧候选 `9d13c658` 的应用源码与构建输入经 Git 对照与最终候选一致，构建产物可复用；最终源码包以新提交重新生成并校验。

## 2. 执行、进度与恢复契约

### 2.1 执行与显示分工

- `AgentKernel._run_events()` 是工具循环的单一执行源：`run()` 汇总它，工具分支 `stream()` 逐事件消费它。暂停消费者时不会另起生产任务继续执行工具。
- 工具分支保持原有 Provider `invoke` 传输；无工具分支保留原生 Provider 流。新增进度不意味着开放模型推理正文或提前提交答案。
- 主业务图的工具也能直接通过 `RunToolLedger` 执行，因此公共工具进度在 ledger 实际执行边界产生，再由 LangGraph `updates + custom` 传递。
- 图、Harness、内核及 Provider 的相关异步迭代器显式关闭；工具开始后的取消会在实际分发前再次检查。
- 最终答案继续经过现有 compose、review、EvidenceCommit 和提交路径。四个 intelligence 开关的源码默认值仍为 false。

### 2.2 公共工具 item

工具事件沿现有 `PROGRESS` envelope 传递。新增字段为 `schemaVersion: 1`、`itemId`、`itemType: tool`、`itemStatus` 和 `toolName`；保留兼容的 `phase/message`。`itemId` 是 `tool_` 加宿主产生的 32 位十六进制身份，不公开原工具幂等密钥。

| 约束 | 行为 |
| --- | --- |
| 状态 | running、completed、failed、cancelled、unknown；进入终态后不被迟到 running 或不同终态覆盖 |
| 身份 | 每次实际执行 attempt 一个 item；共享等待及结果复用不创建新 item；合法失败重试使用新 item |
| 发出时机 | 成功写入 TOOL_PREPARED 后发 running；提交或 unknown 处理后发终态 |
| 内容 | 固定消息、严格 ID/name 格式；不传工具参数、结果、自由错误正文、模型推理或 usage |
| 上限 | ledger 生产计数和恢复投影有 64 项上限；单次 attempt 最多开始与终态两个事件；两端快照保留最多 64 项 |
| 展示 | 现有过程区使用“检索资料”等用户标签；原始工具名和 item ID 不展示 |
| Run 终止 | 没有终态证据的 running item 变为 unknown，不推断工具已完成 |

Backend 为每个 item 使用 `tool-item:<id>:running` 与 `tool-item:<id>:terminal` 两种事件幂等键。合并重复事件时读取数据库实际保存的 payload，避免请求中的后到状态改写已保存终态。快速问答兼容入口使用相同白名单和键规则。

### 2.3 快照、检查点与中断

`ai_chat_run.tool_progress_json` 保存有界的 version 1 工具投影；回答、工具投影与 `snapshot_sequence_no` 在原有 fenced 事务中维护。SSE snapshot 和 Run DTO 增加 `toolItemsSchemaVersion/toolItems`。历史 `NULL` 视为空列表；旧快照缺少字段时，前端保留已有 item。前端沿用 Run/message 所有权、序号去重和缺口回放。

本次还修复了离线复现的恢复缺陷：连续失败重试原先共用 prepare/commit 事件幂等键，第二次已准备但未提交的 attempt 可能被第一次的失败终态遮住。新增 `executionAttemptId` 区分实际 attempt 的持久事件；原 semantic/reuse key 不变。恢复遇到较新 prepare 时清除旧 attempt 的终态投影，保留第二次 unknown，阻止盲目重放。

Provider 必须有合法终止事件才能写 MODEL_COMMITTED。正常结束但正文为空仍走原有一次 invoke 降级；真正无终止事件的 EOF 使用 `ProviderStreamIncompleteError`，内核不重复提交该流。部分输出后异常也不会产生成功 result。图层既有降级策略仍可独立生成并校验替代答案，这不代表原先不完整的模型调用成功。

Backend SSE 仍约每 **3 秒**轮询事件，单次订阅期限为 **30 秒**；订阅结束后，客户端应按游标续接仍在执行的 Run。浏览器订阅 EOF 与 Provider 缺少合法终止事件是两个不同边界。三个 Nginx 配置现已覆盖 durable Run 的 `/api/knowledge/chat-runs/{runId}/events/stream`，关闭该路由的代理缓冲。真实 Chrome 检查还暴露了消息对象的响应式引用缺口：数据快照已更新而 DOM 未更新；三个消息创建点改为保留 Vue reactive proxy，并补充新建与恢复 Run 的 DOM 回归。

隔离浏览器七项检查全部通过，合成事件到 DOM 的观测值为 12 ms，无外部请求或真实模型调用。这只能验证浏览器消费与渲染路径；不能作为生产 Backend 轮询、网络与浏览器串联后的到达时延。

## 3. 缓存保护与单项改进

### 3.1 保留的行为

- `noval-cache-v1` 的身份算法及用户/项目、用户、conversation、trace 优先级不变。
- 稳定前缀、消息角色/顺序、工具规范化、Provider `_build_payload` 和既有缓存策略编译不变。
- TTL、breakpoint、实际路由选择及故障切换门禁不因进度改造改变；没有启用新缓存策略、预热或清空 Redis。
- 公共 item、cursor、attempt 身份留在宿主元数据中，不写入模型 Prompt、工具 observation 或缓存亲和键。
- cache read/miss/write、显式零、未上报及派生 miss 的原有解析和展示保留；回放不触发新模型请求或重新累计 usage。

新旧源码对照使用实施前保存并核对 SHA-256 的 `AgentKernel`、`ProviderClient`，合成工具返回值和生产 `_build_payload`。固定三次不同参数的同名工具，每个配置分别执行旧 run、旧 stream、新 run、新 stream；比较包含字段顺序的最终 JSON 序列化、调用次数、终稿及 token 数。

| 合成 Profile | 每轮模型请求/工具执行 | 新旧 run/stream 对照 |
| --- | --- | --- |
| Responses / GPT-5.6 | 4 / 3 | 一致 |
| Chat Completions / GPT-4.1 | 4 / 3 | 一致 |
| Chat Completions / DeepSeek | 4 / 3 | 一致 |
| Responses / 未知模型与中继端点 | 4 / 3 | 一致 |

以上使用 mock，不访问这些服务。实施前快照、审计脚本与完整请求哈希留在本地私有研究目录；公开仓库的持续回归入口是下方 wire/cache 用例。其他 legacy allowlist、缓存能力及 usage 变体由既有 Provider/dispatch 测试覆盖。

### 3.2 requestSettingsVersion 2

原诊断指纹遗漏了已发送请求中的部分设置。现在额外覆盖：`temperature`、`max_tokens`、`max_completion_tokens`、`max_output_tokens`、`response_format`、`thinking`、`reasoning_effort`、`enable_thinking`、`tool_choice`。在合成 payload 中逐项改变这些字段，能检测设置变化；传输和宿主字段不进入该指纹。

`schemaVersion` 仍为 1，新增 `requestSettingsVersion: 2` 表示诊断覆盖范围。Backend 读取旧记录时把缺失值视为 1；直接比较不同覆盖版本返回 `diagnostic_version_changed`。扩展后的 hash 与 `surfaceGeneration` 可能不同，依赖设置指纹的 Redis 诊断分组也可能出现 `no_previous`。这是诊断基线变化，不能解释为 Provider 缓存失效或命中。

**这项收益是更准确地定位请求漂移。没有实测命中率、费用或回答质量提升，也没有据此调整实际缓存 key、模型参数或路由。**

## 4. 离线验收结果

| 检查 | 最终结果 | 范围 |
| --- | --- | --- |
| Worker | 640 passed，0 failed，0 skipped | 20 个相关文件；内核、Provider、账本、图/API、恢复、上下文、intelligence 与 golden suites |
| Backend | 153 passed | 主链路 72、兼容入口 41、初始化顺序 4、三种 Nginx 配置路由 36 |
| 最终基线配置复核 | 43 passed | Nginx 36、迁移顺序 4、资源配置 3；其中 40 项与 Backend 主集合重复，3 项为资源配置检查 |
| Frontend | 44 passed | 4 个文件：工具 reducer、SSE framing、composable 归属恢复、消息组件；包含响应式 DOM 回归 |
| Frontend 类型与构建 | 通过 | `vue-tsc` 与生产 Vite/PWA 构建 |
| 真实 Chrome | 7 项通过 | 开始态可见、刷新恢复、断线不重提、错属/重复事件、取消 unknown、移动端布局、无外部请求 |
| 目标 MySQL 8.0.46 | 6 项通过 | 生产同版本镜像、隔离网络与合成数据；缺列、升级、原数据/NULL/JSON 保留、重复执行、错误类型与过程清理 |
| 发布与回退脚本 | 17 项通过 | 中断恢复、目录身份、重复执行保护、新版活动 Run 阻止不兼容回退、语法检查 |
| Worker 运行时检查 | 12 项通过 | live kernel progress 与缓存设置；实际候选镜像内执行，无网络或 pytest 依赖；另通过既有 5 项 Provider/config 和 8 项 admission 检查 |
| 文档校验器回归 | 8 passed | 生命周期、链接、发布边界与 Phase23–31 迁移覆盖 |
| 文档与发布边界 | 通过 | 治理校验、3 份研究文档的 151 个链接/120 个文件行号目标、diff 空白检查；计划 private，报告逐文件放行 |
| 实施前/后 wire 审计 | 4 种配置全部一致 | 每种 3 次工具/4 次模型请求，旧新阻塞与流式对照 |

表中只统计最终相关用例，实施前基线和中途专项与最终集合重叠，不重复累计。流式 fixture 明确区分正常空回答与缺少终止事件的 EOF，分别验证原有降级和不完整流的失败语义。

Worker 仍有既有 `python_multipart` 依赖弃用提示；构建输出包含第三方 PURE 注释位置和大 chunk 提示。没有为消除提示修改构建阈值或无关依赖。

可复用命令如下。Worker 在 `langgraph-worker` 目录运行：

```powershell
python -m pytest tests/test_harness_live_progress.py tests/test_cache_settings_diagnostics.py tests/test_tool_progress.py tests/test_agent_kernel.py tests/test_agent_kernel_retry_failover.py tests/test_provider_client.py tests/test_provider_dispatch_scope.py tests/test_context_assembler.py tests/test_context_compaction.py tests/test_harness_tool_ledger.py tests/test_tool_call_loop.py tests/test_webnovel_harness.py tests/test_knowledge_api.py tests/test_agent_checkpoint_resume.py tests/test_harness_intelligence.py tests/test_novel_research_agent.py tests/test_golden_eval_runner.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_edge.py -q
```

Backend 在 `backend` 目录运行：

```powershell
mvn "-Dtest=LangGraphWorkerClientTest,KnowledgeChatRunServiceTest,KnowledgeChatRunEventServiceTest,KnowledgeChatRunPersistenceServiceTest,KnowledgeChatToolProgressTest,KnowledgeAiCacheContinuityServiceTest" test
mvn "-Dtest=KnowledgeChatApplicationServiceTest,KnowledgeChatServiceUnitTest" test
mvn "-Dtest=MySqlInitOrderTest,NginxSseRoutingTest,AgentResourcePolicyConfigTest" test
```

Frontend 在 `frontend` 目录运行：

```powershell
npm run test -- --run src/utils/__tests__/knowledgeToolProgress.spec.ts src/api/__tests__/knowledge.spec.ts src/composables/__tests__/useKnowledgeChat.spec.ts src/components/knowledge/__tests__/KnowledgeMessageBubble.spec.ts --maxWorkers=1 --no-file-parallelism
npm run type-check
npm run build
```

文档在仓库根运行：

```powershell
python -m unittest discover -s tools/docs -p test_validate_docs.py
python tools/docs/validate_docs.py
git diff --check
```

## 5. 发布、线上验收与剩余实验

1. 先执行 [Phase31 migration](../../../backend/sql/mysql/phase31-agent-tool-progress.sql)，再用 [verify SQL](../../../backend/sql/mysql/phase31-agent-tool-progress-verify.sql) 检查可空 JSON 列；无返回行表示核验通过。新库初始化和 Compose `schema-migrate` 已登记，Backend 沿用 `service_completed_successfully` 依赖。
2. 先部署兼容读端 Backend/frontend，再部署产生新工具事件的 Worker。旧事件和空投影可以读取，新字段为增量兼容。
3. 观察各 Run 的 item 归属、终态、unknown、快照恢复和实际 Provider usage；浏览器只断开订阅时保持原 durable Run 生命周期。
4. 回退应用时保留新增列、Run/Event、检查点和未知结果。新版 attempt 边界不能交给旧 Worker 盲目恢复；先让活动 Run 在兼容执行器上完成，或按既有取消及 unknown 处置流程处理。
5. 诊断版本升级造成新分组时等待自然形成新基线；不通过清缓存或更换缓存亲和键处理。

目标 MySQL 8.0.46 的隔离 DDL 演练已完成，六项检查全部通过，临时容器已停止。演练只使用合成表和数据。前置配置发布及其四路测量结束后，本次生产 `prepare/build/cutover/verify` 均退出 0，Phase31 已执行并通过 schema 核验。

### 5.1 生产发布证据

- 发布版本：`7dfc074140c3c587097e571954d5ebe6d9ed2b4b`。源码、Backend JAR、Worker 和前端文件均与清单一致；公网页面引用新构建，Service Worker 文件一致。
- 迁移前完成 MySQL 一致性备份，保留旧应用目录与旧镜像。先启动兼容读端 Backend，再启动 Worker，最后恢复 Nginx；没有执行应用回退或数据库数据恢复。
- 源站和公网健康、公开鉴权配置、页面均返回 200；应用容器运行正常，启动严重错误计数为 0。
- 环境文件、容器环境及 CPU/内存/端口设置核对一致；前置发布的并发配置保留。非目标容器身份与重启状态保持一致，未清理缓存。
- 发布前实际读取的四个 intelligence 开关均为 false。独立验证时活动问答、outbox 与导入任务均为 0。
- 线上共提交四个独立合成问题：首次 fast/deep 两问、修正验证器后的一个 deep 续接场景，以及一个使用现有榜单数据的标准正向场景。所有测试项目和会话均已归档；首次 deep 的取消及失败记录保留。没有自动重复提交同一 Run。

### 5.2 线上验收器修复

首次验证器把 30 秒 SSE 订阅 EOF 当作整个 Run 完成；当 deep 的真实状态仍为 `RUNNING` 时，它触发失败并由清理流程取消测试 Run。只读回查确认该 Run 最终为 `CANCELLED`，已完成的工具投影仍保留；没有将这次检查改写为成功。

修复只涉及私有验收脚本：按 `afterSequence` 重连续读同一个 Run，合并各段工具状态，直到 Run 到达终态才检查最终答案和快照。新增回归验证 EOF 后只执行 GET 续接、不会重复 POST，连同归档归属、终态和 usage 缺失语义共六项自检在本地及服务器均通过。补验仅提交一个独立 deep 问题，不恢复或重放已取消 Run 的未知调用。

首次取消的 deep 没有完整的最终 Provider usage，因此这些样本不能用于计算完整验收成本，更不能据此宣称缓存或费用改善。

### 5.3 最终线上结果

| 场景 | 实际结果 | 可证明的范围 |
| --- | --- | --- |
| 初始 fast | Run `ANSWERED`，业务 `insufficient_evidence`；1 个工具 completed，cursor 20，快照一致 | 工具进度、终态、持久化与尾部回放；这是证据不足路径，不是正向回答质量结论 |
| 初始 deep | 被错误验证器取消，最终 `CANCELLED`，已完成工具仍保留 | 失败证据和清理可追溯；不算通过，最终 usage 不完整 |
| 修正后的 deep | Run `ANSWERED`，业务 `insufficient_evidence`；同 Run 续接 2 次，cursor 20，快照一致 | 跨 30 秒订阅周期继续执行，不重提问题；仍属于证据不足路径 |
| 标准榜单比较 deep | Run `ANSWERED`，业务 `answered`；1,270 字，5 次模型调用及各自提交，1 个工具 completed，cursor 35 | 正向回答完成；同 Run 续接 5 次，事件与持久投影一致，末尾回放通过 |

标准场景耗时 179.29 秒，从订阅开始到第一个工具事件为 33.58 秒，到首个答案 delta 为 175.86 秒。这里测量的是服务端 HTTP 客户端经过 Nginx 消费 SSE 的时刻，不是生产浏览器渲染时延，也不是单个工具的执行时长。

四个 Harness 合成验收请求均显式使用 `limits.timeoutMillis=420000`；前置四并发测量没有超时覆盖值。Worker 源码默认值及 2026-09-09 只读查询的实际默认值为 `30000`。这与前端 SSE 的 30 秒订阅期限属于不同超时边界。上述正向样例验证了覆盖值下的功能，不能据此宣称默认超时在相同负载下也通过，更不能把两批结果当作固定条件的性能或缓存 A/B。

该场景的五次调用均有 `usageReported=true` 和 `cacheUsageReported=true`，Provider usage 合计上报输入 106,660、缓存读取 87,353、输出 5,220 tokens。缓存读取数字只从每次调用的一份 usage 取值，没有与顶层别名重复相加。前两个完成的证据不足样例分别明确上报缓存读取为 0；零值与未上报继续区分。

这些上报证明线上仍能接收并保留缓存读取信息，不能证明缓存命中率或费用较上线前提高。没有执行受控 A/B，也未独立核验 Provider 内部缓存；取消样例的缺失 usage 不按 0 补齐。四个 intelligence 开关在测试前后保持 false。

全部测试结束后再次执行只读生产复核：健康接口和页面 200，源码/镜像/静态资源一致，Phase31 正确，环境与资源设置保留，非目标容器未变，严重错误计数 0，活动问答/outbox/导入计数均为 0。**本次发布与功能验收完成；真实质量/成本/缓存 A/B 和生产浏览器全链路计时保留为未完成实验。**

本次发布范围是工具进度、恢复边界修复和请求漂移诊断，离线 wire 审计证明实际请求保持不变。真实 Provider 缓存 A/B、固定条件质量/成本评测及生产浏览器全链路时延继续保留为未完成实验；四个 intelligence 开关不随本次发布启用。实验应冻结模型/Profile、推理参数、题目/证据、会话和预算，一次只改一个机制；以实际 usage 和每个完成任务的成本评估，不从诊断 hash、离线 fixture 或两次上线冒烟推断真实收益。

### 5.4 零缓存只读复核（2026-09-09）

针对追加的计费记录，只读核对八个已执行的合成 Run，取得 41 个模型检查点事件、24 条最终调用记录及冻结请求的超时设置。没有重发问题、修改产品代码或生产配置、清理缓存，也没有重新部署。20 条用户提供的计量中，13 条能以输入/缓存/输出三元组与不超过 2 秒的记录时间差唯一关联；其余 7 条缺少可唯一关联的 usage 或上游 request ID，保留为未确认。

| 观察 | 可支持的判断 | 不支持的判断 |
| --- | --- | --- |
| 三条约 4.4 万至 4.8 万输入的请求明确上报 0；其路由、亲和、模型、工具、设置与 instructions 指纹一致 | 已匹配样本未显示缓存 key 丢失或本系统配置路由漂移；它们发生在 Harness 切换之前 | 配置路由指纹不包含网关内部渠道、缓存节点或凭据轮换代次，不能据此排除上游变化 |
| 三条请求的前 3 个 input item 链相同，第 4 项起不同；完整相同块对应的共同开头约为 4,845 字符 | 宪法及共享规则保持；这是按完整消息确认的下界，第 4 项内部仍可能有共享片段 | 已测得精确共同 token 数、全部大输入都应命中，或相同 key 必然命中 |
| `runtime_policy` 约 5.8 万至 6 万字符，且 `cachePrefixChars` 包含动态 system 块 | 总输入量和全部开头 system 消息长度都不是稳定可复用 token 数 | 字符估算可以替代实际 tokenizer、最小缓存门槛或实际命中量 |
| 前置四并发有 17 条调用：4 条成功且 usage 完整、12 条 `ReadTimeout`、1 条流式失败 | 该批负载有明确的超时及用量缺口；失败调用的未上报用量必须保留为未知 | 失败调用没有发生上游计费，或 13 次缺失 usage 都等于真实零缓存 |
| 后续五条明确缓存上报的比例均约为 81.9%；回答修订保留了原 7 项输入链并扩展至 9 项 | 本系统收到并保留了该比例的上报；修订链没有重写原输入 | 已独立证实上游内部 KV 命中，或 Harness 改造提高了缓存收益 |

现有 usage 解析只读取上游命中字段，未发现把正常命中归零或按固定比例生成命中量的证据；隔离发布版本的四项 usage 回归通过。当前兼容网关模型别名不能直接作为官方模型缓存门槛、保留时长或计量语义已获验证的依据。已请求网关内部 request ID、渠道与 usage 口径作为补充证据，具体上游零命中原因仍未确定。

后续改进按证据排序：先单独核实默认读取超时和推理等待预算；再评估动态运行策略的冗余与必要字段；有网关渠道证据后再评估缓存策略。它们尚未实施，不从一次样例直接提高全局超时、增加重试、填充 Prompt 或改变缓存身份。详细计量对应表及取证脚本保留为私有工作证据；通用方法见 [缓存连续性排查](../findings/2026-08-16-deepseek-harness-review/06-redis-cache-continuity-shadow-implementation.md#8-零缓存排查边界)。

## 6. 本次代码文件清单

以下是本次实现范围，不包含工作区原有的其他改动。测试文件与新迁移均随实现保留。

| 层 | 修改/新增文件 |
| --- | --- |
| Worker 执行 | [agent_kernel.py](../../../langgraph-worker/app/services/harness/agent_kernel.py)、[tool_ledger.py](../../../langgraph-worker/app/services/harness/tool_ledger.py)、新增 [tool_progress.py](../../../langgraph-worker/app/services/harness/tool_progress.py)、[webnovel_harness.py](../../../langgraph-worker/app/services/harness/webnovel_harness.py)、[novel_research_agent.py](../../../langgraph-worker/app/services/novel_research_agent.py) |
| Worker Provider | [provider_client.py](../../../langgraph-worker/app/services/provider_client.py)、新增 [provider_errors.py](../../../langgraph-worker/app/services/provider_errors.py) |
| Worker 测试 | 新增 [test_harness_live_progress.py](../../../langgraph-worker/tests/test_harness_live_progress.py)、[test_tool_progress.py](../../../langgraph-worker/tests/test_tool_progress.py)、[test_cache_settings_diagnostics.py](../../../langgraph-worker/tests/test_cache_settings_diagnostics.py)；更新 [test_novel_research_agent.py](../../../langgraph-worker/tests/test_novel_research_agent.py) |
| Backend 桥接 | [LangGraphWorkerClient.java](../../../backend/src/main/java/com/novelanalyzer/modules/analysis/client/LangGraphWorkerClient.java)、[KnowledgeChatApplicationService.java](../../../backend/src/main/java/com/novelanalyzer/modules/knowledge/service/KnowledgeChatApplicationService.java)、[KnowledgeChatRunService.java](../../../backend/src/main/java/com/novelanalyzer/modules/knowledge/service/KnowledgeChatRunService.java) |
| Backend 投影 | 新增 [KnowledgeChatToolProgress.java](../../../backend/src/main/java/com/novelanalyzer/modules/knowledge/service/KnowledgeChatToolProgress.java)、[KnowledgeChatPersistenceService.java](../../../backend/src/main/java/com/novelanalyzer/modules/knowledge/service/KnowledgeChatPersistenceService.java)、[KnowledgeChatRunEventStreamService.java](../../../backend/src/main/java/com/novelanalyzer/modules/knowledge/service/KnowledgeChatRunEventStreamService.java)、[KnowledgeChatRunVO.java](../../../backend/src/main/java/com/novelanalyzer/modules/knowledge/vo/KnowledgeChatRunVO.java) |
| Backend 诊断 | [KnowledgeAiCacheContinuityService.java](../../../backend/src/main/java/com/novelanalyzer/modules/knowledge/service/KnowledgeAiCacheContinuityService.java) |
| Backend 测试 | [LangGraphWorkerClientTest](../../../backend/src/test/java/com/novelanalyzer/modules/analysis/client/LangGraphWorkerClientTest.java)、[ApplicationServiceTest](../../../backend/src/test/java/com/novelanalyzer/modules/knowledge/KnowledgeChatApplicationServiceTest.java)、[RunServiceTest](../../../backend/src/test/java/com/novelanalyzer/modules/knowledge/KnowledgeChatRunServiceTest.java)、[RunPersistenceServiceTest](../../../backend/src/test/java/com/novelanalyzer/modules/knowledge/KnowledgeChatRunPersistenceServiceTest.java)、新增 [ToolProgressTest](../../../backend/src/test/java/com/novelanalyzer/modules/knowledge/KnowledgeChatToolProgressTest.java)、[CacheContinuityServiceTest](../../../backend/src/test/java/com/novelanalyzer/modules/knowledge/service/KnowledgeAiCacheContinuityServiceTest.java)、[MySqlInitOrderTest](../../../backend/src/test/java/com/novelanalyzer/sql/MySqlInitOrderTest.java) |
| 数据库与启动 | 新增 Phase31 migration/verify、[H2 等价迁移](../../../backend/src/test/resources/sql/phase31-agent-tool-progress-h2.sql)，更新 [H2 Run fixture](../../../backend/src/test/resources/sql/phase18-agent-harness-conversation-rag-h2.sql)、[初始化脚本](../../../docker/mysql/00-initialize-noval.sh)、[docker-compose.yml](../../../docker-compose.yml) |
| 前端 | [knowledge.ts 类型](../../../frontend/src/types/knowledge.ts)、新增 [knowledgeToolProgress.ts](../../../frontend/src/utils/knowledgeToolProgress.ts)、[useKnowledgeChat.ts](../../../frontend/src/composables/useKnowledgeChat.ts)、[KnowledgeMessageBubble.vue](../../../frontend/src/components/knowledge/KnowledgeMessageBubble.vue) |
| 前端测试 | [工具 reducer](../../../frontend/src/utils/__tests__/knowledgeToolProgress.spec.ts)、[SSE API](../../../frontend/src/api/__tests__/knowledge.spec.ts)、[composable](../../../frontend/src/composables/__tests__/useKnowledgeChat.spec.ts)、[消息组件](../../../frontend/src/components/knowledge/__tests__/KnowledgeMessageBubble.spec.ts) |
| SSE 路由（已进入父提交） | [Docker Nginx 模板](../../../docker/nginx/default.conf.template)、[独立配置示例](../../../tools/nginx/noval.cloudflare.conf.example)、[安装脚本](../../../tools/nginx/install-noval-nginx.sh)、[NginxSseRoutingTest](../../../backend/src/test/java/com/novelanalyzer/sql/NginxSseRoutingTest.java) |
| 文档校验 | [validate_docs.py](../../../tools/docs/validate_docs.py)、[test_validate_docs.py](../../../tools/docs/test_validate_docs.py)，把既有迁移覆盖扩展到 Phase31 |

当前架构和诊断契约分别维护于 [Noval AI 问答当前架构](../findings/2026-08-16-deepseek-harness-review/01-noval-ai-qa-current-architecture.md) 与 [Redis continuity 文档](../findings/2026-08-16-deepseek-harness-review/06-redis-cache-continuity-shadow-implementation.md)。实施计划、运行快照和工作记录继续保持 private；本报告经逐文件检查，不包含真实凭据、用户正文、完整提示词或原始生产日志。
