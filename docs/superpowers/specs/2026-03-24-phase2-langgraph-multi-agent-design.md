# 二期 LangGraph 多 Agent 架构设计说明

## 1. 文档目的

本文档用于确认网文项目二期 AI 调用链路的最终技术方案，并作为后续实施、验收和资源评估的统一基线。

二期目标不是重做产品形态，而是在尽量保持现有业务接口、提示词配置方式和前端交互习惯不变的前提下，完成以下升级：

- 将当前以 `LangChain4j` 为中心的 AI 调用主链路升级为基于官方 `LangGraph` 的多 agent 编排
- 保持“提示词配置 -> 结构化 JSON -> 入库 -> 展示”的产品思路不变
- 将单书分析下的 `deconstruct`、`structure`、`plot` 三项能力与趋势分析 `trend_theme` 拆为四个独立 agent
- 支持并行、异步、可取消、流式回传
- 明确 `2 核 4G` Docker 化部署下的资源预算、并发上限和可行性边界

## 2. 当前现状

### 2.1 当前已具备的能力

- 后端主服务为 Spring Boot，当前 AI 主链路集中在 [AiGatewayService.java](D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java) 和 [AnalysisService.java](D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java)
- `prompt_config` 已支持 `output_json_schema`、`output_example_json`、`post_process_type`、`parse_config_json`
- 单书分析和趋势分析公网接口已分离：
  - `/api/analysis/deconstruct[/stream]`
  - `/api/analysis/structure[/stream]`
  - `/api/analysis/plot[/stream]`
  - `/api/analysis/trend[/stream]`
- 前端单书分析已是三个独立面板，可并发请求，不需要为二期推翻前端产品形态
- `analysis_result.result_json` 已经可以承接结构化分析结果

### 2.2 当前主要问题

- `LangChain4j` 目前承担了主要模型调用逻辑，但不适合继续扩展成正式的多 agent 状态图编排中心
- `AnalysisService` 当前流式执行器使用 `SimpleAsyncTaskExecutor`，属于无界线程模型，不适合 `2 核 4G`
- 长文本分析虽然已有 chunk 能力，但编排仍偏过程式，后续扩展取消、并发配额、指标采集会越来越重
- 趋势分析仍然是单次大调用，不是图式编排
- 当前还没有真正面向 Docker 部署的资源硬上限

## 3. 技术决策结论

### 3.1 最终结论

二期采用以下架构：

- `Java Spring Boot` 继续作为主业务服务
- 新增一个最小化 `Python LangGraph worker`
- 四个 agent 的主编排统一放到 `LangGraph`
- `LangChain4j` 不再承担 AI 主链路，只保留迁移期兼容与回滚能力

这意味着二期不是“只剩 LangGraph”，而是：

- Java 负责业务
- LangGraph 负责 AI 编排
- 模型供应商负责推理

### 3.2 为什么不采用“双编排”

可以实现“Java 仍保留 `LangChain4j` 主调用，同时 Python 再接官方 `LangGraph`”，但不建议这样做。

如果两边都参与主编排，会重复维护以下逻辑：

- 流式处理
- 取消逻辑
- 重试策略
- JSON schema 校验
- token 统计
- 超时控制
- trace 和日志定位

对 `2 核 4G` 目标机器而言，这种双编排会同时抬高：

- 代码复杂度
- 内存占用
- 故障排查成本
- 回归测试成本

因此推荐的正式方案是：

- `LangChain4j` 只保留为迁移期开关和回滚链路
- 官方 `LangGraph` 统一承担四个 agent 的编排主链路

## 4. 目标架构

```text
Frontend
   ->
Java Backend
   - 鉴权
   - 参数校验
   - 查库 / 查缓存 / 入库
   - 组织输入数据
   - 调用 LangGraph worker
   - SSE 转发
   - 停止 / 取消
   ->
Python LangGraph Worker
   - deconstruct_agent
   - structure_agent
   - plot_agent
   - trend_agent
   - graph 编排
   - 结构化 JSON 校验
   - 并发控制
   - 流式输出
   ->
DeepSeek API
```

### 4.1 边界划分

Java Backend 负责：

- 对外接口保持稳定
- 用户权限与请求校验
- 从 MySQL / Redis 获取书籍、章节、榜单、快照数据
- 将原始业务数据整理为 worker 所需输入
- 与 worker 建立内部 HTTP/SSE 通信
- 将结果写回 `analysis_result`
- 向前端转发流式事件
- 响应“停止按钮”产生的取消信号

LangGraph Worker 负责：

- 四类分析任务的 graph 定义
- 节点间状态管理
- chunk 策略
- agent 并行
- 结构化 JSON 输出
- 模型重试、超时与失败分类
- 输出运行指标

DeepSeek 负责：

- 实际推理
- 流式增量 token 输出
- JSON 输出模式
- 上下文缓存命中

## 5. 四个 Agent 的拆分方式

二期建议保持为四个主 agent，不再拆成更多常驻服务：

- `deconstruct_agent`
- `structure_agent`
- `plot_agent`
- `trend_agent`

### 5.1 为什么只保留四个主 agent

- 与当前产品能力完全对应，前端和数据库语义稳定
- 有利于缓存键和历史复用逻辑沿用现有设计
- 避免把“章节切分”“摘要”“词云”等内部步骤误拆成常驻进程，造成内存浪费

### 5.2 graph 内部节点建议

单书三类分析共用一套图骨架：

1. `prepare_input`
2. `estimate_tokens`
3. `choose_direct_or_chunk`
4. `parallel_chunk_analyze`
5. `reduce_chunk_json`
6. `optional_summary_synthesis`
7. `validate_output_json`
8. `emit_metrics_and_finish`

趋势分析使用独立图：

1. `load_board_context`
2. `derive_snapshot_features`
3. `build_trend_prompt_payload`
4. `invoke_model_json_mode`
5. `validate_output_json`
6. `emit_metrics_and_finish`

说明：

- chunk、归并、统计特征提取应尽量代码化，减少“再喂大模型做二次大总结”的 token 浪费
- `trend_agent` 的词云、榜单统计、快照对比应尽量先在代码中产出候选数据，再交给模型做解释层补充

## 6. LangChain4j 与 LangGraph 的职责分工

| 模块 | LangChain4j | LangGraph | 最终方案 |
|---|---|---|---|
| Java 主业务接口层 | 不作为核心 | 不直接参与 | 保持 Spring Boot 自己负责 |
| 单书三项分析编排 | 不再主用 | 主用 | 迁移到 LangGraph |
| 趋势分析编排 | 不再主用 | 主用 | 迁移到 LangGraph |
| 主模型调用 | 仅保留回滚能力 | 主用 | 统一交给 worker |
| JSON 结构化输出 | 逐步退出 | 主用 | worker 内统一做 schema 校验 |
| SSE 对前端输出 | Java 继续负责 | 通过内部流返回 Java | 不改前端公网协议 |
| 回滚 / 灰度 | 保留 | 保留 | 双栈共存一段时间 |

### 6.1 迁移期策略

迁移期引入运行模式开关：

- `analysis.runtime.mode=legacy`
- `analysis.runtime.mode=langgraph`

其中：

- `legacy` 表示沿用当前 `LangChain4j` 主链路
- `langgraph` 表示切换到 Python worker

验收通过前保留双栈，验收通过后再逐步删除 legacy 主链路

## 7. Java 与 Worker 的内部契约

### 7.1 请求模型

Java 传给 worker 的统一请求建议至少包含：

- `traceId`
- `agentType`
- `stream`
- `taskId`
- `promptConfig`
- `sourcePayload`
- `limits`
- `contextMeta`

其中：

- `agentType` 取值：`deconstruct`、`structure`、`plot`、`trend_theme`
- `promptConfig` 包含提示词正文、schema、example、模型名、温度、输出上限
- `sourcePayload` 是已整理好的业务输入，不让 worker 再去查数据库
- `limits` 用于声明本次运行的 token、并发、超时约束

### 7.2 流式事件模型

worker 输出给 Java 的事件统一为：

- `start`
- `progress`
- `delta`
- `done`
- `error`
- `metrics`

Java 再把这些事件转成现有前端已支持的 SSE 语义。

### 7.3 取消机制

每次分析生成唯一 `taskId`。

- 前端点某个面板的“停止”按钮
- Java 记录取消状态并向 worker 发取消信号
- worker 在节点边界和流式回调中检查取消标记
- 只取消当前面板，不影响其他面板

## 8. 结构化 JSON 策略

二期继续沿用当前产品思路：

- 提示词配置在后台维护
- 模型返回严格结构化 JSON
- Java 直接入库到 `analysis_result.result_json`
- 前端优先展示结构化字段，不再依赖对长文本做脆弱二次解析

### 8.1 单书分析统一 JSON 外壳

三类单书分析建议统一保留这些公共字段：

- `analysisType`
- `summary`
- `detailContent`
- `evidence`
- `meta`

各分析类型再补专属字段。

### 8.2 趋势分析固定字段

趋势分析至少固定这些字段：

- `analysisType`
- `platform`
- `channelCode`
- `boardCode`
- `boardName`
- `historicalWordCloud`
- `themeTable`
- `hotBooks`
- `insightCards`
- `snapshotComparisons`
- `summary`
- `trendPreview`
- `detailContent`
- `historyAnalysisCount`
- `meta`

### 8.3 prompt_config 的沿用原则

二期不新建一套平行提示词表，继续复用：

- `output_json_schema`
- `output_example_json`
- `post_process_type`
- `parse_config_json`

Graph 级运行参数不塞进 `prompt_config`，统一放系统配置和 worker 环境变量，避免配置表继续膨胀。

## 9. 并行、流式与停止语义

### 9.1 并行原则

- 用户点击哪个面板，就只启动哪个面板
- 用户不点击，不自动运行
- 用户连续点三个面板，就三个都立刻开始
- 用户点击“停止”，只停止对应面板

### 9.2 运行层的有界并发

允许“面板并行”，但底层必须“有界并发”。

推荐运行上限：

- 同一用户同时活跃 graph run：最多 `4`
- worker 全局同时活跃 LLM 调用：最多 `4`
- 单个长文 run 的 chunk 并发：默认 `2`
- 当同时存在两个及以上长文 run 时，单 run chunk 并发自动降为 `1`

这样可以做到：

- 用户看到的是“点了就开始”
- 服务端实际执行不会因为长文本和多个面板同时冲进来而爆内存

### 9.3 流式策略

必须做到两件事：

1. 请求进入后立刻返回 `start` / `progress`
2. 一旦收到模型首个流式 token，立即向前端透传

这样可以显著缩短“前端空白等待”的体感时间。

## 10. Token 优化方案

二期 token 优化原则是“能代码算的先代码算，必须让模型做的再让模型做”。

### 10.1 固定优化项

1. 默认优先使用 `deepseek-chat` 的 JSON 输出模式，不默认上高成本推理模型
2. 长文本分析走“chunk 输出紧凑 JSON -> 代码 reduce -> 最后小总结”的路径
3. 趋势分析先做榜单统计、快照差分、题材候选词提炼，再交给模型解释
4. 提示词前缀、schema、示例 JSON 尽量稳定，提升上下文缓存命中率
5. 控制 `detailContent` 的生成长度，前端默认只展示预览

### 10.2 不推荐的做法

以下做法会显著增加 token 消耗，不建议保留：

- 把整本书大段原文反复喂给多个 agent 做重复总结
- chunk 结束后再把所有 chunk 的自然语言长文拼接回去做一次“大总结”
- 趋势页把快照原始榜单全文直接整包喂模型

## 11. 资源与内存预算

本节预算分为三层：

- 本机当前已观测的进程量级
- 目标 Linux Docker 部署的建议容器硬上限
- 加上 Docker 运行时与操作系统保留后的整体可行性判断

### 11.1 当前本机已观测到的进程量级

以下为当前 Windows 开发环境实测到的主要进程私有内存量级，仅用于确认数量级：

| 进程 | 本机观测值 |
|---|---:|
| Java 后端 | 355 MB |
| Python crawler | 478 MB |
| MySQL | 515 MB |

说明：

- 这些是宿主进程观测值，不是 Docker 容器值
- 当前环境中未能直接获取 `docker stats`，因为 Docker CLI 不在 PATH 中
- 因此下面的 Docker 开销预算属于基于 Linux 容器化部署经验的预算值，不是本机实测值

### 11.2 Docker 化部署需要额外考虑的开销

在目标 Linux 服务器上，除了应用自身进程内存，还要预留：

- `dockerd` / `containerd` / shim 进程开销
- 容器日志缓冲
- bridge 网络与 NAT 维护
- 文件系统 overlay 元数据
- 健康检查、连接池、序列化缓冲带来的瞬时峰值

对本项目这种 5 个容器规模的部署，建议额外预留：

- Docker 运行时与容器管理总开销：`120 MB - 220 MB`

这部分是整机预算，不是单容器预算。

### 11.3 推荐的容器硬上限

二期正式部署建议控制为：

| 容器 | 推荐硬上限 | 说明 |
|---|---:|---|
| `backend` | 768 MB | 配合 `-Xms256m -Xmx768m` |
| `langgraph-worker` | 384 MB | 单进程、单 worker，不开多进程 |
| `crawler` | 256 MB | 只负责采集，不做模型推理 |
| `mysql` | 640 MB | 需配合压低 buffer pool |
| `redis` | 96 MB | 主要承担缓存与令牌黑名单 |

容器上限合计：

- `2144 MB`

### 11.4 整机预算

按 Linux `2 核 4G` 服务器估算：

| 项目 | 预算 |
|---|---:|
| 应用容器硬上限合计 | 2144 MB |
| Docker 运行时开销 | 120 - 220 MB |
| Linux 系统基础保留 | 500 - 700 MB |
| 文件缓存 / 网络抖动 / 瞬时峰值缓冲 | 300 - 450 MB |

整机预算区间：

- 偏理想：`3064 MB`
- 偏保守：`3514 MB`

结论：

- 在 Linux Docker 环境下，`2 核 4G` 仍然可行
- 但前提是必须严格限制线程池、容器内存、chunk 并发、Uvicorn worker 数
- 如果不设上限，二期会从“可跑”迅速变成“偶发 OOM 或频繁抖动”

### 11.5 为什么不建议拆成四个独立 Python 服务

如果把四个 agent 拆成四个常驻 Python 容器，而不是一个 worker 内部四张 graph：

- 每个容器都要单独维护解释器、依赖、网络、日志和健康检查
- 容器管理开销和空闲内存会显著上升

保守估计会额外多出：

- `300 MB - 600 MB`

这会直接压缩 `2 核 4G` 的可用余量，因此二期不建议这样拆。

### 11.6 关于 Docker Desktop 的特别说明

如果在 Windows 上使用 Docker Desktop 或 WSL2 做长期驻留部署，不能直接套用本节预算。

原因是：

- Docker Desktop / WSL2 本身会引入额外虚拟化开销
- 这部分开销通常明显高于 Linux 原生 Docker

因此：

- 预算基线应以 Linux 服务器部署为准
- Windows Docker Desktop 只适合开发验证，不适合拿来判定 `2 核 4G` 生产可行性

### 11.7 方案间的资源对比

本节比较的是“在当前项目能力目标不变”的前提下，不同 AI 技术路线的资源差异。

先给结论：

- 如果只看内存最省，通常是“Java 里直接用 `LangChain4j`，不额外起 Python worker”
- 如果只在 Python 里做 AI 框架执行，那么“直接用 `LangGraph` 做主编排”通常比“用 Python `LangChain` agent 抽象来做多 agent”更省或至少不更重
- 当前推荐方案 `Java Backend + Python LangGraph worker` 不是最省内存的方案，但它是在“官方 LangGraph、可控多 agent、工程可维护性”之间最平衡的方案

#### 11.7.1 Python LangChain 与 Python LangGraph

这两者不能只按包名做表面对比，必须先区分用法。

如果只是“Python 里调用一个模型”：

- 最轻的是直接用 provider SDK
- 其次是 `LangChain` 的模型封装
- `LangGraph` 反而不是这类单调用场景的首选

但如果目标是本项目二期这种“4 个 agent、并行、流式、可取消、状态图控制”：

- `LangChain` 官方 agent 本身建立在 `LangGraph` 之上
- 因此在同样做多 agent 编排时，`Python LangChain agent` 一般不会比直接用 `LangGraph` 更轻
- `LangChain` agent 抽象通常还会多一层通用封装、状态包装和中间对象

所以在本项目这种多 agent 场景下，推荐判断是：

- `Python LangGraph`：更适合，也通常略省
- `Python LangChain agent`：开发可以更快，但资源通常相近或略高

保守估计在单独 AI worker 中：

| 方案 | AI worker 建议预算 |
|---|---:|
| Python `LangGraph` | 180 - 350 MB |
| Python `LangChain` agent | 220 - 420 MB |

差值通常不是几百 MB，而更可能是：

- `20 - 80 MB`

真正的大头仍然是：

- Python 解释器本身
- Web 框架
- 流式缓冲
- 并发数
- 输入文本大小

#### 11.7.2 Java LangChain4j 与 Python LangChain

这里要分两种口径：

1. 只比“AI 服务进程”本身
2. 比“整个项目落地后的总内存”

如果只比较 AI 服务进程本身：

- `Python LangChain` 通常会比 `Spring Boot + LangChain4j` 更轻
- 主要原因不是 LangChain4j 库更重，而是 JVM + Spring Boot 的基础常驻成本更高

保守估计：

| 方案 | AI 服务进程预算 |
|---|---:|
| Java `LangChain4j` 服务 | 300 - 600 MB |
| Python `LangChain` 服务 | 180 - 420 MB |

但如果放回你当前项目看，结论要更谨慎：

- 现在 Java 后端不只是 AI 调用器，它还承载鉴权、配置、爬虫转发、缓存、入库、SSE、管理接口
- 所以即使 AI 改到 Python，Java 后端仍然要保留
- 这意味着“把 AI 框架放到 Python”并不会让 Java 进程消失，只会减少 Java 内部 AI 逻辑的复杂度

因此在当前项目中：

- “Java 用 LangChain4j 直接做完全链路”总内存更低
- “Java 保留业务 + Python 负责 AI”总内存更高
- 但后者更适合承接官方 `LangGraph` 能力

#### 11.7.3 与当前推荐方案的对比

下面按当前项目结构做总预算对比。

场景 A：Java 后端内直接用 `LangChain4j` 完成多 agent，不新增 Python AI worker

| 容器 | 预算 |
|---|---:|
| `backend` | 896 MB |
| `crawler` | 256 MB |
| `mysql` | 640 MB |
| `redis` | 96 MB |

容器合计：

- `1888 MB`

整机预算区间：

- 约 `2808 MB - 3258 MB`

优点：

- 内存最低
- 容器数最少
- 部署最简单

缺点：

- 不是真正官方 `LangGraph`
- Java 侧要自己承担更多图编排和 agent 复杂度
- 后续并行、取消、状态恢复、扩展能力不如官方 LangGraph 路线自然

场景 B：Java 保留业务，Python 用 `LangChain` agent 做 AI worker

| 容器 | 预算 |
|---|---:|
| `backend` | 768 MB |
| `python-ai-worker` | 448 MB |
| `crawler` | 256 MB |
| `mysql` | 640 MB |
| `redis` | 96 MB |

容器合计：

- `2208 MB`

整机预算区间：

- 约 `3128 MB - 3578 MB`

优点：

- Java 业务层与 AI 层分离
- Python 生态更适合快速接入 AI 框架

缺点：

- 多 agent 场景下通常不比直接 `LangGraph` 更省
- agent 抽象更高，性能和状态控制不如直接 graph 明确

场景 C：当前推荐方案，Java 保留业务，Python 用官方 `LangGraph` 做 AI worker

| 容器 | 预算 |
|---|---:|
| `backend` | 768 MB |
| `langgraph-worker` | 384 MB |
| `crawler` | 256 MB |
| `mysql` | 640 MB |
| `redis` | 96 MB |

容器合计：

- `2144 MB`

整机预算区间：

- 约 `3064 MB - 3514 MB`

优点：

- 使用官方 `LangGraph`
- 多 agent、并行、取消、图状态控制最自然
- 比 Python `LangChain` agent worker 略轻或至少不更重

缺点：

- 比“全留在 Java 内”多一层进程和容器
- 部署复杂度高于单体 Java 方案

#### 11.7.4 最终判断

如果只按“资源消耗最小”排序，建议理解为：

1. Java `LangChain4j` 直接做完整链路
2. Java + Python `LangGraph`
3. Java + Python `LangChain` agent

如果按“二期目标完成度 + 官方框架贴合度 + 资源可控性”综合排序，建议理解为：

1. Java + Python `LangGraph`
2. Java `LangChain4j`
3. Java + Python `LangChain` agent

所以最终判断是：

- 你若只追求最低内存，就留在 Java 里做，不新增 Python worker
- 你若追求官方 LangGraph、多 agent 编排能力和后续演进空间，就用当前推荐方案
- 不建议为了“全 Python”而选择 `Python LangChain agent` 替代 `LangGraph`，因为在本项目这种多 agent 场景下，它通常不会更省，控制力还更弱

## 12. 可行性与风险结论

### 12.1 可行性结论

本项目二期在 `2 核 4G` Linux Docker 环境下可行，但必须满足以下条件：

- Java 侧取消无界线程
- Python worker 单进程运行
- 单机只保留一个 LangGraph worker 容器
- 长文 chunk 并发做动态限流
- DeepSeek 使用 JSON 模式和上下文缓存
- MySQL 与 Redis 都设置合理上限

### 12.2 主要风险

高风险项：

- 没有取消 `SimpleAsyncTaskExecutor`
- 一个用户同时触发多个长文且每个长文都高 chunk 并发
- 容器没有硬内存上限
- 趋势分析仍然走“大段自由文本输入”

中风险项：

- JSON schema 设计不稳定，导致前后端反复变更
- 迁移期双栈共存时日志和指标未区分来源

低风险项：

- 前端界面本身不需要大改，只需适配新的状态和指标字段

## 13. 实施阶段建议

建议按以下阶段推进：

### 阶段 1：打基础

- 新建本地分支 `codex/phase2-langgraph`
- 新增 `langgraph-worker/` 工程
- 确定 Java 与 worker 的内部契约
- 加运行模式开关
- 替换 Java 无界线程池

### 阶段 2：迁移单书三项分析

- 先迁 `deconstruct`
- 再迁 `structure`
- 再迁 `plot`
- 统一单书分析 JSON 外壳和 metrics 上报

### 阶段 3：迁移趋势分析

- 将趋势分析改为榜单级 graph
- 固化 `historicalWordCloud` 等结构字段
- 接入历史快照不足三次时的兼容逻辑

### 阶段 4：资源治理与灰度

- 接入容器资源限制
- 为 Java、worker、MySQL 设置上限
- 增加 trace、queue time、provider latency 指标
- 切换部分接口到 `langgraph` 模式做验收

### 阶段 5：收尾

- 验收通过后逐步下线 `LangChain4j` 主链路
- 保留有限回滚窗口
- 最终删掉 legacy 主链路代码

## 14. 验收标准

二期验收至少需要满足：

- 单书分析三个面板点击后能真正并行启动
- 不点击的面板不自动跑
- 点“停止”只停止当前面板
- `10 章` 长文在最终完成前，用户已经能看到实时 `start` / `progress` / `delta`
- 趋势分析严格针对当前选择的榜单，不再做平台级混合分析
- 趋势历史不足三次时，按已有次数直接展示
- 数据库中的 `result_json` 可直接支撑前端展示，不依赖脆弱的二次文本解析
- Linux Docker `2 核 4G` 部署下，容器不出现频繁 OOM 或明显抖动

## 15. 参考资料

- [LangGraph Python 官方文档](https://docs.langchain.com/oss/python/langgraph)
- [LangGraph JavaScript 官方文档](https://docs.langchain.com/oss/javascript/langgraph)
- [LangChain Multi-Agent 官方文档](https://docs.langchain.com/oss/python/langchain/multi-agent/index)
- [LangChain Structured Output 官方文档](https://docs.langchain.com/oss/python/langchain/structured-output)
- [DeepSeek 官方文档](https://api-docs.deepseek.com/zh-cn/)
- [DeepSeek JSON Output](https://api-docs.deepseek.com/zh-cn/guides/json_mode)
- [DeepSeek 模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing)
