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
- The execution phase confirmed that the highest-risk bug source was silent fallback synthesis, not parsing itself. Tightening the pipeline to "shape only, no fabricated meaning" keeps stored JSON trustworthy enough for direct rendering.
- The safest compatibility compromise is to keep deriving structural equivalents such as word cloud from `themeDistribution` or `themeTable`, while stopping semantic fallbacks such as invented board summaries or hot titles from board metadata.
- Desktop whitespace in `TrendView` is better solved by filling the result column with structured support cards than by lengthening the preview again; this preserves the quick-read interaction while improving PC density.
- Mobile compatibility stayed stable because the page still uses click-to-run plus a closable detail drawer, with the new support grid simply collapsing to one column under `760px`.
- New regression coverage now explicitly protects backend trend contract fields, structured-first trend display shaping, and manual-run-only trend page behavior.
- Reduce backend trend fallbacks from 鈥渋nvent missing fields鈥?to 鈥渘ormalize shape + preserve available data鈥? so stored JSON becomes the source of truth for the trend page.

## Session Addendum 2026-03-25 (Prompt Contract Visibility Follow-up)

### Reproduced State
- Local DB query confirms all four active `prompt_config` rows have empty contract fields:
  - `input_json_schema`
  - `input_example_json`
  - `output_json_schema`
  - `output_example_json`
  - `parse_config_json`
- `system_config.ai.model-registry.json` is already populated locally with at least one model entry (`deepseek-chat`), so the remaining gap on the model side is presentation/management polish rather than raw missing data.
- The prompt-config and model-registry APIs are auth-protected in the current local runtime, so DB inspection is the fastest trustworthy confirmation path for this round.

### Root Cause
- Prompt contract fields were added to schema, DTOs, and UI, but existing MySQL rows were never backfilled.
- `PromptConfigService.getByType(...)` still returns DB rows as-is and does not enrich missing contract fields from system defaults.
- Current seed data only writes contract payloads for `theme`; `deconstruct`, `structure`, and `plot` still have no system default contract data in seeded environments.

### Locked Implementation Direction
- Introduce a backend-owned default prompt contract catalog for all four prompt types.
- Backfill missing contract fields on read/startup so legacy DB rows become visible in the admin page immediately.
- Keep prompt body editable, but clearly label the JSON sections as framework-enforced structure constraints.
- Tighten trend rendering to prefer stored contract data and replace the fake bar-style word cloud path with a real tag-cloud rendering path.
- Reduce backend trend fallbacks from 鈥渋nvent missing fields鈥?to 鈥渘ormalize shape + preserve available data鈥? so stored JSON becomes the source of truth for the trend page.

## Session Addendum 2026-03-26

### Confirmed Project Shape
- The project is a four-part local stack:
  - `frontend/`: Vue 3 + Vite + Element Plus + Pinia, with `/api` proxied to the backend.
  - `backend/`: Spring Boot 3.2 + MyBatis-Plus + Redis, exposing auth/crawler/analysis/config/data/system APIs.
  - `crawler/`: FastAPI service for rank/book/chapter crawling, protected by `CRAWLER_INTERNAL_API_KEY`.
  - `langgraph-worker/`: FastAPI service for AI analysis orchestration, protected by `AI_LANGGRAPH_WORKER_INTERNAL_API_KEY`.
- The runtime entrypoints are straightforward:
  - frontend starts from `frontend/node_modules/.bin/vite(.cmd)`
  - backend starts from `com.novelanalyzer.NovelAnalyzerApplication`
  - crawler starts from `crawler/app/main.py`
  - langgraph-worker starts from `langgraph-worker/app/main.py`

### Confirmed Startup Constraints
- `docker-compose.yml` is available for integrated startup, but it is not usable in the current machine because `docker` is not installed.
- The documented Windows local run path in `docs/本地联调说明.md` is valid and is the safest path in this environment.
- Backend startup is guarded by:
  - `JWT_SECRET` length validation
  - `CRAWLER_INTERNAL_API_KEY` presence/length validation
- Crawler and langgraph-worker each fail fast when their internal API keys are missing.
- Frontend defaults its proxy target to `http://127.0.0.1:8080`, so a local backend is enough for interactive development.

### Local Environment Findings
- Available locally: Java 17, Maven 3.8.1, Node 22.12.0, npm 9.6.5, Python 3.12.0, Redis executable at `D:/ProTools/redis/Redis-x64-5.0.14.1/redis-server.exe`.
- Present in the shell environment: `DEEPSEEK_API_KEY` exists, so AI-capable flows have a configured model key source without writing secrets into repo files.
- MySQL is already listening on `3306`, but the chosen local run path intentionally avoids relying on the current local MySQL schema state by using H2 for the backend.

### Locked Runbook
- Redis:
  - local `redis-server.exe` on `127.0.0.1:6379` with `appendonly no`
- Crawler:
  - `python -m uvicorn app.main:app --host 127.0.0.1 --port 5000`
- LangGraph worker:
  - `python -m uvicorn app.main:app --host 127.0.0.1 --port 8001`
- Backend:
  - `mvn spring-boot:run -Dspring-boot.run.useTestClasspath=true ...` with H2 datasource env vars and local service URLs
- Frontend:
  - `node_modules/.bin/vite.cmd --host=127.0.0.1 --port=5173` with `VITE_PROXY_TARGET=http://127.0.0.1:8080`

### Verified Runtime Result
- The full local stack is up and reachable on expected ports.
- Browser verification shows the frontend resolves to `/login` and renders the login form correctly.
- Backend health is reachable directly on `8080` and through the frontend proxy on `5173`.
- Reduce backend trend fallbacks from “invent missing fields” to “normalize shape + preserve available data”, so stored JSON becomes the source of truth for the trend page.


## Session Addendum 2026-03-26 (Analysis Chain Deep Dive)

### Confirmed Document vs Code Baseline
- `README.md` and `docs/project-design-v2.md` still describe the current stable baseline as `LangChain4j + OpenAI-Compatible`, with Dify as an optional/fallback path.
- `docs/superpowers/specs/2026-03-24-phase2-langgraph-multi-agent-design.md` describes the intended second-phase target architecture, not the fully landed current implementation.
- Therefore the repo should be understood as "dual-runtime, migration-in-progress", not "already fully switched to LangGraph multi-agent".

### Confirmed End-to-End Analysis Chain
- Single-book analysis path:
  - frontend `analysisApi.streamDeconstruct/streamStructure/streamPlot`
  - backend `AnalysisController`
  - backend `AnalysisService.streamAnalyze(...)` / `analyze(...)`
  - chapter loading through `CrawlerService.getChapters(...)`
  - runtime branch:
    - `legacy`: `AiGatewayService`
    - `langgraph`: `LangGraphWorkerClient` -> Python worker
  - result persistence into `analysis_result`
  - history/readback through `DataQueryService.getHistory(...)`
- Trend analysis path:
  - frontend `analysisApi.streamTrend(...)`
  - backend `AnalysisService.streamTrend(...)` / `analyzeTrend(...)`
  - board + snapshot + rank rows loaded from `rank_board` / `rank_snapshot` / `crawl_rank`
  - runtime branch to `AiGatewayService` or `LangGraphWorkerClient`
  - backend normalizes trend JSON before saving and before readback
  - frontend chart/data panels poll `/api/data/visual`

### Confirmed Runtime Organization
- Java is the orchestration layer:
  - prompt lookup
  - model resolution
  - cache reuse
  - crawler data loading
  - SSE outward protocol
  - DB persistence
- Python langgraph-worker is an internal AI execution layer:
  - receives prepared request payloads from Java
  - does not read MySQL/Redis directly
  - calls OpenAI-compatible provider
  - returns blocking or streaming AI results plus runtime metrics

### Confirmed Model / Prompt Resolution Rules
- User-selected model is stored in `user_config.ai.preferred-model`.
- Backend resolves the effective runtime model from:
  1. user preference
  2. system model registry default
  3. legacy global OpenAI-compatible config
  4. prompt-level `modelName` as compatibility fallback
- Prompt config is not just plain prompt text anymore; it also carries JSON contracts and parse instructions, especially for `theme`.

### Confirmed LangGraph Reality Check
- Current worker code contains a single `LangGraphAnalysisService` and a single compiled `StateGraph`.
- `agentType` is used as a mode switch (`deconstruct` / `structure` / `plot` / `trend_theme`), but there are no separately implemented `deconstruct_agent.py`, `structure_agent.py`, `plot_agent.py`, or `trend_agent.py` files yet.
- The graph currently focuses on:
  - request preparation
  - direct vs chunk route
  - chunk fan-out/fan-in
  - JSON parsing / repair
  - runtime metrics attachment
- This means the repo is already using LangGraph, but still in a "minimal graph runtime" stage rather than the full doc-described multi-agent stage.

### Confirmed Trend Contract Strategy
- Trend analysis is built around board-scoped structured JSON, not broad platform summaries.
- Backend intentionally normalizes structure and avoids fabricating semantic business conclusions from missing fields.
- `DataQueryService` and `TrendResultJsonUtils` are the read-side shaping layer for trend history, word cloud, theme table, hot books, and insight cards.


## Session Addendum 2026-03-26 (Single-book 10-Chapter Stream Fix)

### Reproduced Root Cause
- Backend log showed the single-book stream failure was not caused by LangGraph first; the immediate upstream failure was crawler chapter fetching returning `500`.
- Crawler log showed one specific reader URL inside the requested chapter range returned `404`, and `FanqieCrawler.fetch_chapters(...)` previously failed the whole request when any submitted chapter future raised.
- Frontend stream runtime also treated `[analysis-progress] ...` as a real `delta`, which meant:
  - the placeholder text leaked into the visible result area on failure
  - fallback-to-blocking was disabled because the runner thought real stream content had already started

### Fixed Behavior
- Chapter fetching now skips per-chapter failures and continues scanning later chapter refs to backfill as many valid chapters as possible inside the requested range tail.
- If at least one chapter succeeds, crawler returns the available set instead of crashing the whole request.
- Frontend stream runner now treats `[analysis-progress]` as transport-level placeholder progress, not user-facing?? delta.
- Real?????????? token `delta` ????????????????????????? fallback?


## Session Addendum 2026-03-26 (Actual Chapter Count UX)

### Confirmed UI/Backend Design
- The cleanest place to carry actual fetched chapter count is `analysis_result.result_json`, because the analysis page already reads result metadata from `resultJson` and persisted/cached results flow through that same shape.
- Adding a brand-new top-level response field was unnecessary for this round; enriching `resultJson` keeps the diff smaller and avoids widening more DTO/API surface than needed.
- The analysis page now distinguishes between:
  - requested chapter count
  - actual successfully fetched chapter count
- When actual < requested, the page shows a ratio-style label instead of pretending all requested chapters were fetched.


## Session Addendum 2026-03-26 (10-Chapter Timeout Budget)

### Confirmed Timeout Root Cause
- The new failure was a two-layer 15-second budget collision:
  - backend `AiGatewayService` still used the default `ai.timeout.millis=15000` for legacy single-book OpenAI-compatible calls
  - frontend blocking fallback request also used Axios `timeout=15000`
- For 10-chapter single-book analysis, especially when stream falls back before any real token arrives, 15 seconds is too short even after chapter-fetch degradation is handled.

### Fixed Strategy
- Single-book long analysis now gets a longer backend AI timeout budget when:
  - chunk mode is active, or
  - effective requested chapter count reaches 10
- Blocking fallback requests from the frontend now also use a longer timeout for 10-chapter single-book analysis, so the browser no longer aborts earlier than the backend budget.


## Session Addendum 2026-03-26 (Forced Chunking For 8+ Chapters)

### Confirmed Final Root Cause
- Even after lifting the timeout budget from 15s to 60s, the 10-chapter path still timed out because the current legacy single-book analysis often stayed on one very large LLM call.
- The existing DeepSeek-oriented chunk thresholds were so high that 8-10 chapters of real content could still avoid chunk mode.
- When the analysis page launched all three panels, the provider ended up handling multiple long blocking calls in parallel, making timeouts much more likely.

### Final Fix Direction
- Force chunk splitting for large single-book analyses (`8+` fetched chapters), independent of token estimation.
- Keep per-call backend timeout lifting for long book analysis.
- Increase frontend blocking fallback timeout further so chunk + merge has enough whole-request budget.


## Session Addendum 2026-03-26 (Persistent Analysis/Trend Context)

### Confirmed UX Direction
- Analysis page and trend page should treat the current object being viewed as a user-level workspace context, not as a transient route-only parameter.
- The existing generic `user_config` API is already sufficient to persist these contexts; no backend schema or controller change is required for this round.
- The clean split is:
  - `analysis.current-context`: current book + chapterCount + title/author + active analysis tab
  - `trend.current-context`: current board selection
  - `rank.chapter-count`: rank-page chapter-count preference
- Trend page should prefer its own persisted context over rank-page board preference, otherwise changing boards in `/rank` would unexpectedly hijack `/trend` on the next visit.
- Analysis page should restore results from `/api/data/history` rather than auto-rerunning analysis; this keeps the viewed result stable and avoids extra model cost.


## Session Addendum 2026-03-26 (Rank Mobile Refresh Flow)

### Confirmed UX Direction
- The user does not want a card-style short-video layout; the existing rank card arrangement should stay unchanged.
- The change is specifically mobile pagination behavior on `/rank`:
  - hide traditional pager as the primary mobile interaction
  - append next-page items when scrolling near the bottom
  - keep a small manual fallback button for load-more failures / observer misses
  - provide a floating button that returns to the current board list top
- Desktop pagination should remain intact.


## Session Addendum 2026-03-26 (Single-book Analysis First-Run UX)

### Confirmed Root Cause
- The first click on the analysis page still launched all three panels (`deconstruct/structure/plot`) in parallel.
- For 10-chapter content this created a provider overload pattern: even after crawler fixes and timeout tuning, the first run was still much more likely to fall back or appear frozen.
- A second UX bug amplified the perception: `AnalysisView` rendered streaming text through a preview helper, so long streaming content looked like it stopped after a few hundred characters even while the run was still active.

### Locked Fix
- First manual start now runs only the currently active panel.
- Unstarted panels remain available and can be started independently later.
- Streaming view now renders the full accumulated single-book text instead of a truncated preview, while still stripping progress markers.


## Session Addendum 2026-03-26 (UI Copy + Drawer + Trend Visual Cleanup)

### Confirmed UI Cleanup Direction
- The user wants less explanatory text, not a visual redesign from scratch. The right move is to remove helper prose and keep only functional labels.
- The chapter drawer issue on desktop was fundamentally a layout/direction problem: the previous bottom drawer plus non-sticky controls made the effective action area increasingly awkward as content grew.
- Trend word cloud needed to become a genuinely visual, colorful cloud rather than a pill list.
- The theme distribution pie chart needed labels removed from the pie itself to stop legend/metric text colliding inside the chart.
- Theme-table content was clearer as an actual table than as stacked descriptive cards.

## Session Addendum 2026-03-29 (Admin Bootstrap + Secret Config Hardening)

### Confirmed Root Causes
- Model registry `apiKey` values were stored in `system_config.ai.model-registry.json` as plaintext and returned to the frontend unchanged.
- The generic system-config endpoint also exposed plaintext values for secret-like keys such as `ai.langgraph-worker.internal-api-key`.
- Authentication already moved to phone-based login, but some backend integration tests still used legacy username payloads.
- The requested phone `15599316908` had no automatic promotion path to `ADMIN`; role state depended only on existing DB rows.

### Locked Implementation Direction
- Keep secrets server-side only: admin can submit a real key, but subsequent reads return only masked state.
- Encrypt persisted runtime secrets at rest in the backend and decrypt only inside the server process when invoking providers.
- Keep env-var fallback for deployment, but allow admin-configured keys to override and take effect immediately.
- Add a backend-managed bootstrap admin phone list so `15599316908` can become `ADMIN` automatically on login/register/refresh without manual DB surgery.

## Session Addendum 2026-03-29 (Turnstile + SMS Anti-Abuse)

### Confirmed Integration Seams
- SMS send currently enters through `AuthController.sendSmsCode(...)`, so Turnstile should be enforced there instead of buried inside Aliyun send logic.
- Login page already centralizes all SMS-triggering modes in `LoginView.vue`, so one shared Turnstile widget/state is enough.
- Existing SMS risk control only throttled by phone cooldown, which is insufficient against proxy-distributed abuse.

### Locked Direction
- Add a public auth-config endpoint exposing only Turnstile enabled/site-key state.
- Gate `/api/auth/sms/send` with backend Turnstile siteverify before Aliyun send.
- Expand SMS risk control to phone/IP/bizType dimensions with Redis-first behavior and local fallback.

## Session Addendum 2026-03-29 (Password Login Anti-Bruteforce)

### Confirmed Root Cause
- Existing password login protection only reused the generic per-IP request rate limiter.
- That generic limiter could slow obvious hammering, but it could not reliably stop:
  - same phone across multiple IPs
  - same IP sweeping many phones
  - repeated password guessing against one phone+IP pair

### Locked Direction
- Add a dedicated password-login risk-control service separate from the generic request limiter.
- Enforce three dimensions before auth execution:
  - phone
  - IP
  - phone+IP pair
- Record failures on wrong password / unknown phone / disabled account, and clear phone-scoped counters on successful login.

## Session Addendum 2026-04-24 (Project Understanding Review)

### Runtime / Topology Findings
- The repository is still organized as a four-service stack:
  - `frontend/`: Vue 3 + Vite + Element Plus + Pinia
  - `backend/`: Spring Boot 3.2 + MyBatis-Plus + Redis
  - `crawler/`: FastAPI crawler for rank/book/chapter data
  - `langgraph-worker/`: FastAPI internal AI worker
- `docker-compose.yml` confirms the production-like topology:
  - `nginx` fronts `backend`
  - `backend` depends on `mysql`, `redis`, `crawler`, and `langgraph-worker`
  - `crawler` and `langgraph-worker` are internal-only service dependencies, not direct user-facing services
- Backend env config shows the current architecture is still dual-runtime for AI:
  - Java backend can call direct OpenAI-compatible providers
  - Java backend can also call the internal LangGraph worker

### Frontend Bootstrap Findings
- `frontend/src/main.ts` restores auth before mounting the app, so route resolution depends on an early auth bootstrap.
- `frontend/src/router/index.ts` uses a single protected layout with child pages for:
  - `/rank`
  - `/analysis`
  - `/trend`
  - `/history`
  - `/config/prompt`
  - `/config/system`
- `frontend/src/router/guards.ts` keeps the route guard simple:
  - unauthenticated users are redirected to `/login`
  - authenticated users visiting `/login` are redirected to `/rank`
  - role-gated pages are enforced client-side by `meta.roles`

### Frontend Auth / Session Findings
- `frontend/src/stores/auth.ts` models auth state as:
  - `authenticated`
  - `logged_out`
  - `restoring`
- Frontend access tokens are memory-only:
  - `frontend/src/lib/auth-session.ts` clears token snapshot persistence instead of restoring from storage
  - real session restoration relies on refresh-cookie bootstrap, not local token persistence
- `frontend/src/lib/http.ts` centralizes auth header injection and automatic refresh-on-401 behavior.
- `frontend/src/lib/auth-bootstrap.ts` performs the initial silent refresh through `POST /api/auth/refresh` with cookies.

### Frontend API Layer Findings
- `frontend/src/api/auth.ts` shows the current auth contract is phone-first:
  - password login -> `/api/auth/login/password`
  - SMS login -> `/api/auth/login/sms`
  - registration -> `/api/auth/register`
  - SMS send -> `/api/auth/sms/send`
- `frontend/src/api/crawler.ts` confirms the rank module is board-oriented, not just a one-shot crawler call:
  - board catalog
  - user preference
  - rank page
  - rank refresh
  - rank status
  - book detail
  - chapters / refresh
- `frontend/src/api/analysis.ts` confirms both single-book and trend analysis support:
  - blocking requests
  - streaming requests
  - automatic stream fallback to blocking
- Long single-book analysis is treated as a special path:
  - chapter count `>= 10` gets a much larger frontend blocking timeout (`180000ms`)
- `frontend/src/api/data.ts` shows trend visualization and history are read from backend read models, not recomputed in the client.
- `frontend/src/api/config.ts` confirms the config UI is split into:
  - prompt config
  - system config
  - model registry / model options
  - generic user config persistence

### Frontend Page / State Findings
- `frontend/src/views/login/LoginView.vue` is no longer a simple username/password page:
  - password login, SMS login, register, and reset-password are all handled in one view
  - SMS send can be gated by Cloudflare Turnstile public config from `/api/system/auth-public-config`
  - password rules are enforced both in UI and backend contract
- `frontend/src/views/rank/RankView.vue` is the operational hub before analysis:
  - loads board catalog + saved preference + saved chapter-count preference
  - supports board refresh, paged rank browsing, book detail, chapter preview, and jump-to-analysis
  - mobile mode switches from classic pagination to refresh-flow / infinite-scroll behavior
  - board polling continues in the background to surface newer snapshots
- `frontend/src/views/analysis/AnalysisView.vue` is organized around three independent modes:
  - `deconstruct`
  - `structure`
  - `plot`
- Analysis page behavior is intentionally stateful:
  - current book context is restored from route or `user_config.analysis.current-context`
  - persisted history is rehydrated from `/api/data/history`
  - first manual run starts only the active panel, not all three at once
  - model selection is shared through `user_config.ai.preferred-model`
- `frontend/src/views/trend/TrendView.vue` is board-scoped and click-to-run:
  - restores board context from `user_config.trend.current-context`
  - falls back to crawler preference only if dedicated trend context is missing
  - visual cards/charts poll `/api/data/visual`
  - trend analysis itself is manual and streamed through `/api/analysis/trend/stream`

### Frontend Stream / Display Findings
- `frontend/src/lib/analysis-stream.ts` is the core SSE runner for both analysis and trend:
  - parses raw SSE frames manually
  - auto-refreshes token on `401`
  - falls back to blocking HTTP if SSE is unsupported, malformed, or fails before real content arrives
- The stream runner intentionally ignores transport-only placeholder deltas such as `[analysis-progress] ...`.
- `frontend/src/lib/analysis-display.ts` and `frontend/src/lib/trend-display.ts` are important presentation adapters:
  - they parse structured JSON embedded in model output
  - they normalize partially structured results into UI-friendly text/cards/tables
  - trend display heavily prefers stored structured JSON over ad-hoc frontend guessing

### Backend Auth / Security Findings
- Backend auth is based on:
  - bearer access token in memory on the frontend
  - refresh token in HttpOnly cookie
  - session validation against MySQL/Redis-backed auth-session state
- `backend/.../AuthTokenFilter.java` is the main gateway for protected APIs:
  - checks whitelist/protected path prefixes
  - resolves trusted client IP
  - enforces IP blacklist
  - validates bearer token and blacklist status
  - rehydrates caller session via `sid`
  - applies generic rate limiting
- `RequireRoleInterceptor` adds method/class-level role checks after authentication.
- `GlobalExceptionHandler` maps validation failures to `400` and business errors to structured JSON responses with real HTTP status codes.

### Backend Auth Business Findings
- `AuthController` is already phone-first:
  - password login
  - SMS login
  - SMS send
  - register
  - password reset
  - refresh
  - logout
- `AuthService` centralizes the real auth business rules:
  - password verification and password rule enforcement
  - SMS verification use
  - refresh-token rotation
  - token blacklisting on logout
  - active-device limit enforcement
  - bootstrap-admin phone promotion
- `AuthSessionService` shows session state is hybrid:
  - MySQL is the source of truth
  - Redis caches active session / refresh-token mappings / dirty-session activity timestamps
  - activity is flushed back to MySQL later
- `SmsAuthService` integrates Aliyun verification code sending and checking, while risk control is handled separately.

### Backend Config Findings
- `SystemConfigService` is a major runtime pivot, not just a CRUD wrapper:
  - provides typed config lookup with defaults
  - stores AI model registry
  - encrypts secret config values at rest
  - syncs registry back to legacy flat config keys for compatibility
- `PromptConfigService` is part of the analysis runtime path:
  - resolves runtime prompt templates
  - backfills default IO contract fields
  - validates `{{content}}` placeholder
  - supports model-bound prompt template selection
- `UserConfigService` is the persistence seam used by the frontend for:
  - preferred model
  - analysis current context
  - trend current context
  - rank chapter-count preference

### Backend Analysis Findings
- `AnalysisService` is the real orchestration center of the product.
- For single-book analysis it handles:
  - prompt resolution
  - cache lookup
  - history reuse
  - chapter loading through `CrawlerService`
  - runtime branch: legacy Java AI gateway vs internal LangGraph worker
  - optional chunk splitting for long books
  - SSE event emission
  - persistence into `analysis_result`
- For trend analysis it handles:
  - board lookup
  - latest board snapshots + rank rows loading
  - structured trend-result reuse from persisted history
  - runtime branch: legacy vs LangGraph
  - normalization of trend JSON into a board-scoped stable contract
- Current runtime selection is controlled by `analysis.runtime.mode`, with `legacy` still the default in config.
- Long single-book analysis has special timeout/chunking policy:
  - larger timeout budgets
  - forced chunk mode when chapter count is large enough

### Backend AI Integration Findings
- `AiGatewayService` is the legacy/direct AI runtime:
  - renders prompt templates through LangChain4j `PromptTemplate`
  - supports OpenAI-compatible providers and Dify fallback
  - supports true streaming for OpenAI-compatible models
  - parses/normalizes structured result JSON
  - chooses model runtime from prompt config + user preference + system model registry
- `LangGraphWorkerClient` is the internal bridge from Java to Python worker:
  - blocking call endpoint: `/internal/analysis/run`
  - streaming endpoint: `/internal/analysis/run/stream`
  - internal authentication uses `X-Internal-Service-Token`
  - runtime metrics from Python are merged into `resultJson.meta.runtime`

### Backend Data / Read Model Findings
- `DataQueryService` is the read-side assembler for frontend history and trend visual pages.
- `/api/data/history` reads `analysis_result` and joins book metadata for analysis-page restoration.
- `/api/data/visual` combines:
  - `rank_board`
  - recent `rank_snapshot`
  - related `crawl_rank`
  - latest reusable structured trend result
- Trend visual data is therefore a backend-composed read model, not raw frontend-side synthesis.

### Crawler Findings
- `crawler/app/main.py` exposes only `/health` publicly; business endpoints are all under `/internal/*`.
- `crawler/app/security.py` enforces a required internal service token and startup validation of key length.
- `crawler/app/services/fanqie_crawler.py` is the real Fanqie implementation:
  - board catalog comes from rank page state
  - rank list is fetched primarily through a Fanqie API endpoint, with page-state fallback
  - book detail comes from book page embedded state
  - chapter catalog comes from `chapterListWithVolume` / `itemIds`
  - chapter content comes from reader page state
  - confuse-font / PUA text is decoded through `ConfuseFontDecoder`
- Chapter fetching is resilient:
  - it can skip broken chapter pages and continue collecting later valid chapters
  - threaded fetching is used when multiple chapters are requested

### LangGraph Worker Findings
- `langgraph-worker/app/api/analysis.py` exposes internal-only blocking and streaming analysis endpoints.
- `langgraph-worker/app/services/analysis_service.py` shows the worker is currently a single LangGraph-based orchestration service, not a fully split multi-agent codebase.
- The worker graph currently covers:
  - request preparation
  - direct vs chunk route
  - chunk fan-out / merge
  - structured JSON validation and repair
  - runtime metrics capture
- `provider_client.py` talks directly to an OpenAI-compatible `/chat/completions` API with retry logic.

## Session Addendum 2026-04-25 (Prompt Governance Brainstorming)

### Confirmed Requirement Direction
- Prompt governance must move from the current "model binding only" design to a two-layer model:
  - admin global templates and publishable global versions
  - user-level bindings / personal copies that still remain subordinate to the latest admin-published version
- User confirmed the audit/history requirement should keep both:
  - user-side template choice history
  - actual effective template snapshot after admin publish events
- User confirmed the product should support both user behaviors:
  - bind to an existing admin-managed template
  - create a personal copy derived from the current admin default, with limited editable fields
- User confirmed admin rollout should use a draft + explicit publish model:
  - admin edits individual prompt types as drafts
  - only an explicit publish action makes a new global version effective

### Current Code Constraints That Matter
- Current runtime prompt resolution is still:
  - user preferred model -> model-registry promptBindings
  - fallback to `deepseek-chat` promptBindings
  - fallback to `prompt_name = default`
  - fallback to repository `is_default DESC, id ASC`
- Current `prompt_config` is a global shared template library with no user ownership field and no version table.
- Current prompt write API is still shared by `ADMIN` and `USER`, and current DTO allows editing JSON contract fields.
- Current frontend prompt page already treats `promptName = default` as the undeletable / non-renamable system template.

### Design Implication
- A clean solution likely needs new DB tables instead of overloading `prompt_config` further, because the new requirements include:
  - versioned admin publish units
  - effective-template snapshots per user across publish events
  - user-visible/private template scope
  - stricter field-level edit permissions between admin and normal users

## Session Addendum 2026-04-25 (Architecture Flow Review)

### Current Topology
- 当前仓库是四服务主链路：rontend Vue/Vite、ackend Spring Boot、crawler FastAPI、langgraph-worker FastAPI。
- docker-compose.yml 中 
ginx -> backend，后端依赖 mysql、edis、crawler、langgraph-worker；Python 两个服务都作为内部服务被后端调用。
- 前端 dev 通过 Vite /api proxy 转发到后端；生产/compose 由 nginx 对外。

### Frontend Flow
- rontend/src/main.ts 在挂载前执行 uthStore.ensureAuthRestored()，通过 refresh cookie 做静默会话恢复。
- rontend/src/router/index.ts 页面为 /login 和受保护布局下的 /rank、/analysis、/trend、/history、/config/prompt、/config/system。
- rontend/src/lib/http.ts 统一注入 Bearer access token，并在 401 时调用 /api/auth/refresh 轮换 token 后重试。
- rontend/src/api/* 是后端接口适配层；nalysis-stream.ts 是单书/趋势 SSE 统一 runner，支持 token refresh 和流失败回退阻塞请求。
- /rank 负责榜单上下文、偏好、分页/移动端加载、书籍详情和抓章；跳转 /analysis 时携带书籍和章数。
- /analysis 负责三类单书分析 deconstruct/structure/plot，按 active panel 手动触发，并从 /api/data/history 恢复历史结果。
- /trend 是榜单维度的趋势页，先读 board context 与 /api/data/visual，只有点击开始分析才跑 /api/analysis/trend/stream。

### Backend Flow
- TraceIdFilter 注入 traceId；AuthTokenFilter 做 protected path 判断、IP 黑名单、JWT、session、token blacklist 和 rate limit；RequireRoleInterceptor 处理 @RequireRole。
- AuthController/AuthService 当前是手机号优先：密码登录、短信登录、注册、重置、refresh、logout；refresh token 存 HttpOnly cookie，access token 由前端内存持有。
- AuthSessionService 以 MySQL 为真源，Redis 缓存 session、refresh-token 映射和活跃时间脏集合。
- CrawlerController/CrawlerService 管 board catalog、preference、rank refresh/page/status、book detail、chapters/refresh；优先读 Redis/DB，缺失或强刷时调用 PythonCrawlerClient。
- AnalysisController/AnalysisService 是 AI 编排中心：解析 prompt/model、复用缓存/历史、加载章节/快照、选择 legacy 或 langgraph runtime、SSE 输出、保存 nalysis_result。
- DataQueryService 是读模型聚合器：/history 恢复单书历史，/visual 聚合 board、snapshot、rank rows 和最新结构化 theme 结果。
- SystemConfigService 提供模型注册表、默认配置、密钥加密/脱敏；PromptConfigService 选择运行时模板并补齐 IO JSON contract。

### Crawler Flow
- crawler/app/main.py 暴露 /health，业务路由 /internal/board-catalog、/internal/rank、/internal/book、/internal/chapters 都要求 X-Internal-Service-Token。
- FanqieCrawler 从番茄 rank/page/reader 页面解析 window.__INITIAL_STATE__，榜单优先调用番茄 rank API，失败回退页面状态。
- 书籍详情来自 page state；目录来自 chapterListWithVolume / itemIds；正文来自 reader state 的 chapterData.content。
- 混淆字体/PUA 文本通过 ConfuseFontDecoder 解码；章节抓取支持多线程，并跳过坏章节继续收集可用章节。

### AI / LangGraph Flow
- 默认配置 nalysis.runtime.mode=legacy，即 Java AiGatewayService 直接走 LangChain4j + OpenAI-Compatible/Dify fallback。
- LangGraph 模式下，Java LangGraphWorkerClient 调 /internal/analysis/run 或 /internal/analysis/run/stream，传 prompt config、模型注册表解析后的 baseUrl/apiKey/model、sourcePayload 和 limits。
- Python Worker 当前是一个通用 LangGraphAnalysisService，StateGraph 节点为 prepare、direct analyze、split chunks、analyze chunks、merge chunks。
- Worker 通过 OpenAI-compatible /chat/completions 调模型，支持 blocking 和 stream；theme 趋势要求 JSON contract，并会尝试 JSON 解析/修复。

## Session Addendum 2026-04-25 (Server Migration Runbook)

### Deployment Documentation Findings
- Current production-like compose topology uses nginx, backend, crawler, langgraph-worker, mysql, and redis.
- Nginx container expects certificate files inside /etc/nginx/ssl: panch-origin.crt, panch-origin.key, and cloudflare-origin-pull-ca.pem.
- The user's current host SSL directory is /etc/nginx/ssl; therefore NGINX_SSL_DIR=/etc/nginx/ssl is the safest env value for migration docs.
- Existing .env.example did not include the full set of runtime variables provided by the user, and its SSL directory default differed from the current server path.
- Backend legacy AI runtime resolves DEEPSEEK_API_KEY from the backend process environment unless an encrypted model key is configured in system config, so compose should pass OpenAI-compatible variables into backend as well as langgraph-worker.

### Documentation / Config Decisions
- Added a dedicated migration runbook at docs/server-migration-runbook.md instead of expanding the Cloudflare-only document.
- Synced .env.example with the runbook variables, including OpenAI-compatible, LangGraph, SMS, and password-login risk-control settings.
- Updated docker-compose.yml backend environment mapping so migration .env values are actually visible to the Java backend container.
- MySQL backup command in the runbook uses docker compose exec -T mysql to avoid TTY output corrupting SQL dumps.

## Session Addendum 2026-04-25 (AI Request Latency Investigation)

### Symptom Framing
- 用户反馈“每次发起 AI 请求都很慢”，当前需要先区分：是首次出字慢、总完成时间慢、还是缓存未命中导致每次都重新跑全链路。
- 当前前端单书/趋势默认都优先走 SSE；若 SSE 在拿到真实内容前失败，才回退阻塞请求。

### Confirmed Frontend Behavior
- rontend/src/api/analysis.ts 对单书和趋势都优先调用 /stream 接口。
- rontend/src/lib/analysis-stream.ts 只有在未拿到真实 delta 时才触发 fallback blocking；一旦拿到任何真实 delta，就不会回退。
- AnalysisView.vue 会把 [analysis-progress] 和 [chunk-progress] 从展示文本里过滤掉，因此 chunk 模式下用户可能长时间只看到“没有正文输出”，体感会觉得更慢。
- Analysis 页首次只跑当前 active panel，不会一次并发三路，但每次点击 rerun 仍然会重新走完整后端链路。

### Confirmed Backend Latency Sources
- AnalysisService.analyze(...) 有缓存和历史复用：先查 Redis/local cache，再查 nalysis_result 可复用结果，再抓章和调 AI。
- AnalysisService.streamAnalyze(...) 当前**不会先查缓存/历史复用**；它会直接解析 prompt、抓章、判断 chunk、再决定真实流式 / chunk / 阻塞降级。这意味着前端默认走 /stream 时，即使已有缓存，后端也通常不会直接复用缓存结果。
- 单书分析前置步骤固定包含：
  - 解析运行时 prompt
  - 从 CrawlerService 抓章节或补抓缺失章节
  - 构建完整 prompt 文本
  - 按 token 或章数判断是否 chunk
- CrawlerService.getChapters(...) 若缓存不足，会先查库，再调用 Python crawler 抓缺失章节并落库；这一步可能先于任何 AI token 输出。
- 代码中 LARGE_BOOK_FORCE_CHUNK_CHAPTER_COUNT = 8、LARGE_BOOK_FORCE_CHUNK_SEGMENT_SIZE = 3，所以 8 章以上会强制分段，不只是按 token 估算。
- Chunk 模式实际调用次数 = 分段数 + 1 次 merge；例如 10 章通常会拆成 4 段，再做 1 次汇总，总共 5 次模型请求。
- Java legacy chunk 默认并发度来自 nalysis.chunk.parallelism，上限 6，默认 3；但 merge 仍然是串行的最后一步。
- Trend 主题分析默认要求结构化 JSON；若首轮模型文本无法解析 JSON，LangGraph worker 会额外发起一次 JSON repair 请求。
- LangGraph worker _repair_result_json(...) 对 	heme 会再调用一次模型，因此一次趋势请求可能至少 2 次 provider 调用。

### Streaming Reality Check
- Java legacy 真流式只覆盖“非 chunk 且 OpenAI-compatible streaming 可用”的单书场景。
- 单书 chunk 流式本质上发送的是 [chunk-progress] 进度文本；正文要等所有 chunk 分析完成并 merge 后才一次性出来。
- 因为前端把 [chunk-progress] 文本过滤掉，用户在 chunk 模式下基本只能等最终正文，体感上接近“假流式”。
- LangGraph worker 的单书/趋势 stream 会把 provider 的 delta 往前传，但如果 worker内部正在 chunk / merge / JSON repair，首个真正可见 delta 仍可能推迟。

### Observed Log Evidence
- ackend_local_20260326-011503.out.log 多次出现 dev.langchain4j.exception.TimeoutException: request timed out 和 java.net.http.HttpTimeoutException: request timed out，调用栈落在 AiGatewayService.invokeOpenAiCompatible -> AnalysisService.invokeLegacyBookAnalysis/streamAnalyze。
- 同一批日志还出现 crawler 相关 fallback/失败记录，说明部分请求在模型前已经被抓章/抓榜阶段拖慢或扰动。
- crawler_runtime_wmic.out.log 中确实有 /internal/chapters 的 500 Internal Server Error 记录，说明章节前置获取并不总是稳定。
- langgraph_worker_live.out.log 只有少量请求记录，没有看到足够多的成功样本；当前默认 runtime 仍更可能是 legacy。

### Root Cause Hypotheses (ranked)
1. **默认流式路径绕过缓存复用**：前端总是先打 /stream，但后端流式路径没有像阻塞 nalyze(...) 那样先命中 Redis/历史结果，导致“每次发起都重新抓章+重新调模型”。
2. **长内容 chunk + merge 导致真实 provider 调用次数膨胀**：8+ 章强制分段，10 章常见是 4 段 + 1 次 merge，耗时天然比单次调用高很多。
3. **chunk 进度被前端过滤，放大了体感延迟**：实际上服务端有进度输出，但界面不展示，所以用户只能等最终正文。
4. **章节抓取在 AI 前置且不完全稳定**：章节缓存不足时会同步补抓，Python crawler 的 500/repair/fallback 会把首 token 时间拖后。
5. **theme JSON contract 可能触发二次模型修复**：趋势分析若首轮 JSON 不合法，会额外走 repair 请求。
6. **15s legacy 默认 timeout 偏紧**：日志里已有多次 provider timeout，尤其长内容 legacy 路径会先慢后超时，再触发降级/重跑，进一步放大总时长。

### Highest-Value Optimization Directions
- P0: 在 streamAnalyze(...) / streamTrend(...) 开始时先复用缓存和最近历史结果；若命中，直接把结果切片为 delta 返回，而不是重新抓章/重新调模型。
- P0: 给前端保留 chunk 进度的可见展示，不要把 [chunk-progress] 全部过滤掉，至少显示“第 N/M 段分析中 / 汇总中”。
- P0: 为 AI 请求加入结构化耗时埋点：抓章耗时、prompt 构建耗时、provider 首 token 时间、provider 总耗时、chunk 数、merge 耗时、JSON repair 次数。
- P1: 单书分析对已抓章节做更积极的预热/缓存命中，例如用户在 /rank 抓章后，分析页直接复用已落库章节，不再同步补抓。
- P1: 对 8-10 章场景重新评估“强制 chunk”阈值和分段大小；如果模型和内容质量允许，可减少分段数，降低 provider_call_count。
- P1: 对趋势 theme 场景审查 prompt/schema，降低 JSON repair 触发率；repair 是隐性的第二次模型请求。
- P1: 若使用 LangGraph runtime，可直接利用 worker 返回的 meta.runtime.providerCallCount / queueWaitMillis / providerLatencyMillis / totalDurationMillis 做可视化排障。
- P2: 提高 nalysisStreamTaskExecutor 并发或拆分 chunk executor 资源，仅在确认为服务端线程池阻塞时再调；当前更像外部调用慢，不像本地线程池先打满。
- P2: 对 trend /api/analysis/trend/stream 也考虑先命中持久化结构化结果，再决定是否真的重跑模型。
