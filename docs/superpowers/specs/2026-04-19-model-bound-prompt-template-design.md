# 模型绑定模板设计

**目标**

保留“用户通过下拉框切换已配置模型进行分析”的能力，同时让不同模型可以绑定各自的提示词模板；未配置专属模板时，自动回退到 `deepseek-chat` 对应模板，再回退到 `default` 模板。

**问题背景**

当前系统存在两套互相打架的配置来源：

1. 模型注册表 `ai.model-registry.json`
2. `prompt_config` 中每种分析类型的 `model_name / temperature / max_tokens`

运行时虽然会优先读取用户的 `ai.preferred-model`，但提示词模板仍然只有“按 `prompt_type` 取一条默认记录”的模式，导致：

- 用户切换模型后，提示词不一定跟着切
- 管理员需要去不同页面重复修改模型与模板
- 模板总数、模板用途、每个模型实际使用哪套模板都不清晰

**设计原则**

- 用户选择模型后，应明确使用该模型绑定的模板
- 模板正文允许按模型分化
- JSON 结构约束默认共享，避免重复维护
- 未配置模型专属模板时，优先回退到 `deepseek-chat` 的成熟模板
- 再次回退时才使用 `default`
- 尽量复用现有表结构和现有模型注册表，不额外引入复杂数据库结构

## 一、模板组织方式

`prompt_config` 从“每种分析类型只有一条当前生效记录”升级为“模板库”。

模板唯一标识继续沿用现有唯一键：

- `prompt_type`
- `prompt_name`

建议约定：

- `prompt_name = default`
- `prompt_name = deepseek-chat`
- `prompt_name = kimi-k2.5`
- 也允许后续扩展为更语义化名称，例如 `kimi-compact-v2`

四类分析类型分别维护独立模板集合：

- `deconstruct`
- `structure`
- `plot`
- `theme`

## 二、模型与模板的绑定方式

模型绑定关系放入 `ai.model-registry.json` 中，每个模型项新增 `promptBindings` 字段。

示例：

```json
{
  "defaultModelKey": "deepseek-chat",
  "models": [
    {
      "modelKey": "deepseek-chat",
      "displayName": "Deepseek Chat",
      "providerType": "openai-compatible",
      "modelName": "deepseek-chat",
      "baseUrl": "https://api.deepseek.com/v1",
      "enabled": true,
      "isDefault": true,
      "defaultTemperature": 1.0,
      "maxTokens": 8192,
      "temperatureSpecJson": "{\"min\":0.0,\"max\":2.0,\"step\":0.1,\"default\":1.0}",
      "promptBindings": {
        "deconstruct": "deepseek-chat",
        "structure": "deepseek-chat",
        "plot": "deepseek-chat",
        "theme": "default"
      }
    },
    {
      "modelKey": "kimi-k2.5",
      "displayName": "kimi",
      "providerType": "openai-compatible",
      "modelName": "kimi-k2.5",
      "baseUrl": "https://api.moonshot.cn/v1",
      "enabled": true,
      "isDefault": false,
      "defaultTemperature": 1.0,
      "maxTokens": 8192,
      "temperatureSpecJson": "{\"min\":0.0,\"max\":2.0,\"step\":0.1,\"default\":1.0}",
      "promptBindings": {
        "deconstruct": "kimi-k2.5",
        "structure": "kimi-k2.5",
        "plot": "deepseek-chat",
        "theme": "default"
      }
    }
  ]
}
```

## 三、运行时模板解析规则

分析运行时先确定当前模型 `modelKey`，然后按如下顺序解析模板：

1. 当前模型在当前 `analysisType` 上的绑定模板
2. `deepseek-chat` 在当前 `analysisType` 上的绑定模板
3. `prompt_name = default` 的模板

例如用户选择 `kimi-k2.5` 做拆文：

1. 查 `modelKey = kimi-k2.5` 的 `promptBindings.deconstruct`
2. 若无，查 `deepseek-chat` 的 `promptBindings.deconstruct`
3. 若仍无，则查 `deconstruct/default`

这样可以保证：

- 新模型接入时不用一次性补齐全部模板
- 业务可以持续复用 DeepSeek 的成熟模板
- 用户切模型时不会失效

## 四、模型参数与模板参数职责分离

### 模型注册表负责

- `baseUrl`
- `apiKey`
- `defaultTemperature`
- `maxTokens`
- `providerType`

### 模板库负责

- `promptContent`
- `promptName`
- 结构化约束字段

### 共享约束的继承规则

以下字段默认从 `default` 模板继承：

- `inputJsonSchema`
- `inputExampleJson`
- `outputJsonSchema`
- `outputExampleJson`
- `postProcessType`
- `parseConfigJson`

也就是说：

- 模型专属模板主要维护 `promptContent`
- 如果模型专属模板未填写结构化约束字段，则自动继承 `default`

这样可以避免同一套 JSON 契约在多个模型模板中重复拷贝。

## 五、前端交互设计

### 1. 提示词配置页

目标：把 `prompt_config` 作为模板库来管理。

新增交互：

- 先选择 `prompt_type`
- 再通过模板名称下拉查看该类型下所有模板
- 页面显示“当前类型已有多少种模板”
- 支持新增模板名、保存模板内容
- 结构化契约区继续保留，但提示“默认继承 `default` 模板”

建议默认模板名展示：

- `default`
- `deepseek-chat`
- `kimi-k2.5`

### 2. 系统配置页

目标：把模型和模板绑定关系放在同一张模型卡片里配置。

每个模型卡片下新增 4 个模板绑定下拉：

- 拆文模板
- 结构模板
- 情节模板
- 趋势模板

下拉项来自当前 `prompt_type` 对应的模板名称列表。

### 3. 分析页 / 趋势页

保留现有“用户下拉切模型”的交互，不新增心智负担。

用户看到的仍然只是：

- 选择模型
- 点击分析

模板由后端根据当前模型自动解析。

## 六、后端改造点

### 1. 模型注册表对象扩展

为 `AiModelRegistryModelVO` / `AiModelRegistryModelRequest` 增加：

- `promptBindings`

类型建议：

```json
{
  "deconstruct": "promptName",
  "structure": "promptName",
  "plot": "promptName",
  "theme": "promptName"
}
```

### 2. PromptConfigRepository 新增查询方法

新增按：

- `prompt_type`
- `prompt_name`

查询活动模板的方法，以及按 `prompt_type` 拉取全部模板名称的方法。

### 3. PromptConfigService 新增模板列表接口

用于前端下拉展示：

- 当前分析类型有哪些模板名称
- 每个模板的基础信息

### 4. 运行时模板选择器

在分析调用前新增一层模板解析：

- 输入：`analysisType + userPreferredModel`
- 输出：最终 `PromptConfigEntity`

该解析器负责三层回退：

1. 当前模型绑定模板
2. `deepseek-chat` 绑定模板
3. `default`

### 5. 缓存键修正

分析缓存键必须加入：

- 当前模型 `modelKey`
- 最终命中的 `prompt_name`

否则用户切模型、切模板仍会误命中旧缓存。

### 6. 结果回显一致性

分析结果中落库的：

- `model_name`
- `prompt_config_id`

都必须对应“本次真正使用的模型和模板”。

## 七、兼容策略

### 初始化兼容

现有每个 `prompt_type` 的现存模板先视为：

- `prompt_name = default`

如果需要为 `deepseek-chat` 显式建模板，可复制一份：

- `prompt_name = deepseek-chat`

### 老字段兼容

`prompt_config.model_name / temperature / max_tokens` 可先保留，避免数据库迁移过大。

但新逻辑中应逐步降级它们的职责：

- `model_name` 不再作为主控模型来源
- `temperature / max_tokens` 若保留，应明确为模板高级覆盖项，而不是默认运行参数

## 八、难度评估

整体难度：**中等偏小**

原因：

- 数据表结构天然支持“同一类型多模板”
- 前端已有模型切换入口
- 后端已有用户模型偏好入口
- 主要新增的是“模板绑定解析”和“模板列表管理”

## 九、推荐推进顺序

1. 后端支持模板绑定解析与回退
2. 修正缓存键
3. 系统配置页增加模型模板绑定 UI
4. 提示词配置页增加模板名称下拉与模板列表
5. 数据迁移：把现有模板规范化为 `default`

## 十、预期效果

最终用户体验应变成：

- 管理员在系统配置页录入模型
- 为每个模型分别绑定拆文/结构/情节/趋势模板
- 普通用户在分析页只需要下拉选择模型
- 系统自动取该模型绑定模板
- 没配就自动用 DeepSeek 模板兜底
- DeepSeek 也没配再用 `default`

这正是“模型配置里添加了，用户选择切换哪一个就切换哪一个；没配置的用 DeepSeek 的提示词兜底”的产品语义。
