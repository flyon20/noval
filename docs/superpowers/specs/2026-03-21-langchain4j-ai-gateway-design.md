# LangChain4j AI Gateway 设计说明

## 目标

在不引入 Dify 独立部署的前提下，把后端 AI 调用链升级为可直接通过 `LangChain4j` 调用 OpenAI 兼容模型，并满足以下要求：

- 现有分析接口与 SSE 协议流接口路径保持不变，前端联调成本最小。
- 模型密钥不入库、不进仓库，只允许通过本地环境变量或本地未提交配置引用。
- 分析类型维度的模型参数可配置，供应商级非密钥参数可通过系统配置调整。
- AI 返回内容继续统一落到 `analysis_result`，保持历史回看、趋势展示、协议流能力不受影响。

## 现状问题

- 当前 [AiGatewayService.java](D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java) 只有 Dify workflow blocking 调用。
- `LangChain4j` 目前只用于 `PromptTemplate` 渲染，没有真正接上模型客户端。
- `prompt_config` 表虽已具备 `temperature`、`max_tokens` 字段，但接口层未暴露，前端无法配置。
- `system_config` 已经被爬虫刷新策略实际使用，但 AI 侧还没有接入这套动态配置能力。

## 方案选择

### 方案 A：LangChain4j + OpenAI 兼容网关

保留 Spring Boot 主链路，在 Java 后端直接通过 `LangChain4j` 的 OpenAI 兼容模型接入 DeepSeek 一类供应商。

优点：

- 不依赖 Dify 单独部署，资源占用更低。
- 可以较快支持“多个模型名切换”的业务诉求。
- 与现有项目架构最贴近，改动面集中在 AI 网关与配置层。

缺点：

- 工作流编排能力需要后端自己维护。
- 供应商级配置与路由逻辑需要我们自己抽象。

### 方案 B：继续只保留 Dify 通道

不改 Java AI 调用链，只要求部署 Dify 并在 Dify 中配置模型与工作流。

不采用原因：

- 当前本地没有 Dify，且 Dify 对 2 核 4G 机器偏重。
- 会把“模型切换能力”绑定到额外平台部署上，不适合现在先跑通本地和小规格服务器。

## 最终设计

### 1. AI 运行时配置分层

配置分三层，优先级从高到低：

1. `system_config`
2. `application.yml`
3. 代码默认值

其中：

- 密钥只从环境变量读取。
- 环境变量名可以来自 `application.yml` 默认配置，也可以通过 `system_config` 覆盖。

计划支持的 AI 配置键：

- `ai.provider.type`
- `ai.timeout.millis`
- `ai.openai-compatible.base-url`
- `ai.openai-compatible.api-key-env`
- `ai.openai-compatible.default-model`
- `ai.openai-compatible.streaming-enabled`

### 2. Prompt 配置扩展

继续使用 `prompt_config` 表，不新增字段结构。

接口补齐以下可编辑字段：

- `modelName`
- `temperature`
- `maxTokens`

语义：

- `modelName`：当前分析类型默认使用的模型名，如 `deepseek-chat`
- `temperature`：传给模型的采样温度
- `maxTokens`：传给模型的输出上限

### 3. AI 调用链

阻塞分析链路：

1. 读取 `prompt_config`
2. 渲染提示词
3. 解析系统配置与应用配置，组装 LangChain4j 模型
4. 调用 OpenAI 兼容接口
5. 规范化返回为 `AiInvokeResult`
6. 保存到 `analysis_result`

协议流链路：

- 保持现有 SSE 事件协议不变：`start` / `delta` / `done` / `error`
- 本轮优先保证“模型真实调用 + 协议流接口继续稳定可用”
- 若流式模型调用条件不足，则继续复用当前“阻塞结果切片推送”的协议流方式

### 4. 接口影响

不新增分析接口路径，不修改现有路径。

仅补充配置接口字段：

- `GET /api/config/prompt`
- `PUT /api/config/prompt`

新增字段说明：

- `temperature`
- `maxTokens`

`/api/config/system` 不改路径，但会新增 AI 相关 key 约定，前端需要同步文档中的固定 key 列表。

### 5. 数据库影响

本轮不改表结构。

仅有两类数据库层变化：

- 使用现有 `prompt_config.temperature`、`prompt_config.max_tokens`
- 在 `system_config` 种子数据中增加 AI 配置项

## 错误处理

- 未配置环境变量 key：返回统一业务失败，不能回显真实密钥信息。
- 模型响应无法解析结构化 JSON：保存兜底摘要 JSON，保证落库不断链。
- 系统配置缺失：回退到 `application.yml` 默认值。

## 测试策略

- 先补失败测试，验证：
  - 配置接口能保存和返回模型参数
  - LangChain4j 调用成功后分析结果能落库
  - SSE 协议流接口仍然可用
- 使用本地 mock OpenAI 兼容 HTTP 服务验证，不依赖真实外网 key。

## 前端对齐要求

- 前端提示词配置页需要增加 `temperature`、`maxTokens` 表单项。
- 系统配置页固定 key 列表需要补充 AI 配置项。
- 分析与趋势接口路径保持不变，现有联调逻辑不需要重写。
