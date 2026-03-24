# LangChain4j AI Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Spring Boot 后端中接入 LangChain4j OpenAI 兼容模型调用，补齐模型参数配置入口，并同步前端接口文档与数据库配置记录。

**Architecture:** 保持现有分析接口、SSE 协议流接口和分析落库逻辑不变，把 AI 网关替换为“系统配置 + 应用配置 + 环境变量”驱动的 LangChain4j 调用层。分析类型级别的参数继续落在 `prompt_config`，供应商级非密钥参数落在 `system_config`。

**Tech Stack:** Spring Boot 3.2, Java 17, LangChain4j, MyBatis-Plus, H2, JUnit 5, MockMvc

---

### Task 1: 先补失败测试覆盖模型配置与 LangChain4j 调用

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\analysis\Phase4AnalysisIntegrationTest.java`
- Modify: `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-data-h2.sql`

- [ ] Step 1: 在分析集成测试中补充 prompt 配置返回模型参数的断言。
- [ ] Step 2: 在分析集成测试中补充 LangChain4j 模型调用成功并落库的失败测试。
- [ ] Step 3: 启动本地 mock OpenAI 兼容服务并把测试属性指到该服务。
- [ ] Step 4: 运行定向测试并确认先红后绿。

### Task 2: 扩展 AI 配置承载层

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\AiProperties.java`
- Modify: `D:\Git\agent\noval\backend\src\main\resources\application.yml`
- Modify: `D:\Git\agent\noval\backend\sql\mysql\phase5-seed.sql`
- Modify: `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-data-h2.sql`

- [ ] Step 1: 为 OpenAI 兼容模型补充应用级默认配置。
- [ ] Step 2: 在种子数据中补充 AI 相关 `system_config` 键。
- [ ] Step 3: 保持密钥只通过环境变量名引用，不写入仓库。

### Task 3: 补齐提示词配置接口字段

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\dto\PromptConfigUpdateRequest.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\vo\PromptConfigVO.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\service\PromptConfigService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\repository\PromptConfigRepository.java`

- [ ] Step 1: 暴露 `temperature`、`maxTokens` 字段。
- [ ] Step 2: 更新保存逻辑，保证编辑现有 prompt 时参数可被持久化。
- [ ] Step 3: 更新返回 VO，保证前端读取到完整配置。

### Task 4: 实现 LangChain4j AI 网关

**Files:**
- Modify: `D:\Git\agent\noval\backend\pom.xml`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\service\AiGatewayService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\service\AnalysisService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\service/SystemConfigService.java`

- [ ] Step 1: 引入 LangChain4j OpenAI 兼容模型依赖。
- [ ] Step 2: 在 AI 网关中实现系统配置解析、模型构建与响应标准化。
- [ ] Step 3: 保留现有落库与 SSE 协议流接口行为。
- [ ] Step 4: 对缺少 key、接口失败和结构化解析失败做显式兜底。

### Task 5: 同步前端接口文档与数据库记录

**Files:**
- Modify: `D:\Git\agent\noval\docs\前端接口设计-v1.md`
- Modify: `D:\Git\agent\noval\docs\项目总设计-v2.md`

- [ ] Step 1: 更新 prompt config DTO 字段说明。
- [ ] Step 2: 更新 system config 固定 key 列表，补充 AI 配置项。
- [ ] Step 3: 记录本轮数据库影响为“无表结构变更，仅新增配置种子项”。

### Task 6: 验证并提交

**Files:**
- Modify: `D:\Git\agent\noval\progress.md`

- [ ] Step 1: 运行 `mvn "-Dtest=Phase4AnalysisIntegrationTest" test` 做第一轮验证。
- [ ] Step 2: 如稳定，再运行更大范围的后端联调测试。
- [ ] Step 3: 仅暂存本轮修改文件并按规范提交 git。
