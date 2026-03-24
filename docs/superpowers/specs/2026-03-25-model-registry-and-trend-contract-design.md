# 2026-03-25 模型注册表与趋势 JSON 契约重构设计

## 背景

当前系统已经具备单书分析、趋势分析、Prompt 配置、系统配置、LangGraph Worker 与 OpenAI-Compatible 调用链，但仍存在两个关键结构性问题：

1. AI 模型配置仍然是扁平字符串模式。
   - `ai.available-models` 只是逗号分隔列表。
   - Java `AiGatewayService` 与 Python `langgraph-worker` 仍依赖单套全局 `baseUrl/apiKey/defaultModel`。
   - 用户虽然能下拉切换模型，但模型没有标签、温度元数据、独立请求参数，也无法真正表达“多个可管理模型”。

2. 趋势分析的结构化 JSON 契约仍然不够硬。
   - 运行时虽然要求返回 JSON，但输入契约没有在管理员界面可见。
   - 趋势页很多字段依然依赖后端兜底推断或占位生成。
   - 前端图表、词云、代表作品、榜单摘要、趋势洞察并没有完全建立在“AI 已返回且已落库”的统一结构之上。

本轮设计目标是把这两条链路统一成“配置可见、契约稳定、前后端一致、可直接落库渲染”的结构。

## 用户目标

### 模型配置
- 管理员可以在配置页中看到所有已配置的大模型。
- 管理员可以新增多个 OpenAI-Compatible 模型条目，而不是只维护一个字符串列表。
- 每个模型条目可维护：
  - 展示名称
  - 模型 key
  - 实际请求 `model`
  - `baseUrl`
  - `apiKey`
  - 是否启用
  - 是否默认
  - 温度能力 JSON
  - 默认温度
  - 可选 `maxTokens`
- 用户端只需要一个下拉框选择“当前使用哪个模型”，该选择作用于所有分析。

### 趋势分析
- 趋势分析必须围绕“当前选中的具体榜单”展开。
- AI 输入 JSON 与输出 JSON 契约必须在管理员界面可见。
- Prompt 正文可以继续专业化写作，但运行时必须追加稳定契约说明。
- 趋势页使用的核心字段都要直接来自落库 JSON，而不是二次猜测。
- 历史快照不足 3 次时，用现有次数直接展示，不拒绝、不等待。

## 设计总览

### 一、模型注册表

新增系统配置键：

- `ai.model-registry.json`
- `ai.model-registry.version`

其中 `ai.model-registry.json` 存储结构化模型列表，建议结构如下：

```json
{
  "defaultModelKey": "deepseek-chat",
  "models": [
    {
      "modelKey": "deepseek-chat",
      "displayName": "DeepSeek Chat",
      "providerType": "openai-compatible",
      "modelName": "deepseek-chat",
      "baseUrl": "https://api.deepseek.com/v1",
      "apiKey": "",
      "enabled": true,
      "isDefault": true,
      "defaultTemperature": 1.0,
      "maxTokens": 8192,
      "temperatureSpec": {
        "min": 0.0,
        "max": 2.0,
        "step": 0.1,
        "default": 1.0,
        "docNote": "按官方 OpenAI-Compatible 文档维护"
      }
    }
  ]
}
```

#### 关键规则
- `modelKey` 是用户配置与前端下拉的唯一标识。
- `displayName` 用于前端显示。
- `modelName` 是真正发送给 OpenAI-Compatible `/chat/completions` 的 `model` 字段。
- `baseUrl/apiKey` 按模型条目维护，允许不同厂商共存。
- `defaultTemperature` 必须落在 `temperatureSpec.min/max` 范围内。
- 系统只允许一个默认模型；如果管理员没有显式设置，则自动用第一个启用模型兜底。

### 二、模型解析优先级

新的运行优先级：

1. 用户配置 `ai.preferred-model`
2. 模型注册表中的默认模型
3. 旧系统配置 `ai.openai-compatible.default-model` 作为兼容兜底
4. `PromptConfig.modelName` 仅作为历史兼容，不再是主入口

这意味着：
- Prompt 配置不再承担“选哪个模型”的职责。
- Prompt 配置只负责 Prompt、输出契约、解析策略。
- 所有分析页共用一套用户模型选择。

### 三、趋势分析输入契约

Prompt 配置新增输入契约字段：

- `inputJsonSchema`
- `inputExampleJson`

趋势分析运行时始终发送结构化 `sourcePayload`，并将契约信息附加到 Prompt 调用链中。

趋势输入的核心结构如下：

```json
{
  "kind": "trend_analysis",
  "analysisType": "theme",
  "platform": "fanqie",
  "channelCode": "male-new",
  "boardCode": "urban-brain",
  "boardName": "都市脑洞",
  "snapshotCount": 3,
  "snapshots": [
    {
      "snapshotId": 87,
      "snapshotTime": "2026-03-25 01:02:03",
      "recordCount": 30,
      "ranks": [
        {
          "rankNo": 1,
          "bookId": 1001,
          "bookName": "书名",
          "author": "作者",
          "intro": "简介"
        }
      ]
    }
  ]
}
```

#### 输入契约要求
- AI 必须明确知道这是“同一个榜单的历史快照对比”，不是全平台混合分析。
- `ranks` 中至少包含 `rankNo/bookId/bookName/author/intro`。
- 可用快照数小于 3 时，必须按实际可用快照继续分析。

### 四、趋势分析输出契约

趋势分析的输出 JSON 统一约束为以下核心字段：

```json
{
  "analysisType": "theme",
  "platform": "fanqie",
  "channelCode": "male-new",
  "boardCode": "urban-brain",
  "boardName": "都市脑洞",
  "summary": "300字左右摘要",
  "boardSummary": "对当前榜单整体走向的摘要",
  "detailContent": "完整分析正文",
  "historicalWordCloud": [
    { "name": "系统", "value": 18 }
  ],
  "themeDistribution": [
    { "theme": "都市脑洞-系统流", "count": 12, "ratio": 40.0 }
  ],
  "themeTable": [
    {
      "theme": "都市脑洞-系统流",
      "count": 12,
      "ratio": 40.0,
      "trend": "up",
      "representativeBooks": [
        {
          "bookName": "代表热书",
          "author": "作者",
          "rankNo": 1,
          "reason": "为何能代表该题材"
        }
      ]
    }
  ],
  "hotBooks": [
    {
      "theme": "都市脑洞-系统流",
      "bookName": "代表热书",
      "author": "作者",
      "rankNo": 1,
      "reason": "高位代表作"
    }
  ],
  "insightCards": [
    {
      "label": "主赛道",
      "value": "都市脑洞-系统流",
      "note": "占比最高题材"
    },
    {
      "label": "代表热书",
      "value": "某本书",
      "note": "该题材下当前排名最高"
    }
  ],
  "snapshotComparisons": [
    {
      "snapshotTime": "2026-03-25 01:02:03",
      "topTheme": "都市脑洞-系统流",
      "topThemeRatio": 40.0,
      "leadBookName": "某本书",
      "change": "up"
    }
  ],
  "historyAnalysisCount": 3,
  "trendPreview": "前端短摘要"
}
```

#### 输出契约解释
- `summary`：用于结果卡片短摘要。
- `boardSummary`：用于“榜单摘要”模块。
- `detailContent`：用于详情抽屉。
- `historicalWordCloud`：真正的词云数据源。
- `themeDistribution`：用于分布图和主赛道判断。
- `themeTable`：用于题材表格，且包含代表作。
- `hotBooks`：用于代表作品模块。
- `insightCards`：用于趋势洞察卡片。
- `snapshotComparisons`：用于历史对比表/图。

### 五、后端职责边界

#### 保留的后端职责
- 构造榜单历史快照输入数据
- 附加输入/输出 JSON 契约
- 校验输出形状
- 序列化落库
- 将缺失字段补成“空结构”，而不是伪造业务结论

#### 不再做的事情
- 不再把缺失题材字段硬推成榜单名
- 不再把缺失热书硬推成榜首书并当作真实 AI 结果
- 不再把缺失趋势表硬拼成 placeholder 业务结论

也就是说，后端只负责“结构保底”，不负责“语义造假”。

### 六、管理员界面改造

#### 系统配置页
- 新增“模型注册表”区块。
- 展示当前模型列表。
- 支持新增/删除/编辑模型条目。
- 支持设置默认模型。
- 支持编辑每个模型的 `temperatureSpec` JSON。
- 保留原有其他系统配置项。

#### Prompt 配置页
- 增加输入契约 JSON 区块：
  - Input JSON Schema
  - Input Example JSON
- 保留输出契约 JSON 区块：
  - Output JSON Schema
  - Output Example JSON
  - Parse Config JSON
- JSON 区块默认只读。
- 点击“编辑 JSON 契约”后才允许修改。
- 保存前需要二次确认。

### 七、用户界面改造

#### 单书分析页
- 保留当前模型下拉，但选项来自模型注册表对象，而不是纯字符串。
- 选择后写入 `ai.preferred-model`。

#### 趋势页
- 同样增加模型下拉，读取/写入同一用户配置。
- 趋势页只负责发起分析和展示结构化结果，不单独维护一套模型配置。
- 词云改为真正的词云布局，不再用柱状图假装词云。
- PC 与手机端都保证：
  - 摘要区高度紧凑
  - 详情在抽屉中可开关
  - 图表和卡片按契约字段直接显示

## 兼容性策略

### 数据兼容
- 旧 `ai.available-models` 保留读取能力，用于首次迁移生成模型注册表。
- 旧 PromptConfig 没有输入契约字段时，代码生成默认趋势输入契约。
- 旧趋势结果 JSON 缺少新字段时，只渲染已有字段，不虚构业务语义。

### 运行兼容
- Java 旧调用链与 LangGraph Worker 都统一切换到“按模型条目解析 provider config”。
- 若模型注册表为空，则回退到旧全局 OpenAI-Compatible 配置，保证系统不直接炸掉。

## 测试策略

### 后端
- 模型注册表解析测试
- 用户首选模型与默认模型优先级测试
- PromptConfig 新字段读写测试
- 趋势分析输出契约测试
- LangGraph 请求中模型条目参数透传测试
- `/api/data/visual` 使用严格结构化字段渲染测试

### 前端
- 系统配置页模型注册表增删改测
- Prompt 配置页 JSON 锁定/编辑/确认测
- 单书分析页与趋势页模型选择测
- 趋势页基于新契约字段的展示测
- 手机端抽屉与词云布局测

## 本轮不做

- 不引入新的多 Provider SDK 体系，继续以 OpenAI-Compatible 为主。
- 不做数据库层面的复杂版本化历史对比编辑器。
- 不新增验证码或额外权限系统。

## 结论

本轮重构的关键不是“多加几个字段”，而是把 AI 模型配置和趋势 JSON 契约都提升为一等公民。  
完成后，系统会形成一条稳定链路：

管理员维护模型注册表与 Prompt 契约  
→ 用户选择一个全局分析模型  
→ 运行时按板块发送严格输入 JSON  
→ AI 返回严格输出 JSON  
→ 结果直接入库  
→ 趋势页与分析页从结构化结果直接渲染

这样后续不管是继续加模型，还是继续扩趋势分析图表，都不需要再靠“临时兜底拼字段”推进。
