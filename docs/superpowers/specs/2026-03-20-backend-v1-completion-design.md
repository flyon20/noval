# 网文项目后端 V1 补齐设计

## 目标

在不扩展到二期范围的前提下，补齐 `docs/项目总设计-v2.md` 中已承诺但当前仓库尚未真正落地的后端、数据库、Redis、部署与 Python 爬虫能力，并让实现与文档保持一致。

## 设计边界

- 仅覆盖 V1 范围：`fanqie` 平台、鉴权、安全、扫榜、章节抓取、AI 分析、提示词配置、系统配置、历史查询、可视化数据、趋势分析。
- 不实现起点真实抓取，不扩展验证码体系，不增加额外遥测。
- 本轮将补齐后端主链路与工程支撑，不做与 V1 无关的大规模重构。

## 关键现状

- Spring Boot 工程已具备基础能力，但数据访问仍以 `JdbcTemplate` 为主，MyBatis-Plus 只停留在依赖和分页插件层面。
- Python 爬虫已有 FastAPI 接口骨架，但 `fanqie_crawler.py` 仍主要返回样例数据。
- AI 部分已有 Dify 直调骨架与 fallback 文本拼装，但 LangChain4j 尚未真正用于业务链路。
- 文档承诺的下列接口尚未落地：
  - `GET /api/analysis/trend`
  - `GET /api/data/visual`
  - `GET /api/data/history`
  - `GET/PUT /api/config/system`

## 总体方案

### 1. 数据访问层回到 MyBatis-Plus 主路径

- 为本轮涉及的主线表引入实体注解与 Mapper：
  - `crawl_book`
  - `crawl_rank`
  - `crawl_chapter`
  - `prompt_config`
  - `analysis_result`
  - `system_config`
- 优先把 `crawler / analysis / config / data / system` 主线迁到 MyBatis-Plus。
- 安全鉴权相关仓储如无必要不做大范围迁移，避免在本轮把风险扩散到非目标链路。

### 2. 补齐缺失接口

#### 2.1 `GET /api/analysis/trend`

- 查询指定平台、可选榜单分类最近三次抓取快照。
- 将榜单书名、简介、作者等聚合后交给 AI 网关生成“题材趋势分析”。
- 使用 `theme` 类型提示词；若已有缓存或近似历史结果则优先复用。
- 将趋势分析结果持久化到 `analysis_result`，便于后续历史回看。

#### 2.2 `GET /api/data/history`

- 提供分析历史列表查询。
- 支持按 `platform`、`bookId`、`analysisType`、`limit` 过滤。
- 返回最近记录及其分析元信息，支撑前端历史列表与回看页面。

#### 2.3 `GET /api/data/visual`

- 返回图表友好的聚合统计数据，优先落地稳定的一期统计：
  - 分析类型分布
  - 最近 N 天分析次数趋势
  - 最近榜单分类分布
  - 最近三次抓取摘要
- 统一由后端聚合，前端直接渲染。

#### 2.4 `GET/PUT /api/config/system`

- 通过 `system_config` 管理运行时系统配置。
- 支持按 `configKey` 查询单项配置。
- 支持更新单项配置值，并保留可编辑性判断。

### 3. AI 网关补强：Dify + LangChain4j + fallback

- 保留 Dify 作为主通道。
- 使用 LangChain4j 的模板能力负责提示词渲染，不再手写 `replace("{{content}}", ...)`。
- 规范化 Dify 响应解析，优先读取工作流输出；若 Dify 不可用或返回异常，走 fallback 通道。
- fallback 继续保留，但输出结构要稳定，并附带模型名、token 估算和 trace 可追踪信息。

### 4. Python 爬虫补强

- 将 `fanqie_crawler.py` 从样例数据升级为真实抓取逻辑。
- 补齐：
  - HTTP 请求封装
  - HTML/JSON 数据解析
  - 基础清洗与容错
  - 抓取失败的友好异常
- 保留接口契约不变，Java 侧无需跟着大改。
- 如目标页面结构变化或被拦截，则保留清晰降级信息，Java 端继续可走缓存/兜底。

### 5. Redis 与工程支撑

- 新增 `redis/redis.conf`，对齐历史文档中的安全与内存策略建议。
- 新增 `docker-compose.yml`，串联 backend / crawler / mysql / redis。
- 保持当前 Redis 不可用时的本地降级能力，但把“正确配置 Redis”作为默认主路径。

## 数据库设计补充

### 新增

- `system_config`
- Phase5 测试所需 H2 schema/seed

### 调整

- `prompt_config` 初始化数据增加 `theme` 相关提示词
- 若可视化/历史查询不需要新表，则优先复用 `analysis_result` 与抓取表，避免无意义扩表

## 错误处理与降级

- Dify 失败：记录失败原因，回退到 fallback 结果构造。
- Python 爬虫失败：Java 端优先尝试缓存，若无缓存则返回清晰业务错误。
- Redis 不可用：继续使用现有本地降级缓存与本地黑名单/限流计数。

## 测试策略

- 新增后端集成测试覆盖：
  - 趋势分析接口
  - 系统配置接口
  - 历史查询接口
  - 可视化数据接口
- Python 侧补充单元测试或至少保证可启动与接口冒烟。
- 所有新增行为先写失败测试，再补实现。

## 实施假设

- 用户刚刚的确认消息视为对该方案的批准，可直接进入实现。
- LangChain4j 仅以提示词模板与链路组织能力接入，不引入额外模型 SDK。
- 若番茄页面存在强反爬或结构波动，本轮目标是“可抓取并有清晰降级”，不是保证所有页面永久稳定。
