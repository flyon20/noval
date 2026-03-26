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
