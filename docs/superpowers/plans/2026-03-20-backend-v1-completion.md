# Backend V1 Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐网文项目 V1 后端未落地能力，迁移主线数据访问到 MyBatis-Plus，并补强 Dify + LangChain4j、Redis 工程配置和 Python 番茄抓取逻辑。

**Architecture:** 以现有 Spring Boot 分层为基础，补齐 `analysis / data / config / crawler` 主链路，并将这些模块的数据访问迁移到 MyBatis-Plus。AI 网关继续以 Dify 为主，使用 LangChain4j 负责模板渲染和链路整理，Python 爬虫保留 FastAPI 契约但实现真实抓取与容错。

**Tech Stack:** Spring Boot 3.2, Java 17, MyBatis-Plus, MySQL 8, Redis 7, LangChain4j, FastAPI, httpx, BeautifulSoup4, lxml

---

### Task 1: 建立 Phase5 测试与数据库脚本基线

**Files:**
- Create: `D:\Git\agent\noval\backend\sql\mysql\phase5-schema.sql`
- Create: `D:\Git\agent\noval\backend\sql\mysql\phase5-seed.sql`
- Create: `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-schema-h2.sql`
- Create: `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-data-h2.sql`

- [ ] Step 1: 写失败测试所需 schema/seed 草案
- [ ] Step 2: 运行相关集成测试，确认因接口/实现缺失而失败
- [ ] Step 3: 完成 MySQL/H2 脚本
- [ ] Step 4: 再次运行测试，确认脚本层面无阻塞

### Task 2: 为主线表引入 MyBatis-Plus 实体与 Mapper

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\MybatisPlusConfig.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\model\CrawlBookEntity.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\model\PromptConfigEntity.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\model\CrawlRankEntity.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\model\CrawlChapterEntity.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\model\AnalysisResultEntity.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\model\SystemConfigEntity.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\mapper\*.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\mapper\*.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\mapper\*.java`

- [ ] Step 1: 写一个最小测试，确保基于 MP 的仓储查询能跑通
- [ ] Step 2: 运行测试确认失败
- [ ] Step 3: 增加实体注解、Mapper 与 `@MapperScan`
- [ ] Step 4: 运行测试确认通过

### Task 3: 迁移 crawler / analysis / config 主线仓储

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\repository\CrawlerRepository.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\repository\AnalysisRepository.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\repository\PromptConfigRepository.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\repository\SystemConfigRepository.java`

- [ ] Step 1: 写失败测试，覆盖历史查询、系统配置查询和趋势查询所需的数据访问
- [ ] Step 2: 运行测试确认失败
- [ ] Step 3: 用 MP 重写相关仓储
- [ ] Step 4: 运行测试确认通过

### Task 4: 补齐系统配置接口

**Files:**
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\controller\SystemConfigController.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\dto\SystemConfigUpdateRequest.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\service\SystemConfigService.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\vo\SystemConfigVO.java`
- Create: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\config\SystemConfigIntegrationTest.java`

- [ ] Step 1: 先写系统配置接口失败测试
- [ ] Step 2: 运行测试确认 404 或断言失败
- [ ] Step 3: 实现 controller/service/repository
- [ ] Step 4: 运行该测试确认通过

### Task 5: 补齐历史与可视化数据接口

**Files:**
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\controller\DataController.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\service\DataQueryService.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\vo\*.java`
- Create: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\data\DataQueryIntegrationTest.java`

- [ ] Step 1: 写 `history` 和 `visual` 两个接口的失败测试
- [ ] Step 2: 运行测试确认失败
- [ ] Step 3: 实现聚合查询与返回结构
- [ ] Step 4: 运行测试确认通过

### Task 6: 补齐趋势分析接口并增强 AI 网关

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\controller\AnalysisController.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\service\AnalysisService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\service\AiGatewayService.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\vo\TrendAnalysisVO.java`
- Create: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\analysis\TrendAnalysisIntegrationTest.java`

- [ ] Step 1: 写趋势分析失败测试
- [ ] Step 2: 运行测试确认失败
- [ ] Step 3: 用 LangChain4j 模板渲染改造 AI 网关，并补齐 Dify 响应解析/fallback
- [ ] Step 4: 实现趋势分析接口
- [ ] Step 5: 运行测试确认通过

### Task 7: 补齐 Redis 与部署工程支撑

**Files:**
- Create: `D:\Git\agent\noval\redis\redis.conf`
- Create: `D:\Git\agent\noval\docker-compose.yml`
- Modify: `D:\Git\agent\noval\backend\src\main\resources\application.yml`

- [ ] Step 1: 写出默认工程支撑配置
- [ ] Step 2: 校验配置与文档、环境变量命名一致
- [ ] Step 3: 如有必要补充测试或启动说明

### Task 8: 补强 Python 番茄抓取链路

**Files:**
- Modify: `D:\Git\agent\noval\crawler\app\services\fanqie_crawler.py`
- Create: `D:\Git\agent\noval\crawler\app\utils\http_client.py`
- Create: `D:\Git\agent\noval\crawler\app\utils\parsers.py`
- Create: `D:\Git\agent\noval\crawler\app\utils\__init__.py`
- Create: `D:\Git\agent\noval\crawler\tests\test_fanqie_crawler.py`
- Modify: `D:\Git\agent\noval\crawler\requirements.txt`

- [ ] Step 1: 写 Python 爬虫失败测试或最小可验证用例
- [ ] Step 2: 运行测试确认失败
- [ ] Step 3: 实现真实抓取、清洗与异常处理
- [ ] Step 4: 运行测试确认通过

### Task 9: 全量验证

**Files:**
- Modify: `D:\Git\agent\noval\task_plan.md`
- Modify: `D:\Git\agent\noval\findings.md`
- Modify: `D:\Git\agent\noval\progress.md`

- [ ] Step 1: 运行 `backend` 相关测试
- [ ] Step 2: 运行 `crawler` 相关测试
- [ ] Step 3: 检查关键配置文件与脚本
- [ ] Step 4: 汇总结果与剩余风险
