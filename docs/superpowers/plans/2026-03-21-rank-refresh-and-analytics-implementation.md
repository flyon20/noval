# 榜单刷新与数据分析补齐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为榜单抓取、详情/章节读取、AI 重分析和数据分析接口补齐业务刷新规则、结构化结果和异常补抓能力。

**Architecture:** 保持现有 Spring Boot + MyBatis-Plus + FastAPI 主链路不变，在 Java 侧新增刷新策略与配置读取，让数据库快照成为业务主数据源；Redis 继续作为加速层。趋势分析结果统一结构化后落库，再由 `data` 模块聚合成词云和最近三次对比视图。

**Tech Stack:** Spring Boot 3.2, Java 17, MyBatis-Plus, MySQL 8, H2, JUnit 5, MockMvc, FastAPI

---

### Task 1: 扩展失败测试覆盖刷新与重分析行为

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\crawler\CrawlerPhase3IntegrationTest.java`
- Modify: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\analysis\Phase4AnalysisIntegrationTest.java`
- Modify: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\data\Phase5BackendIntegrationTest.java`

- [ ] Step 1: 为榜单 `AUTO` 模式命中数据库快照补失败测试。
- [ ] Step 2: 为榜单 `FORCE` 模式的冷却和次数限制补失败测试。
- [ ] Step 3: 为 `forceReanalyze=true` 重新生成分析记录补失败测试。
- [ ] Step 4: 为 `visual` 返回词云、题材表和最近三次对比结构补失败测试。
- [ ] Step 5: 运行定向测试并确认因功能缺失而失败。

### Task 2: 补 SQL 与种子配置

**Files:**
- Modify: `D:\Git\agent\noval\backend\sql\mysql\phase3-schema.sql`
- Modify: `D:\Git\agent\noval\backend\sql\mysql\phase5-seed.sql`
- Modify: `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-data-h2.sql`

- [ ] Step 1: 为系统配置补齐刷新与强刷限制种子数据。
- [ ] Step 2: 为书籍去重与修链所需查询策略补数据库支持。
- [ ] Step 3: 运行相关测试，确认 schema/seed 可被加载。

### Task 3: 实现系统配置读取与刷新策略模型

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\service\SystemConfigService.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\model\RankRefreshMode.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\service\CrawlerRefreshPolicyService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\dto\CrawlerRankRequest.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\dto\AnalysisRequest.java`

- [ ] Step 1: 添加刷新模式、强刷原因、重分析开关字段。
- [ ] Step 2: 在系统配置服务中增加数值/布尔配置读取能力。
- [ ] Step 3: 实现榜单自动刷新、强刷冷却、强刷次数判定。
- [ ] Step 4: 运行相关测试确认策略层通过。

### Task 4: 改造爬虫仓储与服务

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\repository\CrawlerRepository.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\service\CrawlerService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\model\CrawlBookEntity.java`

- [ ] Step 1: 让书籍查询优先按 `platform_book_id` 复用旧记录。
- [ ] Step 2: 增加榜单快照读取、最近快照时间读取和刷新任务审计。
- [ ] Step 3: 在 `AUTO` 下优先返回数据库快照，在 `FORCE` 下校验冷却和次数。
- [ ] Step 4: 为详情/章节增加“修链后重试一次”的兜底逻辑。
- [ ] Step 5: 运行 crawler 相关测试确认通过。

### Task 5: 改造 AI 分析与趋势结构

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\service\AnalysisService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\service\AiGatewayService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\vo\AnalysisResultVO.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\vo\TrendAnalysisVO.java`

- [ ] Step 1: 为拆文类接口实现 `forceReanalyze=true` 时跳过分析缓存。
- [ ] Step 2: 规范趋势分析结构化结果，产出词云、题材分布、题材表和三次对比摘要。
- [ ] Step 3: 将清洗后的结构化 JSON 落入 `analysis_result.result_json`。
- [ ] Step 4: 运行 analysis 相关测试确认通过。

### Task 6: 扩展数据分析返回结构

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\service\DataQueryService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\vo\VisualDataVO.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\vo\ThemeWordCloudItemVO.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\vo\ThemeTableItemVO.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\vo\SnapshotThemeComparisonVO.java`

- [ ] Step 1: 从最近一次 `theme` 分析结果中抽取词云和题材表。
- [ ] Step 2: 组装最近三次快照主题变化摘要。
- [ ] Step 3: 保持缺失趋势分析时返回空集合而非异常。
- [ ] Step 4: 运行 data 相关测试确认通过。

### Task 7: 全量验证

**Files:**
- Modify: `D:\Git\agent\noval\progress.md`

- [ ] Step 1: 运行 `mvn -Dtest=CrawlerPhase3IntegrationTest,Phase4AnalysisIntegrationTest,Phase5BackendIntegrationTest test`
- [ ] Step 2: 如有必要补跑 `mvn test`
- [ ] Step 3: 记录验证结果、未覆盖风险与后续前端接入注意点
