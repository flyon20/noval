# Progress Log

## Session Addendum: 2026-04-26 AI Execution Consolidation Task 4
- Continued the interrupted `codex/ai-execution-consolidation` worktree session.
- Routed blocking legacy single-book analysis through `langgraph-worker` while keeping Java responsible for prompt/model resolution, metadata attachment, persistence, cache reuse, and response shaping.
- Preserved worker request compatibility by sending the normalized prompt template instead of invalid persisted prompt text, and by decrypting model-registry API keys only for runtime model resolution.
- Updated backend integration coverage so legacy single-book execution asserts worker usage, preserved chapter metadata, prompt/model payload passthrough, history reuse, and no Java OpenAI provider call on the blocking legacy path.
- Verification evidence: `mvn "-Dtest=Phase4AnalysisIntegrationTest,LangGraphWorkerClientTest" test` passed with 37 tests, 0 failures, 0 errors.

## Session: 2026-03-20

### Phase 1: 文档与现状梳理
- **Status:** complete
- **Started:** 2026-03-20 21:00
- Actions taken:
  - 阅读 `docs/项目总设计-v2.md` 与 `docs/分步开发计划.md`
  - 读取历史技术文档并修正编码问题
  - 检索当前后端控制器、服务、仓储、SQL 和测试
  - 对照文档承诺接口与当前实现，梳理缺口
- Files created/modified:
  - `D:\Git\agent\noval\task_plan.md` (created)
  - `D:\Git\agent\noval\findings.md` (created)
  - `D:\Git\agent\noval\progress.md` (created)

### Phase 2: 方案确认与范围锁定
- **Status:** complete
- Actions taken:
  - 向用户确认是否将未落地工程支撑也纳入补齐范围
  - 用户确认“未落地的也补齐”
  - 输出第 2 种补齐方案并合并用户新增约束：MyBatis-Plus、Dify + LangChain、Python 爬虫一起完善
  - 写入设计文档与实现计划文档
- Files created/modified:
  - `D:\Git\agent\noval\task_plan.md` (created)
  - `D:\Git\agent\noval\findings.md` (created)
  - `D:\Git\agent\noval\progress.md` (created)
  - `D:\Git\agent\noval\docs\superpowers\specs\2026-03-20-backend-v1-completion-design.md` (created)
  - `D:\Git\agent\noval\docs\superpowers\plans\2026-03-20-backend-v1-completion.md` (created)

### Phase 3: 测试先行与功能补齐
- **Status:** complete
- Actions taken:
  - 新增 Phase5 MySQL/H2 schema 与 seed
  - 新增 `Phase5BackendIntegrationTest`，先锁定系统配置、历史查询、可视化数据、趋势分析缺口
  - 为 `crawler / analysis / config` 主线引入 MyBatis-Plus 实体、Mapper 与仓储改造
  - 补齐 `GET/PUT /api/config/system`
  - 补齐 `GET /api/data/history`
  - 补齐 `GET /api/data/visual`
  - 补齐 `GET /api/analysis/trend`
  - 将 LangChain4j `PromptTemplate` 接入 `AiGatewayService`
  - 将 Python `fanqie_crawler.py` 从样例数据改为真实抓取实现
  - 新增 `docker-compose.yml`、`backend/Dockerfile`、`redis/redis.conf`
- Files created/modified:
  - `D:\Git\agent\noval\backend\sql\mysql\phase5-schema.sql`
  - `D:\Git\agent\noval\backend\sql\mysql\phase5-seed.sql`
  - `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-schema-h2.sql`
  - `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-data-h2.sql`
  - `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\data\Phase5BackendIntegrationTest.java`
  - `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\MybatisPlusConfig.java`
  - `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\analysis\...`
  - `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\config\...`
  - `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\...`
  - `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\data\...`
  - `D:\Git\agent\noval\backend\src\main\resources\application.yml`
  - `D:\Git\agent\noval\backend\Dockerfile`
  - `D:\Git\agent\noval\docker-compose.yml`
  - `D:\Git\agent\noval\redis\redis.conf`
  - `D:\Git\agent\noval\crawler\app\config.py`
  - `D:\Git\agent\noval\crawler\app\services\fanqie_crawler.py`
  - `D:\Git\agent\noval\crawler\app\utils\http_client.py`
  - `D:\Git\agent\noval\crawler\app\utils\parsers.py`
  - `D:\Git\agent\noval\crawler\tests\test_fanqie_crawler.py`

### Phase 4: 测试与回归
- **Status:** complete
- Actions taken:
  - 先运行 `mvn -Dtest=Phase5BackendIntegrationTest test` 验证新增接口由红转绿
  - 安装 crawler Python 依赖并执行 `python -m unittest discover -s tests -v`
  - 运行 `mvn test` 验证后端全量测试通过
- Files created/modified:
  - `D:\Git\agent\noval\backend\target\...` (generated)

## Test Results
| Test | Input | Expected | Actual | Status |
|------|-------|----------|--------|--------|
| 文档与代码差异梳理 | 读取文档与主要后端代码 | 找出未落地能力 | 已找出核心差异点 | PASS |
| Phase5 新接口测试 | `mvn -Dtest=Phase5BackendIntegrationTest test` | 新增接口通过 | 通过 | PASS |
| Backend 全量测试 | `mvn test` | 所有后端测试通过 | 14 tests passed | PASS |
| Crawler 单测 | `python -m unittest discover -s tests -v` | 解析与抓取逻辑通过 | 4 tests passed | PASS |

## Error Log
| Timestamp | Error | Attempt | Resolution |
|-----------|-------|---------|------------|
| 2026-03-20 21:10 | 历史文档乱码 | 1 | 使用正确编码重新读取 |
| 2026-03-20 21:05 | `rg.exe` 无法执行 | 1 | 改用 `Get-ChildItem` 与 `git grep` |
| 2026-03-21 00:12 | Python 依赖安装与测试并发执行导致测试导入失败 | 1 | 改为先安装依赖，再顺序运行 `unittest` |

## 5-Question Reboot Check
| Question | Answer |
|----------|--------|
| Where am I? | Phase 5: 交付总结 |
| Where am I going? | 输出结果、验证证据与剩余风险 |
| What's the goal? | 补齐网文项目后端 V1 未落地能力及数据库/Redis/工程支撑 |
| What have I learned? | 番茄页面的 `__INITIAL_STATE__` 可直接支撑榜单、详情、目录与正文抓取 |
| What have I done? | 已完成接口补齐、MP 主线迁移、AI 网关增强、Python 抓取补强与测试验证 |
## Session Addendum: 2026-03-21
### OCR Deobfuscation Work
- Added `crawler/app/utils/confuse_font_decoder.py` and wired it into `FanqieCrawler`.
- Added `crawler/tests/test_confuse_font_decoder.py` and updated crawler tests.
- Real validation with `FanqieCrawler.fetch_chapters(..., 3)` showed sampled chapters decode to readable Chinese with `PUA=0`.

### JSON Result Work
- Added `result_json` handling through analysis model, service, VO, repository, and SQL scripts.
- Updated Phase4/Phase5 backend integration tests to assert `resultJson` is present.

### Verification Evidence
- `python -m unittest discover -s tests -v` in `crawler` passed.
- `mvn test` in `backend` passed.
- Temporary local Uvicorn crawler server returned 200 for `/health`, `/internal/book`, and `/internal/chapters`, with decoded chapter content sample verified.

## Session Addendum: 2026-03-21
### Security Hardening Work
- Completed backend security hardening for authentication, refresh, request IP parsing, and HTTP status semantics.
- Disabled demo login by default. Demo credentials are only accepted when `app.auth.demo-enabled=true`.
- Added startup validation for auth configuration so JWT secret must be explicitly configured and meet the minimum length requirement.
- Added login rate limiting in `POST /api/auth/login`.
- Refresh now rejects blacklisted tokens and reloads the active user and role set from the database before issuing a new token.
- `X-Forwarded-For` is now trusted only when the immediate remote address is in the trusted proxy allowlist.
- Error responses now return real HTTP status codes for 400/401/403/429/500 while keeping the existing JSON `code/message/data` contract.

### Security Test Coverage
- Added `backend/src/test/java/com/novelanalyzer/config/AuthConfigValidatorTest.java`.
- Added `backend/src/test/java/com/novelanalyzer/modules/security/LoginRateLimitIntegrationTest.java`.
- Updated `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`.
- Updated `backend/src/test/java/com/novelanalyzer/modules/security/Phase2SecurityIntegrationTest.java`.

### Verification Evidence
- Ran `mvn "-Dtest=AuthConfigValidatorTest,AuthControllerTest,Phase2SecurityIntegrationTest,LoginRateLimitIntegrationTest" test` and it passed.
- Ran `mvn test` and it passed with `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`.

### Remaining Risk
- Environment warning remains: installed Tomcat Native is `1.2.33`, below the recommended `1.2.34`. This did not block the backend test suite.

## Session Addendum: 2026-03-21
### Crawler Internal API Security Work
- Assessed the Python crawler exposure risk and confirmed the default deployment previously published `crawler:5000` to the host while `/internal/*` had no service-to-service authentication.
- Added defense in depth for the crawler internal APIs:
  - removed default host port exposure for `crawler` in `docker-compose.yml`
  - introduced shared internal service key `CRAWLER_INTERNAL_API_KEY`
  - Java backend now sends `X-Internal-Service-Token` on crawler calls
  - Python crawler now validates that header for `/internal/rank`, `/internal/book`, and `/internal/chapters`
  - crawler startup now fails fast if the internal API key is missing or too short
- Kept `/health` open with minimal response payload for internal health checks.

### Files Created Or Updated
- Added `D:\Git\agent\noval\crawler\app\security.py`
- Updated `D:\Git\agent\noval\crawler\app\config.py`
- Updated `D:\Git\agent\noval\crawler\app\main.py`
- Updated `D:\Git\agent\noval\crawler\app\api\book.py`
- Updated `D:\Git\agent\noval\crawler\app\api\chapter.py`
- Updated `D:\Git\agent\noval\crawler\app\api\rank.py`
- Added `D:\Git\agent\noval\crawler\tests\test_internal_api_security.py`
- Added `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\CrawlerConfigValidator.java`
- Updated `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\CrawlerProperties.java`
- Updated `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\client\PythonCrawlerClient.java`
- Added `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\config\CrawlerConfigValidatorTest.java`
- Added `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\crawler\client\PythonCrawlerClientTest.java`
- Updated `D:\Git\agent\noval\backend\src\main\resources\application.yml`
- Updated `D:\Git\agent\noval\backend\src\test\resources\application.yml`
- Updated `D:\Git\agent\noval\docker-compose.yml`
- Added `D:\Git\agent\noval\docs\superpowers\specs\2026-03-21-crawler-internal-api-security-design.md`
- Added `D:\Git\agent\noval\docs\superpowers\plans\2026-03-21-crawler-internal-api-security.md`

### Verification Evidence
- Ran `python -m unittest tests.test_internal_api_security -v` and confirmed the newly added security tests passed after implementation.
- Ran `mvn "-Dtest=CrawlerConfigValidatorTest,PythonCrawlerClientTest" test` and confirmed targeted backend security tests passed.
- Ran `python -W ignore::ResourceWarning -m unittest discover -s tests -v` in `crawler` and it passed with `Ran 12 tests ... OK`.
- Ran `mvn test` in `backend` and it passed with `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0`.

### Remaining Risk
- Environment warning remains: installed Tomcat Native is `1.2.33`, below the recommended `1.2.34`. This did not block backend verification.

## Session Addendum: 2026-03-21
### Backend Replay Review Work
- Replayed the backend against the design baseline with focus on controller coverage, exception semantics, and security framework coverage.
- Verified documented controllers are present across auth, crawler, analysis, config, data, system, and security modules.
- Added review-driven regression tests for:
  - logout without authenticated caller context
  - missing required system config query parameter
  - blank trend platform parameter
  - admin-only access boundary for system config
- Fixed two real backend gaps:
  - `/api/auth/logout` now runs through the protected auth filter chain
  - missing and method-level validated request parameters now map to HTTP `400` instead of leaking as `500`

### Verification Evidence
- Ran `mvn "-Dtest=AuthControllerTest,Phase5BackendIntegrationTest" test` and reproduced three failures before the fix:
  - logout without `Authorization` returned `200`
  - missing `configKey` returned `500`
  - blank trend `platform` returned `500`
- Ran `mvn "-Dtest=AuthControllerTest,Phase5BackendIntegrationTest" test` again after the fix and it passed with `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`.
- Ran `mvn test` in `backend` after the replay-review fixes and it passed with `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`.

### Remaining Risk
- Prompt config write permission is still open to both `ADMIN` and `USER` by current controller policy. This may be acceptable for the current product model, but from a stricter operational-security perspective it is a policy point worth revisiting explicitly.
- Environment warning remains: installed Tomcat Native is `1.2.33`, below the recommended `1.2.34`. This did not block backend verification.

## Session Addendum: 2026-03-21
### Prompt Config Safety Follow-up
- Confirmed `LangChain4j PromptTemplate` rendering is actively wired in `AiGatewayService`.
- Confirmed Dify workflow invocation code is present, but default runtime setup is not fully active because `DIFY_*` env vars are absent in the current shell and seed data leaves `dify_workflow_id` empty.
- Kept the product rule that `USER` can read and update prompt config, then added guardrails around that capability instead of removing it.
- Added prompt content validation so saved prompts must contain the required `{{content}}` placeholder.
- Updated analysis cache keys to include a prompt signature, so prompt edits take effect immediately for repeated identical analysis requests instead of returning stale cached output.

### Added Regression Coverage
- `Phase4AnalysisIntegrationTest` now covers:
  - `USER` role can update prompt config
  - prompt config without `{{content}}` returns `400`
  - same analysis request re-runs against the updated prompt instead of serving old cache

### Verification Evidence
- Ran `mvn "-Dtest=Phase4AnalysisIntegrationTest" test` and it passed with `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.
- Ran `mvn test` in `backend` and it passed with `Tests run: 32, Failures: 0, Errors: 0, Skipped: 0`.

## Session: 2026-03-24

### Phase 1: 认证与趋势重构设计/计划
- **Status:** in_progress
- **Started:** 2026-03-24
- Actions taken:
  - Re-read the existing auth, trend, data, crawler, and AI gateway implementation.
  - Confirmed the current trend flow is still platform-oriented, auto-runs on mount, and does not use the selected rank board as first-class context.
  - Confirmed the current auth flow lacks password rules, fine-grained Chinese error feedback, and friendly exception mapping.
  - Wrote a new design spec and implementation plan for the auth UX rework plus board-level trend analytics rework.
- Files created/modified:
  - `D:\Git\agent\noval\docs\superpowers\specs\2026-03-24-trend-analytics-rework-and-auth-ux-design.md` (created)
  - `D:\Git\agent\noval\docs\superpowers\plans\2026-03-24-trend-analytics-rework-and-auth-ux.md` (created)
  - `D:\Git\agent\noval\task_plan.md` (updated)
  - `D:\Git\agent\noval\findings.md` (updated)
  - `D:\Git\agent\noval\progress.md` (updated)

## Current Focus
- Prepare failing tests for auth UX expectations and board-scoped trend analytics expectations before implementation changes.

## Session Addendum: 2026-03-24
### Trend Rework Execution
- Reworked backend trend analysis from platform/category scope to board scope with `platform + channelCode + boardCode`.
- Added board-scoped seed data for `rank_board`, `rank_snapshot`, `crawl_rank`, and structured `analysis_result.result_json`.
- Reworked `/api/data/visual` to return board-level visualization payloads including `historicalWordCloud`, `themeTable`, `hotBooks`, `insightCards`, and `snapshotComparisons`.
- Rebuilt the trend page so it loads boards + saved preference + visual data on mount, but does not auto-run analysis.
- The trend page now starts streaming analysis only after the explicit toolbar click, keeps the 300-character preview, and supports detail open/close on mobile.

### Verification Evidence
- Ran `mvn "-Dtest=Phase5BackendIntegrationTest" test` and it passed.
- Ran `mvn "-Dtest=AuthControllerTest,Phase5BackendIntegrationTest" test` and it passed.
- Ran `npm run test -- TrendView.spec.ts` and it passed.
- Ran `npm run test -- LoginView.spec.ts TrendView.spec.ts` and it passed.
- Ran `npm run build` and it passed.

### Files Updated This Round
- `backend/src/main/java/com/novelanalyzer/modules/analysis/...`
- `backend/src/main/java/com/novelanalyzer/modules/data/...`
- `backend/src/main/java/com/novelanalyzer/modules/crawler/repository/CrawlerRepository.java`
- `backend/src/test/resources/sql/phase5-data-h2.sql`
- `backend/sql/mysql/phase5-seed.sql`
- `frontend/src/views/trend/TrendView.vue`
- `frontend/src/components/trend/...`
- `frontend/src/composables/useTrendRun.ts`
- `frontend/src/types/data.ts`
- `frontend/src/types/trend.ts`
- `frontend/src/api/data.ts`
- `frontend/src/lib/trend-display.ts`

## Session: 2026-03-25

### Phase 1: 模型注册表与趋势 JSON 契约设计/计划
- **Status:** in_progress
- **Started:** 2026-03-25
- Actions taken:
  - Completed a local checkpoint commit before starting this rework: `5ec4812 chore: checkpoint langgraph and rank fetch updates`.
  - Re-read current system config, prompt config, analysis view, trend view, trend data service, and LangGraph worker request/response flow.
  - Confirmed the current AI model system is still based on flat string lists and global OpenAI-compatible credentials.
  - Confirmed the current trend pipeline still depends on backend fallback synthesis instead of a strict stored JSON contract.
  - Prepared the new design direction around model registry + guarded prompt contract editing + strict trend IO schema.
- Files created/modified:
  - `D:\Git\agent\noval\task_plan.md` (updated)
  - `D:\Git\agent\noval\findings.md` (updated)
  - `D:\Git\agent\noval\progress.md` (updated)

## Current Focus
- Write the dedicated spec and implementation-plan documents for this rework, then move into test-first backend changes.

## Session Addendum: 2026-03-25
### Model Registry + Trend Contract Execution
- Completed backend contract tightening for trend analysis:
  - `AnalysisService` now normalizes trend payloads into a stable shape without inventing board/theme conclusions.
  - `DataQueryService` now returns empty contract-driven collections when stored JSON is missing, while keeping real snapshot context available.
  - Added and extended trend DTOs for `boardSummary`, `themeDistribution`, `rankNo`, `representativeBooks`, `topThemeRatio`, and `leadBookName`.
- Updated theme prompt seed/example data and persisted test fixtures so admin-visible contracts and stored theme samples match the new schema.
- Rebuilt trend display shaping in `frontend/src/lib/trend-display.ts` and expanded front-end data types to carry the richer contract fields.
- Reworked `TrendView.vue`, `TrendSummaryCards.vue`, and `TrendComparisonList.vue` so the page renders board summary, theme table, representative books, comparison ratios, and detail drawer content directly from structured JSON while keeping mobile layout intact.

### Verification Evidence
- Ran `mvn "-Dtest=Phase5BackendIntegrationTest" test` and it passed.
- Ran `mvn "-Dtest=Phase4AnalysisIntegrationTest,Phase5BackendIntegrationTest" test` and it passed.
- Ran `npm run test -- --run src/lib/__tests__/trend-display.spec.ts src/views/trend/__tests__/TrendView.spec.ts` and it passed.
- Ran `npm run test -- --run src/lib/__tests__/trend-display.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts src/views/analysis/__tests__/AnalysisView.spec.ts src/views/trend/__tests__/TrendView.spec.ts` and it passed.
- Ran `npm run type-check` and it passed.
- Ran `npm run build` and it passed.

### Remaining Risk
- Front-end production build still emits chunk-size warnings for large bundles, especially `TrendView` and the main app bundle. This did not block the build, but later code-splitting would improve deploy-time performance.
- The workspace still contains unrelated pre-existing changes plus `appendonly.aof`, which should stay out of local commits.

## Session Addendum: 2026-03-25 (Prompt Contract Visibility Follow-up)
### Root-cause Reproduction
- Queried local MySQL directly and confirmed the active `prompt_config` rows still have empty input/output contract fields for all four prompt types.
- Verified local `system_config.ai.model-registry.json` is already populated, so the configuration complaint is now centered on prompt-contract visibility and model-management presentation rather than missing registry storage.
- Re-read `PromptConfigService`, `PromptConfigRepository`, `PromptConfigView.vue`, `SystemConfigView.vue`, `AnalysisService`, `DataQueryService`, `TrendView.vue`, and `trend-display.ts`.

### Current Focus
- Add regression coverage for prompt default-contract backfill and admin-page visibility.
- Implement backend-owned prompt contract defaults plus legacy-row backfill.
- Tighten trend rendering to use stored structured fields more strictly and upgrade the word-cloud presentation.

## Session: 2026-03-26

### Phase: 文档梳理与本地拉起
- **Status:** complete
- **Started:** 2026-03-26 01:10
- Actions taken:
  - Re-read `README.md`, `docs/项目总设计-v2.md`, `docs/本地联调说明.md`, and existing planning files to recover current project context.
  - Inspected key runtime files: `docker-compose.yml`, `frontend/package.json`, `frontend/vite.config.ts`, `backend/pom.xml`, `backend/src/main/resources/application.yml`, `crawler/app/main.py`, and `langgraph-worker/app/main.py`.
  - Verified local toolchain state: Java 17, Maven 3.8.1, Node 22, npm 9.6.5, Python 3.12; confirmed `docker` is unavailable and MySQL is already listening on `3306`.
  - Started local Redis, crawler (`5000`), langgraph-worker (`8001`), backend in H2 mode (`8080`), and frontend Vite dev server (`5173`).
  - Diagnosed and corrected two startup command issues:
    - Python service env injection via `Start-Process powershell -Command` dropped the env assignment.
    - Frontend `npm run dev` invocation passed `127.0.0.1 5173` as positional args to Vite.
  - Re-ran the failed service startups with corrected commands and re-verified the stack.
- Files created/modified:
  - `D:\Git\agent\noval\task_plan.md` (updated)
  - `D:\Git\agent\noval\findings.md` (updated)
  - `D:\Git\agent\noval\progress.md` (updated)

## Verification Evidence: 2026-03-26
- `Invoke-WebRequest http://127.0.0.1:5000/health` returned `{"code":200,"message":"success","data":{"status":"UP"}}`.
- `Invoke-WebRequest http://127.0.0.1:8001/health` returned `{"code":200,"message":"success","data":{"status":"UP"}}`.
- `Invoke-WebRequest http://127.0.0.1:8080/api/system/health` returned backend `status=UP`.
- `Invoke-WebRequest http://127.0.0.1:5173/api/system/health` returned the proxied backend health payload.
- `Invoke-WebRequest http://127.0.0.1:5173/` returned `200`, and browser verification confirmed the login page rendered with username/password fields.
- `Get-NetTCPConnection` confirmed listeners on `5000`, `8001`, `6379`, `8080`, and `5173`.


## Session Addendum: 2026-03-26 (Analysis Chain Deep Dive)
### Analysis Architecture Reading Work
- Re-read the current baseline docs for architecture and AI design scope:
  - `README.md`
  - `docs/project-design-v2.md`
  - `docs/superpowers/specs/2026-03-21-langchain4j-ai-gateway-design.md`
  - `docs/superpowers/specs/2026-03-24-phase2-langgraph-multi-agent-design.md`
  - `docs/superpowers/specs/2026-03-25-model-registry-and-trend-contract-design.md`
- Traced the real single-book and trend analysis call chain across:
  - frontend analysis/trend API + composables
  - backend `AnalysisController` / `AnalysisService`
  - backend `AiGatewayService` / `LangGraphWorkerClient`
  - Python `langgraph-worker`
  - crawler-backed chapter / board snapshot inputs
  - `analysis_result` persistence and `DataQueryService` readback
- Verified the current migration state:
  - default runtime config is still `analysis.runtime.mode=legacy`
  - LangGraph path is implemented and callable
  - current worker is one shared graph service, not yet four independently implemented agent modules
- Logged the main mental model for future sessions:
  - Java owns business orchestration and storage
  - Python worker owns AI execution when LangGraph mode is enabled
  - model registry + prompt contract are the shared config seam between frontend, backend, and worker
- Files created/modified:
  - `D:/Git/agent/noval/task_plan.md` (updated)
  - `D:/Git/agent/noval/findings.md` (updated)
  - `D:/Git/agent/noval/progress.md` (updated)


## Session Addendum: 2026-03-26 (Single-book 10-Chapter Stream Fix)
### Debug + Fix Work
- Reproduced the single-book analysis failure path from logs and confirmed the key evidence:
  - backend warned `PythonCrawlerClient : crawler chapter call failed: 500 Internal Server Error`
  - crawler stderr showed a reader URL inside the requested chapter range returned `404`
- Added a crawler regression test proving that one invalid reader page inside the requested range should not fail the whole chapter fetch.
- Updated `crawler/app/services/fanqie_crawler.py` so chapter fetching now skips broken reader pages and continues pulling later chapters until it has enough valid content or exhausts the catalog.
- Added a frontend stream regression test proving `[analysis-progress]` must not count as real output before fallback.
- Updated `frontend/src/lib/analysis-stream.ts` so placeholder progress deltas are ignored for visible text and fallback state tracking, while real streamed tokens still render normally.

### Verification Evidence
- Ran `python -m unittest tests.test_fanqie_crawler -v` in `crawler` and it passed with `Ran 24 tests ... OK`.
- Ran `npm run test -- --run src/lib/__tests__/analysis-stream.spec.ts src/composables/__tests__/useAnalysisRun.spec.ts` in `frontend` and it passed with `14 passed`.


## Session Addendum: 2026-03-26 (Actual Chapter Count UX)
### TDD + Implementation Work
- Added a backend unit test in `AnalysisServiceTimeoutTest` to lock that single-book analysis results carry:
  - `requestedChapterCount`
  - `actualChapterCount`
  - `inputChapterCount`
  - `chapterFetchDegraded`
- Added a front-end view test to lock that the analysis page shows `?????8/10` when fewer chapters were actually fetched than requested.
- Updated `AnalysisService` to attach requested/actual chapter metadata into `resultJson` for:
  - blocking single-book analysis
  - real streaming single-book analysis
  - chunked streaming analysis
  - LangGraph single-book analysis path
- Updated `AnalysisView.vue` so the result meta area now shows an explicit ratio when actual fetched chapters are lower than requested, while keeping the previous compact label when they are equal.

### Verification Evidence
- Ran `mvn "-Dtest=AnalysisServiceTimeoutTest" test` in `backend` and it passed.
- Ran `npm run test -- --run src/views/analysis/__tests__/AnalysisView.spec.ts src/lib/__tests__/analysis-stream.spec.ts src/composables/__tests__/useAnalysisRun.spec.ts` in `frontend` and it passed with `18 passed`.
- Re-ran `python -m unittest tests.test_fanqie_crawler -v` in `crawler` and it passed with `Ran 24 tests ... OK`.


## Session Addendum: 2026-03-26 (10-Chapter Timeout Budget)
### TDD + Timeout Fix Work
- Added backend timeout regression tests proving:
  - 10-chapter single-book analysis gets a 60s timeout budget
  - short single-book analysis keeps the default 15s budget
- Added a frontend API regression test proving the blocking single-book request uses a 60s timeout for `chapterCount=10`.
- Updated `AnalysisService` to compute a longer timeout budget for long single-book analysis and pass it through blocking, streaming, and chunked legacy analysis paths.
- Updated `AiGatewayService` to accept per-call timeout overrides for OpenAI-compatible blocking and streaming model clients.
- Updated `frontend/src/api/analysis.ts` so blocking fallback requests for 10-chapter analysis no longer use the generic 15s Axios timeout.

### Verification Evidence
- Ran `mvn "-Dtest=AnalysisServiceTimeoutTest" test` in `backend` and it passed with `Tests run: 9 ... 0 errors`.
- Ran `npm run test -- --run src/api/__tests__/analysis.spec.ts src/views/analysis/__tests__/AnalysisView.spec.ts src/lib/__tests__/analysis-stream.spec.ts src/composables/__tests__/useAnalysisRun.spec.ts` in `frontend` and it passed with `19 passed`.


## Session Addendum: 2026-03-26 (Forced Chunking For 8+ Chapters)
### Final Timeout Fix Work
- Added a backend regression test proving that large single-book analysis with short chapter text still splits into multiple chunks once chapter count is high enough.
- Updated `AnalysisService.splitChaptersForChunkedAnalysis(...)` so `8+` chapters force fixed-size chunk splitting (`3` chapters per segment) instead of relying only on token thresholds.
- Kept the longer backend AI timeout budget for long single-book analysis and aligned the frontend blocking fallback timeout to `180000ms` for `chapterCount=10`.

### Verification Evidence
- Re-ran `mvn "-Dtest=AnalysisServiceTimeoutTest" test` in `backend` and it passed with `Tests run: 10 ... 0 errors`.
- Re-ran `npm run test -- --run src/api/__tests__/analysis.spec.ts src/views/analysis/__tests__/AnalysisView.spec.ts src/lib/__tests__/analysis-stream.spec.ts src/composables/__tests__/useAnalysisRun.spec.ts` in `frontend` and it passed with `19 passed`.


## Session Addendum: 2026-03-26 (Persistent Analysis/Trend Context)
### Implementation Work
- Added hydration support to `useAnalysisRun` so persisted single-book analysis results can be restored into the three analysis panels without rerunning requests.
- Updated `AnalysisView.vue` to:
  - restore `analysis.current-context` from `user_config` when route query is absent
  - persist current book context and active tab back to `user_config`
  - reload recent persisted results for the current book via `/api/data/history`
  - keep the current analyzed book title visible across navigation and refresh
- Updated `TrendView.vue` to:
  - restore `trend.current-context` from `user_config`
  - prefer that saved trend context over rank-page board preference
  - persist the selected trend board when the user switches context
- Updated `RankView.vue` to:
  - restore `rank.chapter-count` from `user_config`
  - persist chapter-count changes independently from existing rank-fetch-count preference
  - include current book title/author when routing into `/analysis`
- Added/updated frontend regression coverage for:
  - hydrated analysis results
  - restoring persisted analysis context and results
  - restoring persisted trend context
  - restoring rank-page chapter count

### Verification Evidence
- Ran `npm run test -- --run src/composables/__tests__/useAnalysisRun.spec.ts src/views/analysis/__tests__/AnalysisView.spec.ts src/views/trend/__tests__/TrendView.spec.ts src/views/rank/__tests__/RankView.spec.ts` and it passed with `35 passed`.
- Ran `npm run type-check` and it passed.


## Session Addendum: 2026-03-26 (Rank Mobile Refresh Flow)
### Implementation Work
- Updated `RankView.vue` to support mobile-only refresh-flow pagination while keeping the current card layout unchanged.
- Added mobile viewport state, page-append state, load-more error state, and a bottom sentinel driven by `IntersectionObserver`.
- Implemented mobile auto-load for the next page, plus a manual `????` fallback button and an `?????` terminal state.
- Kept desktop `ElPagination` behavior unchanged.
- Added a floating `???` button for mobile refresh flow and wired it to smooth scroll back to the list top.
- Guarded board polling so mobile multi-page refresh flow does not unexpectedly collapse appended content into a single later page.

### Verification Evidence
- Ran `npm run test -- --run src/views/rank/__tests__/RankView.spec.ts` and it passed with `11 passed`.
- Ran `npm run type-check` and it passed.


## Session Addendum: 2026-03-26 (Single-book Analysis First-Run UX)
### Debug + Fix Work
- Updated `AnalysisView.vue` so the first click on `????` / rerun no longer dispatches all three analysis panels at once; it now starts only the active panel.
- Updated `AnalysisView.vue` so later first-time runs for untouched panels use normal analysis start semantics instead of forcing `forceReanalyze`.
- Replaced the analysis-page streaming display path from preview-truncation mode to full accumulated streaming text, while still removing progress markers.
- Updated analysis-page tests to lock:
  - first trigger only runs the active panel
  - long streaming output remains visible instead of truncating to preview length
  - targeted panel stop/rerun still behaves correctly

### Verification Evidence
- Ran `npm run test -- --run src/views/analysis/__tests__/AnalysisView.spec.ts src/composables/__tests__/useAnalysisRun.spec.ts` and it passed with `14 passed`.
- Ran `npm run type-check` and it passed.


## Session Addendum: 2026-03-26 (UI Copy + Drawer + Trend Visual Cleanup)
### Implementation Work
- Simplified `AppHeader.vue` to keep only page titles and made the top bar sticky so mobile users can reliably reach logout.
- Simplified `BookDetailDrawer.vue` by removing trace/debug text and extra labels.
- Rebuilt `ChapterPreviewDrawer.vue` for better desktop usability:
  - desktop uses a side drawer instead of the old awkward bottom sheet
  - primary actions reordered to `?????? / ????? / ??`
  - chapter count and quota move into a dedicated meta row below the actions
  - removed trace/debug parameter text
- Simplified `RankView.vue` hero copy by removing explanatory filler text.
- Rebuilt `TrendTagCloud.vue` into a true colorful cloud-like SVG layout with varied size, color, and placement.
- Tightened trend UI copy and improved visual clarity:
  - shortened trend summary/comparison copy
  - removed verbose trend toolbar subtitles
  - converted theme-table support content to a real `el-table`
  - disabled pie labels inside the theme distribution chart and kept legend/tooltips outside the pie
  - tightened snapshot table copy and enabled overflow tooltips

### Verification Evidence
- Ran `npm run test -- --run src/components/rank/__tests__/BookDetailDrawer.spec.ts src/components/rank/__tests__/ChapterPreviewDrawer.spec.ts src/layouts/__tests__/AppShell.spec.ts src/views/trend/__tests__/TrendView.spec.ts` and it passed with `19 passed`.
- Ran `npm run type-check` and it passed.

## Session Addendum: 2026-03-29 (Admin Bootstrap + Secret Config Hardening)
### Implementation Work
- Added server-side config-secret encryption support for sensitive system-config values and model-registry API keys.
- Changed model-registry reads to return masked key state instead of plaintext, while preserving write-only key updates from the admin page.
- Added `auth.bootstrap-admin-phones` default config and wired auth login/register/refresh to auto-grant `ADMIN` when the phone matches.
- Updated targeted backend integration helpers to use the current phone-based login contract.
- Updated local docs and seed data so the admin-phone bootstrap and secret-key strategy are documented and reproducible.

### Verification Evidence
- Ran `mvn -DskipTests test-compile` in `backend` and it passed.
- Ran `npm run test -- --run src/views/config/system/__tests__/SystemConfigView.spec.ts` in `frontend` and it passed.
- Started local Redis on `127.0.0.1:6379`.
- Ran `mvn "-Dtest=AuthControllerTest#shouldGrantAdminRoleToBootstrapPhoneOnPasswordLogin,Phase5BackendIntegrationTest#shouldManageAiModelRegistryAndExposeModelOptions+shouldMaskAndEncryptSecretSystemConfigValue,Phase4AnalysisIntegrationTest#shouldUseSelectedModelRegistryRuntimeConfigForOpenAiCompatibleAnalysis" test` and it passed.

## Session Addendum: 2026-03-29 (Turnstile + SMS Anti-Abuse)
### Implementation Work
- Added Cloudflare Turnstile config properties, public auth-config endpoint, and backend Turnstile siteverify service with short HTTP timeouts.
- Updated `/api/auth/sms/send` to require Turnstile verification before SMS send.
- Upgraded `SmsRiskControlService` from single phone cooldown to phone/IP/bizType layered throttling with local fallback.
- Added a lightweight frontend Turnstile widget component and wired the login page to require a Turnstile token before SMS send when enabled.
- Updated login-page tests to load public auth config and preserve existing flows when Turnstile is disabled.

### Verification Evidence
- Ran `mvn "-Dtest=AuthControllerTest#shouldRejectSmsSendWhenTurnstileTokenMissing+shouldReturnDebugVerifyCodeForLoopbackSmsSend,SystemControllerTest#shouldExposePublicAuthConfig" test` and it passed.
- Ran `npm run test -- --run src/views/login/__tests__/LoginView.spec.ts` and it passed.
- Ran `npm run type-check` and it passed.
- Ran `mvn -DskipTests test-compile` and it passed.

## Session Addendum: 2026-03-29 (Password Login Anti-Bruteforce)
### Implementation Work
- Added `PasswordLoginRiskControlService` to track password-login failures by phone, IP, and phone+IP pair.
- Wired password-login pre-checks into `AuthController.login(...)`.
- Wired failure/success bookkeeping into `AuthService.login(...)`, including unknown phone and wrong-password paths.
- Added tunable security properties and environment keys for password-login windows, thresholds, and cooldown.
- Added integration tests covering:
  - repeated wrong password attempts on the same phone+IP
  - distributed attempts against the same phone
  - one IP sweeping multiple phones

### Verification Evidence
- Ran `mvn "-Dtest=PasswordLoginRiskControlIntegrationTest" test` and it passed.

## Session Addendum: 2026-04-24 (Project Understanding Review)
### Read-only Architecture Review Work
- Re-read the existing planning files to recover current repo context before scanning code.
- Re-read high-level runtime files:
  - `README.md`
  - `frontend/package.json`
  - `frontend/vite.config.ts`
  - `backend/pom.xml`
  - `backend/src/main/resources/application.yml`
  - `docker-compose.yml`
- Reconstructed the frontend bootstrap and routing chain:
  - `frontend/src/main.ts`
  - `frontend/src/router/index.ts`
  - `frontend/src/router/guards.ts`
  - `frontend/src/stores/auth.ts`
  - `frontend/src/lib/http.ts`
  - `frontend/src/lib/auth-session.ts`
  - `frontend/src/lib/auth-bootstrap.ts`
- Reconstructed the frontend business flow by reading:
  - login / rank / analysis / trend views
  - `useAnalysisRun.ts`
  - `useTrendRun.ts`
  - API adapters for auth / crawler / analysis / data / config / system
  - stream/display adapters in `frontend/src/lib`
- Reconstructed the backend service boundaries and orchestration by reading:
  - security filter / role interceptor / global exception handler
  - auth / crawler / analysis / data / config / system controllers
  - `AuthService`, `AuthSessionService`, `SmsAuthService`
  - `CrawlerService`, `PythonCrawlerClient`
  - `AnalysisService`, `AiGatewayService`, `LangGraphWorkerClient`
  - `DataQueryService`, `SystemConfigService`, `PromptConfigService`, `UserConfigService`
- Reconstructed the crawler service and internal AI worker boundaries by reading:
  - crawler FastAPI entrypoint, config, security, API routers, and `FanqieCrawler`
  - langgraph-worker entrypoint, streaming API, LangGraph service, and provider client

### Outcome
- Confirmed the current repo should be understood as:
  - frontend Vue workspace
  - Java backend orchestration core
  - internal Python crawler
  - internal Python LangGraph worker
- Confirmed the most important end-to-end path is:
  - frontend interaction
  - backend auth / role / rate-limit gates
  - backend business orchestration
  - backend calls crawler and/or AI runtime
  - backend persists normalized results
  - frontend restores or renders backend read models
- No business files were modified in this round; only planning / findings / progress notes were updated.

## Session Addendum: 2026-04-25 (Prompt Governance Brainstorming)
### Design Discovery Work
- Re-read the current prompt-template design notes and key implementation files before proposing changes:
  - `docs/superpowers/specs/2026-04-19-model-bound-prompt-template-design.md`
  - `backend/.../PromptConfigController.java`
  - `backend/.../PromptConfigService.java`
  - `backend/.../PromptConfigRepository.java`
  - `backend/.../SystemConfigService.java`
  - `frontend/src/views/config/prompt/PromptConfigView.vue`
- Confirmed current runtime behavior:
  - prompt template selection is still global and model-binding driven
  - `promptBindings` is the intended primary selector, not `prompt_config.model_name`
  - `is_default` is currently polluted by repository insert behavior
- Confirmed current data limitations:
  - `user_config` has no soft-delete field
  - `prompt_config` has no user ownership / version / publish grouping
  - current prompt update DTO does not separate admin-editable and user-editable fields

### Requirements Locked With User
- History model: use full snapshot/history semantics, not just one backup field.
- User mode: support both binding existing templates and creating personal copies with restricted editable fields.
- Admin rollout: use draft edits plus explicit global publish, not immediate auto-publish on every save.

### Current State
- Still in brainstorming/design phase.
- No implementation code has been changed yet.

### Spec Output
- Wrote the prompt-governance redesign spec to:
  - `D:\Git\agent\noval\docs\superpowers\specs\2026-04-25-prompt-governance-redesign-design.md`
- The spec locks:
  - admin draft + publish flow
  - system template vs user copy scope
  - user binding and effective-history tables
  - admin-only JSON contract editing
  - runtime fallback order anchored on published global templates

## Session Addendum: 2026-04-25 Architecture Flow Review
- 2026-04-25 03:20:28 完成当前项目只读架构梳理：前端、后端、爬虫、LangGraph worker。
- 已确认主要入口、路由/API、鉴权链路、爬虫链路、AI runtime 分支和趋势/单书分析数据流。
- 本轮未修改业务代码，仅更新 planning 记录文件。

## Session Addendum: 2026-04-25 Server Migration Runbook
- 2026-04-25 03:40:33 新增服务器迁移 Runbook，覆盖 SSL/env/compose/MySQL/Redis/Cloudflare/验证/回滚/排障。
- 同步 .env.example 的线上 SSL 路径和运行变量占位。
- 补齐 docker-compose.yml 中 backend 对 AI、Dify、短信和登录风控环境变量的映射。

### Verification Update: 2026-04-25 Server Migration Runbook
- 2026-04-25 03:44:20 静态校验 .env.example、docker-compose.yml、docs/server-migration-runbook.md 关键变量一致。
- 本机未安装 Docker，无法执行 docker compose config；已记录为环境限制。
- 同步 docs/nginx-cloudflare-production.md 中旧的 /etc/noval/ssl 示例为当前 /etc/nginx/ssl。

## Session Addendum: 2026-04-25 AI Latency Investigation
- 2026-04-25 04:00:20 完成 AI 请求慢链路只读排查：前端 stream/fallback、后端缓存/抓章/chunk、legacy 网关、LangGraph worker、现有日志证据。
- 已确认一个高优先级问题：默认流式路径未像阻塞路径那样先做缓存/历史复用。
- 已确认长内容 8 章以上会强制 chunk，且 chunk 进度目前被前端过滤，显著放大用户体感延迟。
