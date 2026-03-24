# Progress Log

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
