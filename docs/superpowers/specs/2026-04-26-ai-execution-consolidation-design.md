# AI 执行层收口与 Legacy 迁移设计

## 背景

当前仓库中的 AI 相关链路仍处于“双栈并存”状态：

- `Java backend` 既承担主系统业务编排，也仍保留一套可直接调用模型的 `legacy` 执行链路。
- `Python langgraph-worker` 已承担一套基于 LangGraph 的 AI 执行链路，但尚未完全覆盖 Java legacy 的能力边界。
- 前端只感知 Java API，不直接感知 Python worker 或 provider 细节。

在当前状态下，系统可以正常工作，但存在以下长期问题：

1. AI 执行层分散在 Java 与 Python 两边，后续修复流式恢复、provider 切换、执行时 fallback、chunk/merge 策略时需要双边维护。
2. `analysis.runtime.mode` 仍以 `legacy` 作为默认值，说明 Python 路径尚未达到完全替代 legacy 的成熟度。
3. 趋势分析与单书分析都已经强依赖结构化 JSON 输出；一旦执行层迁移时改动 JSON 契约，前端趋势页、单书分析展示、历史结果回放都会受到影响。

本设计的目标不是调整产品功能，而是在**不改变现有对外契约和 JSON 结构约束**的前提下，将“真正的 AI 执行层”逐步统一收口到 Python，并为最终删除 Java 直接调模型代码建立清晰切线。

## 目标

### 主目标

1. `Java` 保留主系统与业务编排职责，不再直接承载最终的模型调用执行。
2. `Python langgraph-worker` 统一负责真正的 AI 执行，包括：
   - provider 调用
   - chunk / merge
   - LangGraph 流程
   - 执行期 fallback
3. 在 Python 路径补齐 Java legacy 能力后，将 `analysis.runtime.mode` 默认值切换到 `langgraph`。
4. 完成切换与验证后，删除 Java 中直接调模型的 legacy 代码。

### 约束目标

1. **JSON 契约不可改动**：字段名、层级、语义保持兼容。
2. 前端接口与 SSE 事件语义保持兼容。
3. 历史结果数据兼容，旧数据回放不能失效。
4. prompt/model 解析权仍由 Java 统一负责，Python 仅执行已解析完成的配置。

## 非目标

本次设计不包含以下内容：

1. 不重构前端趋势页或单书分析页展示逻辑。
2. 不改变 `prompt_config`、`analysis_result`、`system_config` 的表结构和既有字段语义。
3. 不改变趋势分析与单书分析的产品输出要求。
4. 不把缓存、持久化、鉴权或数据查询逻辑迁移到 Python。

## 当前架构现状

### 前端

- 前端统一调用 Java 对外接口：
  - `/api/analysis/*`
  - `/api/data/*`
  - `/api/crawler/*`
- 前端先尝试 SSE 流式请求，必要时自动回退到阻塞接口。

### Java backend

Java 当前承担以下职责：

1. 鉴权、路由、权限控制
2. 书籍/章节/榜单/快照查询
3. prompt 解析与运行时模型解析
4. 分析缓存复用与异步任务去重
5. 趋势分析结果标准化
6. 分析结果落库与历史结果查询
7. SSE 对前端出口
8. 在 `legacy` 模式下，直接调用 provider / Dify / fallback result

### Python langgraph-worker

Python worker 当前承担以下职责：

1. 接收 Java 组装的 `RunRequest`
2. 通过 LangGraph 执行 direct / chunk / merge
3. 基于 prompt 中的 JSON 契约补充系统提示
4. 调用 OpenAI-compatible provider
5. 解析或修复 JSON 结果
6. 输出 `RunResponse`

当前不足：

1. 尚未完整承接 Java legacy 的 provider 选择与 fallback 策略。
2. 与 Java legacy 的 chunk/merge 语义尚未完全统一。
3. Java 侧仍保有模型执行主路径，造成职责重复。

## 硬约束：JSON 契约冻结

本设计中的最高优先级约束是：**与模型输入/输出 JSON 契约相关的字段、结构、语义均视为冻结接口，不允许在迁移中改动。**

### Prompt 契约字段冻结

以下字段必须继续由 Java 读取并原样传递给 Python，字段名和值语义均不得变化：

- `inputJsonSchema`
- `inputExampleJson`
- `outputJsonSchema`
- `outputExampleJson`
- `parseConfigJson`
- `postProcessType`

原因：

1. 这些字段已经直接影响模型提示内容与 JSON 输出约束。
2. Python 当前已经基于这些字段做 prompt augmentation、JSON 强制输出和 JSON repair。
3. 管理后台、测试、默认契约目录均围绕这些字段工作。

### 趋势结果 JSON 冻结

趋势分析相关的以下字段视为冻结契约：

- `summary`
- `boardSummary`
- `trendPreview`
- `detailContent`
- `historicalWordCloud`
- `themeDistribution`
- `themeTable`
- `hotBooks`
- `insightCards`
- `snapshotComparisons`

原因：

1. Java 趋势归一化逻辑基于这些字段工作。
2. 前端趋势页和 `trend-display.ts` 已深度依赖这些字段。
3. 历史结果回放和可视化也依赖这些字段的语义和类型。

### 单书分析结果元数据冻结

以下字段虽不是 prompt 主契约字段，但已成为结果展示契约的一部分，也必须保持兼容：

- `analysisMode`
- `segmentCount`
- `requestedChapterCount`
- `actualChapterCount`
- `inputChapterCount`
- `chapterFetchDegraded`
- `promptRuntime`

建议这些字段继续由 Java 在结果落库前补齐，而不是迁移给 Python 主导。

## 当前默认模型与兜底链路现状

迁移设计必须同时覆盖“默认模型兜底”与“provider 执行兜底”，否则切换默认 runtime 后容易出现能力回退。

### 模型选择兜底

当前 Java 通过 `SystemConfigService.resolveEnabledModel(...)` 解析运行时模型，解析顺序为：

1. 用户偏好模型 `ai.preferred-model`
2. prompt 指定模型
3. 模型注册表 `defaultModelKey`
4. 第一个启用模型

这说明当前已经存在稳定的“默认模型选择兜底”能力，且它与用户偏好、模型注册表、系统配置强绑定。

设计要求：

- 模型选择兜底继续保留在 Java。
- Python 不自行决定默认模型，只消费 Java 已解析后的最终模型参数。

### Provider 调用兜底

当前 Java `AiGatewayService` 中存在 provider 优先级与兜底链路：

- 当 `providerType = dify`
  - 先调 Dify
  - Dify 失败后再调 OpenAI-compatible
- 否则
  - 先调 OpenAI-compatible
  - 失败后调 Dify

若两边均失败，则构造本地 fallback result。

这说明“模型失败后执行兜底的默认模型链路”并不只是“换一个默认模型”这么简单，而是由三层组成：

1. 模型解析兜底
2. provider 执行兜底
3. 本地 fallback result 兜底

设计要求：

- 在执行层收口到 Python 之前，必须先把这三层兜底能力迁移或重建到 Python 侧。
- 不允许在切换默认 runtime 后丢失现有 provider fallback 语义。

### 本地 fallback result

当前 Java 还有一层最终兜底：如果 provider 都失败，会构造一个基于源文本摘要的 fallback result。

设计要求：

- Python 侧也应实现兼容的最终 fallback result。
- 结果结构需要保持与现有 Java fallback 一致或兼容，至少不能破坏前端和结果落库。

## 目标边界

### Java 保留职责

长期保留在 Java 的职责如下：

1. 鉴权、权限、路由
2. 数据查询：
   - 书籍
   - 章节
   - 榜单
   - 快照
   - 历史结果
3. prompt 与 model 运行时解析
4. 缓存复用与任务去重
5. 分析结果落库
6. 趋势结果最终归一化
7. 单书结果业务元数据补充
8. SSE 对前端出口

这些职责与主系统耦合紧密，放在 Java 最稳。

### Python 统一职责

迁移完成后，Python 应统一承担：

1. provider 调用
2. provider 优先级 fallback
3. 最终 fallback result
4. LangGraph 执行图
5. 单书 chunk / merge 执行
6. 趋势分析执行
7. JSON 解析与 JSON repair
8. 执行期超时、流式错误、provider 错误处理

换句话说，Python 应成为“唯一的 AI 执行器”，而不是“可选执行器”。

## 保留 / 迁移 / 删除切线

### 一、保留类（长期保留在 Java）

以下类建议长期保留，不进入删除范围：

- `AnalysisService`
  - 但其职责收缩为业务编排和结果生命周期管理
- `LangGraphWorkerClient`
- `SystemConfigService`
- `PromptConfigService`
- `DefaultPromptContractCatalog`
- `TrendResultJsonUtils`
- `AnalysisRepository` 及其相关实体 / VO / DTO
- `CrawlerRepository` / `CrawlerService` / `PythonCrawlerClient`
- 所有 `frontend` 解析和展示逻辑

### 二、迁移类（能力迁移后 Java 侧不再保留主执行职责）

以下 Java 能力建议迁移到 Python：

- `AiGatewayService` 中的：
  - OpenAI-compatible 调用
  - Dify 调用
  - provider fallback
  - fallback result 生成
  - 流式 token 处理相关主执行逻辑
- `AnalysisService` 中 legacy 单书执行链：
  - chunk 拆分与执行
  - merge prompt 驱动的最终汇总调用

说明：

- 迁移后，Java 中这些逻辑可以先降级成适配层或空壳，再进入最终删除阶段。
- 若有少量纯工具方法仍被 Java 侧使用，可单独抽离为无 provider 依赖的工具类。

### 三、最终删除类 / 代码路径

当 Python 路径经过完整验证后，以下 Java 直接调模型代码应进入删除范围：

- `AiGatewayService` 中直接执行 provider 的核心方法
- `AnalysisService` 中 legacy 直接调模型分支
- 与 legacy 执行链强绑定、但又不再被 Java 编排复用的 chunk/merge 代码
- `analysis.runtime.mode=legacy` 作为长期运行路径的配置语义

注意：

- `analysis.runtime.mode` 可以保留配置项一段时间用于灰度和回滚。
- 但在最终状态中，`legacy` 不应再代表“Java 直接调模型”。

## 分阶段迁移方案

### Phase 1：补齐 Python 执行能力

目标：

- Python 覆盖 Java legacy 当前具备的执行能力，但不改变对外契约。

任务：

1. 在 Python 中补齐 Dify provider 支持
2. 在 Python 中实现 provider 优先级 fallback
3. 在 Python 中实现最终 fallback result
4. 对齐单书 chunk / merge 的核心语义
5. 确保 JSON 契约注入方式保持兼容

验收：

- 在不改变前端和 Java 主流程的情况下，Python 能独立完成 legacy 执行等价能力。

### Phase 2：Java 执行链统一改走 Python

目标：

- Java 不再自己直接调模型，而是统一通过 `LangGraphWorkerClient` 调 Python。

任务：

1. 单书分析 legacy 分支改为统一发 `RunRequest` 给 Python
2. 趋势分析继续走 Python，但保持 Java normalize 不变
3. 保留 Java：
   - prompt/model 解析
   - 元数据附加
   - 趋势 normalize
   - 落库 / 缓存 / SSE

验收：

- `analysis.runtime.mode=legacy` 与 `langgraph` 在用户可见结果上保持兼容，差异仅体现在底层执行器。

### Phase 3：切默认值

目标：

- 将默认 runtime 从 `legacy` 切为 `langgraph`

任务：

1. 将 `analysis.runtime.mode` 默认值改为 `langgraph`
2. 保留 runtime 配置项用于灰度与回滚
3. 执行完整集成回归

验收：

- 单书分析、趋势分析、历史回放、趋势页展示全部正常
- JSON 字段、结构、前端展示语义无回归

### Phase 4：删除 Java 直接调模型代码

目标：

- 删除 Java legacy 执行器，完成 AI 执行层收口

任务：

1. 删除 `AiGatewayService` 中直接模型调用主路径
2. 删除 Java legacy chunk / merge 主逻辑
3. 精简 `analysis.runtime.mode`
4. 清理失效测试与文档

验收：

- Java 不再直接触达 LLM SDK
- Python 成为唯一 AI 执行器

## SSE 与错误恢复策略

本次设计的重点是执行层收口，不是立即重构 SSE 协议，但迁移时必须确保以下兼容性：

1. 前端继续只感知：
   - `start`
   - `delta`
   - `done`
   - `error`
2. 前端已有的流式到阻塞 fallback 行为不可被破坏
3. 若后续要补“已收到部分 delta 后的流式失败恢复”，应优先在前端 / 执行层协议中补齐，而不是顺手改动 JSON 契约

## 风险

### 风险 1：JSON 契约被执行层重写

风险描述：

- 若迁移时把 prompt augmentation、输出 schema、repair prompt 做了不兼容改动，模型响应会漂移，趋势页和单书结果会一起受影响。

缓解：

- 迁移时将 JSON 契约视为冻结接口
- 对关键 prompt contract 建立逐字段回归测试

### 风险 2：趋势页字段兼容性回归

风险描述：

- 趋势页对 `boardSummary / trendPreview / historicalWordCloud / hotBooks / insightCards / themeDistribution` 高度敏感。

缓解：

- Java `normalizeTrendResultJson(...)` 继续保留
- 前端趋势页不做契约改造

### 风险 3：默认模型 / provider fallback 丢失

风险描述：

- 如果只迁移主执行路径，不迁移 provider fallback，切默认 runtime 后异常路径会退化。

缓解：

- Phase 1 必须先补齐 Python provider fallback 与 fallback result

### 风险 4：历史结果兼容性下降

风险描述：

- 新结果结构一旦偏移，历史页和趋势可视化会出现新旧数据不一致。

缓解：

- `analysis_result.result_json` 外部结构冻结
- 对新旧数据做兼容回归

## 测试与验收建议

### 必测一：Prompt 契约透传一致性

验证 Java 传给 Python 的以下字段逐项一致：

- `inputJsonSchema`
- `inputExampleJson`
- `outputJsonSchema`
- `outputExampleJson`
- `parseConfigJson`
- `postProcessType`

### 必测二：单书结果 JSON 兼容

验证：

- 顶层字段不变
- `analysisMode / segmentCount / inputChapterCount` 等字段存在且语义不变

### 必测三：趋势结果 JSON 兼容

验证：

- `boardSummary`
- `trendPreview`
- `historicalWordCloud`
- `themeDistribution`
- `themeTable`
- `hotBooks`
- `insightCards`
- `snapshotComparisons`

字段全部存在且类型兼容。

### 必测四：默认模型与 provider fallback

验证：

1. 用户偏好模型失效时，能回退到 `defaultModelKey`
2. `defaultModelKey` 不可用时，能回退到第一个启用模型
3. 首选 provider 失败时，能回退到次级 provider
4. provider 全部失败时，仍有最终 fallback result

### 必测五：前端页面无感迁移

验证：

- 单书分析页
- 趋势页
- 历史页

在不修改前端契约的前提下均可正常工作。

## 结论

推荐采用如下最终收口路径：

1. `Java` 保留主系统业务编排与结果生命周期管理
2. `Python` 统一承担真正的 AI 执行职责
3. JSON 契约与结果字段视为冻结接口，迁移期间只能更换执行者，不能更换协议
4. 先补齐 Python 对 Java legacy 的执行能力，再切默认 runtime，最后删除 Java 直接调模型代码

这是当前仓库中风险最低、可回归验证最清晰、最不容易破坏趋势页与单书分析结果的一条演进路线。
