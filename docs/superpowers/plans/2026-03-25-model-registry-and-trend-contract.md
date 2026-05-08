# 2026-03-25 模型注册表与趋势 JSON 契约实施计划

## 目标

按阶段完成以下两条主线：

1. 将 AI 模型配置升级为可管理的模型注册表，并让单书分析/趋势分析共享同一个用户选模。
2. 将趋势分析改造成“输入契约明确、输出契约严格、管理员可见可编辑、前端直接渲染落库 JSON”的模式。

## 阶段拆分

### Phase 1: 文档与基线冻结
- 写入本次设计文档与实施计划
- 更新 `task_plan.md` / `findings.md` / `progress.md`
- 本地提交当前工作区作为检查点

### Phase 2: 后端模型注册表
- 新增模型注册表 VO/DTO/解析器
- 扩展 `SystemConfigService` 支持：
  - 读取/写入模型注册表 JSON
  - 迁移旧 `ai.available-models`
  - 返回用户侧模型选项对象
- 扩展配置接口：
  - 获取模型注册表
  - 保存模型注册表
  - 获取用户可选模型列表
- 为模型注册表解析与默认模型选择补测试

### Phase 3: 运行时模型解析链路
- Java `AiGatewayService` 改为按模型注册表解析：
  - modelName
  - baseUrl
  - apiKey
  - defaultTemperature/maxTokens
- LangGraph 请求增加 provider/model runtime 字段
- Python `langgraph-worker` 改为按请求里的模型条目调用 OpenAI-Compatible
- 为 Java/Python 透传链路补测试

### Phase 4: Prompt 配置契约扩展
- `prompt_config` 扩展输入契约字段：
  - `input_json_schema`
  - `input_example_json`
- 更新：
  - entity / dto / vo / repository / service
  - MySQL schema
  - H2 schema
  - seed/test data
- Prompt 配置页增加 JSON 契约区块
- JSON 契约区块默认锁定，点击编辑后可修改，保存前二次确认
- 补前后端测试

### Phase 5: 趋势分析严格契约化
- 明确趋势输入 JSON 模板与运行时 sourcePayload
- 明确趋势输出 JSON 模板，并写入 Prompt 配置默认值
- `AnalysisService` 调整为：
  - 构建稳定输入 JSON
  - 输出仅做结构保底，不再生成虚假业务语义
- `DataQueryService` 调整为：
  - 优先直接读取结构化 JSON
  - 仅对缺字段返回空集合/空文案，不构造假结论
- 更新相关后端集成测试

### Phase 6: 趋势页与用户选模 UI
- 系统配置页增加模型注册表管理卡片
- 单书分析页与趋势页都接入统一模型下拉
- 趋势页重排布局：
  - 榜单摘要
  - 代表作品
  - 真实词云
  - 题材分布/题材表
  - 趋势洞察
  - 历史对比
- 保持手机端可读性与抽屉关闭体验
- 更新前端测试

### Phase 7: 验证与联调
- 运行后端目标测试
- 运行前端目标测试
- 运行前端构建
- 本地启动前后端与 worker 联调
- 汇总结果、剩余风险、下一步建议

## 文件范围

### 后端
- `backend/src/main/java/com/novelanalyzer/modules/config/**`
- `backend/src/main/java/com/novelanalyzer/modules/analysis/**`
- `backend/src/main/java/com/novelanalyzer/modules/data/**`
- `backend/sql/mysql/phase5-schema.sql`
- `backend/sql/mysql/phase5-seed.sql`
- `backend/src/test/resources/sql/**`
- 相关后端测试类

### Python Worker
- `langgraph-worker/app/models/analysis.py`
- `langgraph-worker/app/services/analysis_service.py`
- `langgraph-worker/app/services/provider_client.py`
- 相关测试

### 前端
- `frontend/src/views/config/system/SystemConfigView.vue`
- `frontend/src/views/config/prompt/PromptConfigView.vue`
- `frontend/src/views/analysis/AnalysisView.vue`
- `frontend/src/views/trend/TrendView.vue`
- `frontend/src/components/trend/**`
- `frontend/src/api/config.ts`
- `frontend/src/types/config.ts`
- `frontend/src/types/data.ts`
- `frontend/src/types/trend.ts`
- 相关前端测试

## 执行顺序约束

1. 先补文档与计划
2. 先补后端测试，再改后端
3. 先打通模型注册表解析，再改 UI
4. 先固化趋势 JSON 契约，再改趋势页展示
5. 所有页面级改动最后统一跑测试和本地联调

## 成功标准

- 管理员可在配置页维护多个模型条目并设置默认模型
- 用户在分析页/趋势页都能选择同一套模型
- 单书分析与趋势分析都按该用户模型执行
- Prompt 配置页能查看并编辑输入/输出 JSON 契约
- 趋势分析结果可直接从结构化 JSON 驱动主要模块展示
- 趋势页图表、词云、摘要、代表作品、洞察卡片在 PC/手机端都可正常使用
- 相关测试通过，联调可运行
