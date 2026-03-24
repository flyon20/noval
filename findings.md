# Findings & Decisions

## Requirements
- 阅读 `docs/` 下项目设计文档和其中提到的初始版技术文档。
- 结合这些文档检查当前后端实现。
- 对未完成的后端功能进行补齐。
- 检查并补充数据库设计、建表脚本、初始化数据与 Redis 相关配置/使用。
- 用户已确认：未落地的工程支撑也一并补齐。

## Research Findings
- 当前项目文档主基线为 `D:\Git\agent\noval\docs\项目总设计-v2.md`，历史来源是 `D:\Git\myNote\学习笔记\项目文档\网文项目文档.md`。
- 当前后端已落地：健康检查、登录/刷新/登出、角色拦截、限流、IP 黑名单、番茄榜单/详情/章节抓取、三类分析、提示词配置。
- 当前后端未落地或未完整落地：
- `GET /api/analysis/trend`
- `GET /api/data/visual`
- `GET /api/data/history`
- `GET/PUT /api/config/system`
- `system_config` 表及相关仓储/服务/控制器
- Redis 安全配置文件与 Compose 级工程支撑
- 历史文档还提到 `user_book`、`crawler_task`、更细的 Redis/部署支撑；其中 `crawler_task` 表已在 Phase 3 schema 中，`user_book` 尚未落地。
- 现有 `CrawlerCacheService` 已实现 Redis 失败时的本地缓存降级；`RateLimitService`、`TokenBlacklistService` 也支持 Redis 不可用时的本地退化。
- `analysis_result` 表当前可支撑分析历史与趋势统计，但缺少对应查询接口与统计逻辑。
- `PromptConfigRepository` 仅支持按类型默认查询与单条保存，不支持更完整的配置管理能力。
- 番茄榜单页 `https://fanqienovel.com/rank/...`、书籍页 `https://fanqienovel.com/page/...`、正文页 `https://fanqienovel.com/reader/...` 都包含可直接解析的 `window.__INITIAL_STATE__`。
- 番茄榜单页内的 `rank.book_list` 可提供榜单书籍数据；书籍页内的 `page.itemIds` / `page.chapterListWithVolume` 可提供章节目录； reader 页内的 `reader.chapterData.content` 可直接提供正文 HTML。
- LangChain4j 本轮已通过 `PromptTemplate` 接入提示词渲染，不再只是 Maven 依赖存在但业务未使用。

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 优先补齐文档已公开承诺的接口 | 直接消除“文档与实现不一致”问题 |
| 复用现有 `JdbcTemplate` 风格继续补仓储层 | 保持现有代码风格一致，避免引入额外 ORM 复杂度 |
| 数据分析接口尽量基于现有 `analysis_result`、`crawl_rank`、`crawl_book`、`crawl_chapter` 产出 | 降低 schema 变更范围，优先把现有数据利用起来 |
| Redis 工程支撑补齐到配置文件和 Compose 层 | 让缓存、安全、部署文档与项目结构更一致 |
| 主线仓储改造为 MyBatis-Plus | 回应用户对 ORM 的明确要求 |
| AI 网关保留 Dify 主通道，同时使用 LangChain4j 模板渲染和 fallback | 让 Dify + LangChain4j 都真正落地，同时保留降级能力 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| 历史文档首次读取为乱码 | 改用 UTF-8 重新读取后得到正确中文内容 |
| 当前仓库目录是 `docs/` 而不是用户口述的 `doc/` | 按实际目录继续执行，不影响需求理解 |
| Windows 对超长补丁字符串有限制 | 将大补丁拆分为多段小补丁顺序提交 |
| Redis 配置文件不支持直接解析 `${REDIS_PASSWORD}` | 改为 Compose 启动命令注入密码，配置文件仅保留通用项 |

## Resources
- `D:\Git\agent\noval\docs\项目总设计-v2.md`
- `D:\Git\agent\noval\docs\分步开发计划.md`
- `D:\Git\myNote\学习笔记\项目文档\网文项目文档.md`
- `D:\Git\agent\noval\backend\src\main\resources\application.yml`
- `D:\Git\agent\noval\backend\sql\mysql\phase2-schema.sql`
- `D:\Git\agent\noval\backend\sql\mysql\phase3-schema.sql`
- `D:\Git\agent\noval\backend\sql\mysql\phase4-schema.sql`
- `D:\Git\agent\noval\docs\superpowers\specs\2026-03-20-backend-v1-completion-design.md`
- `D:\Git\agent\noval\docs\superpowers\plans\2026-03-20-backend-v1-completion.md`

## Visual/Browser Findings
- 本轮未使用浏览器/图片类上下文。
## Session Addendum 2026-03-21
- Confirmed `muye_a00bd8bc.js` uses `/api/reader/full` and stores confuse font config from `x-tt-zhal` response headers.
- Confirmed `x-tt-zhal` is font configuration, not plaintext mapping.
- Implemented Python-side deobfuscation using font caching plus OCR mapping for the current Fanqie font signature.
- Real sample validation: the fetched chapter content is readable Chinese and `PUA` count is 0 after decoding.
## Session Addendum 2026-03-21 (Backend Review)
- Review scope has expanded from feature completion to full backend operability under authentication, role checks, rate limit, validation failure, and fallback behavior.
- Primary baseline remains `docs/项目总设计-v2.md`, with special attention to auth, crawler, analysis, config, data, system, and shared response/error semantics.
- Current automated coverage exists for auth/security, crawler, analysis, data, and system health, but controller edge cases and cross-cutting exception/security behavior still need a matrix review.
- Endpoint review confirmed every documented backend controller is present: auth, crawler, analysis, config, data, system, and security probe endpoints.
- Real review gaps found by test-first verification:
  - `/api/auth/logout` was outside the protected path prefixes, so it accepted a valid body token without any authenticated caller context.
  - Missing required query parameters such as `/api/config/system?configKey=...` fell through as `500` instead of the documented `400`.
  - Blank validated query parameters such as `/api/analysis/trend?platform= ` raised `HandlerMethodValidationException` and also surfaced as `500`.
- Security regression tests now cover:
  - logout without `Authorization` must return `401`
  - system config is still admin-only and `USER` role gets `403`
  - missing/blank required query parameters return `400`
## Session Addendum 2026-03-21 (Prompt Config Safety)
- `PromptConfigController` intentionally keeps write access open to both `ADMIN` and `USER`; this is now treated as a confirmed product rule, not a defect.
- `AiGatewayService` already has both AI integration pieces in code:
  - LangChain4j prompt rendering via `PromptTemplate`
  - Dify workflow invocation via `POST /workflows/run`
- Current default deployment is still "code-ready but not fully activated for Dify":
  - current shell has no `DIFY_*` environment variables
  - `phase4-seed.sql` and `phase5-seed.sql` keep `dify_workflow_id` empty
  - `docker-compose.yml` only provides `DIFY_API_KEY_REF`, not the actual `DIFY_API_KEY`
- Two concrete follow-up risks were identified and fixed:
  - prompt config updates could be masked by stale analysis cache because cache keys did not depend on prompt state
  - prompt content could be saved without `{{content}}`, breaking real analysis input injection
- Regression coverage now explicitly confirms:
  - `USER` can update prompt config
  - invalid prompt content is rejected with `400`
  - repeated identical analysis requests use the updated prompt after a prompt change

## Session Addendum 2026-03-24

### Confirmed Auth Findings
- `frontend/src/views/login/LoginView.vue` only validates required username/password and confirm-password equality.
- Registration currently has no visible password rules and no password strength enforcement in `AuthService`.
- Backend login failure semantics are too coarse and still return English text like `username or password is incorrect`.
- `frontend/src/lib/http-error.ts` passes backend or axios messages through too directly, which is why odd numeric or technical text can reach the UI.

### Confirmed Trend Findings
- `frontend/src/views/trend/TrendView.vue` still auto-starts trend analysis on mount via `initializePage()`.
- `frontend/src/composables/useTrendRun.ts` defaults to a fake category (`male-hot-a`) instead of the user-selected board context.
- `frontend/src/views/rank/RankView.vue` already persists board preference through `/api/crawler/preference`, so trend can use that as its default context source.
- `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java` builds trend analysis from free-form snapshot text and stores a generic `theme` result, but does not treat the board as a first-class analysis subject.
- `backend/src/main/java/com/novelanalyzer/modules/data/service/DataQueryService.java` returns platform-level visualization data and reads only the latest theme result without board scoping.
- Current theme prompt seeds in `backend/sql/mysql/phase5-seed.sql` and `backend/src/test/resources/sql/phase5-data-h2.sql` only define a shallow JSON contract and do not cover the required board-history word cloud or richer trend fields.

### Implementation Direction Locked
- Use `platform + channelCode + boardCode` as the canonical trend context.
- Constrain trend AI output to structured JSON and parse/store it directly for rendering.
- Keep the trend result preview around 300 characters with full details in a closable drawer/modal.
- Treat mobile usability as a first-class requirement for both auth and trend pages.

### Implementation Findings: 2026-03-24 Execution
- Backend trend reuse now depends on `analysis_result.channel_code + board_code + snapshot_id`, which lets the API reuse the latest board-scoped result instead of recomputing on every request.
- Board-level visualization now reads `rank_board`, the latest three `rank_snapshot` rows, and the corresponding `crawl_rank` rows, then combines them with the latest structured theme result.
- The previous front-end failures were caused by three independent mismatches:
  - `TrendView.vue` still auto-ran trend analysis on mount
  - `useTrendRun.ts` still built requests with legacy `category`
  - chart and summary rendering still depended on removed platform-level fields like `analysisDailyTrend`
- A safe migration path was to replace the trend page contract entirely rather than partially shim the old fields, because the user requirement is fundamentally board-scoped and click-to-run.
- Mobile usability improved most by keeping the switchable board pills, shortening the default preview, and leaving the long-form result inside a closable drawer instead of in-page overflow blocks.

## Session Addendum 2026-03-25

### Confirmed Model Configuration Findings
- `SystemConfigView.vue` still treats AI models as a plain comma-separated string stored in `ai.available-models`, which cannot carry per-model base URL, API key, or temperature metadata.
- `AnalysisView.vue` already has a user-facing model dropdown backed by `user_config.ai.preferred-model`, but the available options are only raw strings without labels or model-specific runtime config.
- `AiGatewayService` resolves the effective model name from prompt config, then user preference, then system default, but it still takes base URL and API key from one global OpenAI-compatible config source.
- `LangGraphAnalysisService` in Python also uses a single global `openai_base_url` and `openai_api_key`, so it cannot currently honor different provider endpoints per selected model.

### Confirmed Prompt / Contract Findings
- `PromptConfigView.vue` already exposes output schema/example JSON and parse config, but there is no input contract JSON shown to admins.
- `PromptConfigService` validates only `{{content}}` and saves prompt rows by `promptType + promptName`; it has no concept of guarded JSON-contract editing.
- `PromptConfigEntity` has output-side fields only; there is no `input_json_schema` or `input_example_json` column yet.

### Confirmed Trend Contract Findings
- `AnalysisService.buildTrendInputText(...)` still injects a free-form English task string; the runtime mentions required fields, but the real source payload contract is not exposed in the admin UI.
- `AnalysisService.normalizeTrendResultJson(...)` still manufactures placeholder `themeTable`, `historicalWordCloud`, `hotBooks`, `insightCards`, and summary text when the model response is incomplete, which hides contract drift instead of surfacing it.
- `DataQueryService` still has a second layer of fallback synthesis for trend charts and cards, so the trend page is not yet truly rendering “what the model analyzed and stored”.
- `TrendTagCloud.vue` is already tag-cloud-like, but the surrounding trend page still mixes fallback chart bars and summary cards that are not strictly derived from the intended schema.

### Implementation Direction Locked
- Introduce a structured model registry config and migrate available-model/user-selection flows to use model keys + labels instead of raw comma-separated strings.
- Extend prompt config with input-contract JSON fields and guarded edit mode for JSON contract boxes.
- Make trend analysis runtime inject a stable board-scoped source JSON contract and enforce a richer output JSON contract that includes board summary, representative works, word cloud, theme distribution, hot books, and insight cards.
- Reduce backend trend fallbacks from “invent missing fields” to “normalize shape + preserve available data”, so stored JSON becomes the source of truth for the trend page.
