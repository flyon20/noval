# Progress Log

## Session Addendum: 2026-07-07 (Webnovel Project Knowledge Skill Agent Design)
### Design And Skill Foundation
- Continued the AI Q&A domain-agent design toward a user-owned novel project knowledge base rather than stateless chat memory.
- Confirmed the product boundary: uploaded works belong to `userId + projectId/workId`, and new conversations should still resolve and use that project knowledge when selected or uniquely named.
- Added a production implementation plan at `docs/superpowers/plans/2026-07-07-webnovel-project-knowledge-skill-agent.md`, covering MySQL structured state, Qdrant vector chunks, async ingestion, project-aware routing, cache-first rank/chapter retrieval, UX, Trace, and production gates.
- Added `webnovel-project-knowledge-qa` as the first skill pack for cross-session project knowledge Q&A, foreshadowing audits, continuity checks, setting lookup, chapter recall, timeline checks, and project-based continuation planning.
- Enhanced `webnovel-book-breakdown` with editor/architect/plot-level commercial拆文 modules:整体结构,作者思路,类型微创新,黄金开局,节奏拉扯,钩子留存,情节复刻模版,主编综合评语.
- Enhanced `webnovel-market-scan` with Top30 keyword frequency, subgenre statistics, reader emotion anchors, black-horse micro-innovation, author-side recommendations, and explicit 3-day cache-first rank/chapter reuse policy.

### Verification Evidence
- Red skill test evidence: `python -m pytest tests/test_skill_registry.py -q` first failed because the project knowledge skill did not exist and the existing拆文/市场 skill packs lacked the required modules/cache policy.
- Green skill test evidence: `python -m pytest tests/test_skill_registry.py -q` passed with 13 tests after adding and enhancing the skill packs.

## Session Addendum: 2026-07-07 (Login/SMS Production Availability Hotfix)
### Root Cause And Implementation
- Read the production backend log attachments and the successful nginx-to-backend health check.
- Confirmed backend was up; login failed in the auth database path because `sys_user ... FOR UPDATE` hit a MySQL lock wait timeout.
- Confirmed SMS send also hit a stale/broken MySQL connection in `SmsCodeLogRepository.insert`, with Hikari warning that pooled connection lifetime was too long for the server/network closure behavior.
- Added `ResultCode.SERVICE_UNAVAILABLE` and a global handler for transient/recoverable DB exceptions, returning a Chinese 503 retry message instead of a generic 500.
- Wrapped auth login user-lock acquisition so lock wait timeout returns a clear 503 login retry message.
- Made SMS send resilient when Aliyun returns success but local SMS audit-log insert fails from a recoverable/stale DB connection; the API can still return `smsOutId` for verification.
- Tuned backend MySQL/Hikari defaults: JDBC connect/socket timeout, TCP keepalive, validation timeout, connection timeout, idle timeout, max lifetime, and keepalive time.

### Verification Evidence
- Red test evidence: `mvn "-Dtest=AuthServiceTest,SmsAuthServiceTest" "-DforkCount=0" test` initially failed before implementation because `SERVICE_UNAVAILABLE` did not exist and SMS log insert recovery was not implemented.
- Green test evidence: `mvn "-Dtest=AuthServiceTest,SmsAuthServiceTest,GlobalExceptionHandlerTest" "-DforkCount=0" test` passed with 12 tests, 0 failures, 0 errors.
- Diff hygiene evidence: `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.
- Packaging evidence: created `D:\Git\agent\noval-release-ai-qa-agent-20260707_014805.tar.gz` (`1,793,734` bytes). Archive verification found 991 entries, included the auth/SMS hotfix files plus frontend/worker/docker roots, and found no `.git`, `node_modules`, `target`, `dist`, `.pytest_cache`, `__pycache__`, `.codex-research`, or nested release archive entries.

## Session Addendum: 2026-07-06 (AI Q&A Post-Release Product Regression Fix)
### Root Cause And Implementation
- Reproduced the user-facing regressions with red tests: raw `RUNNING` leaked into normal chat, project space did not load durable conversations, and backend chat-run/Admin Trace id resolution ignored worker `trace.trace_id`.
- Frontend durable run statuses now normalize to Chinese labels before rendering or persisting, so users see `正在后台执行` / `后台任务已排队` / `后台回答已完成` instead of raw enum values.
- Project space now loads recent server-side chat runs for the active project, displays a clear `最近会话` list with colored status labels, and emits conversation selection events.
- Chat view now handles conversation selection by loading the latest server-side run for that conversation and restoring question, answer, Trace id, partial/running state, and polling when needed.
- Backend added `GET /api/knowledge/chat-runs?projectId=&limit=` through `KnowledgeChatRunService.listRecentRuns`, returning the latest run per conversation and respecting user/project isolation.
- Backend trace linking now accepts `traceId`, `trace_id`, `trace.traceId`, and `trace.trace_id` in both chat-run persistence and Admin Agent Trace persistence.

### Verification Evidence
- Red frontend evidence: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/components/knowledge/__tests__/KnowledgeProjectSpace.spec.ts` failed because `RUNNING` was visible and `listChatRuns` was never called.
- Red backend evidence: `mvn "-Dtest=KnowledgeChatRunServiceTest,KnowledgeAgentTraceServiceTest" "-DforkCount=0" test` failed because snake_case trace ids were not persisted.
- Green frontend evidence: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/components/knowledge/__tests__/KnowledgeProjectSpace.spec.ts` passed with 39 tests.
- Frontend type evidence: `npm run type-check` passed.
- Green backend evidence: `mvn "-Dtest=KnowledgeChatRunServiceTest,KnowledgeAgentTraceServiceTest,KnowledgeControllerTest" "-DforkCount=0" test` passed with 23 tests.
- Worker API evidence: `python -m pytest tests/test_knowledge_api.py -q` passed with 8 tests.
- Worker agent evidence: `python -m pytest tests/test_novel_research_agent.py -q` passed with 134 tests and 11 subtests.
- Diff hygiene evidence: `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.
- Packaging evidence: first archive `D:\Git\agent\noval-release-ai-qa-agent-20260706_033054.tar.gz` was rejected because archive verification found `mcp-tools` pytest caches. Removed it and rebuilt.
- Final archive: `D:\Git\agent\noval-release-ai-qa-agent-20260706_033129.tar.gz` (`1,797,410` bytes). Archive verification found 993 entries, included backend/frontend/worker/mcp-tools/docker key files, and found no `.git`, `node_modules`, `target`, `dist`, `.pytest_cache`, `__pycache__`, or nested release archives.

## Session Addendum: 2026-07-06 (AI Q&A Durable Deep Run Regression Fix)
### Review Work
- Re-read `task_plan.md`, `findings.md`, and `progress.md`, plus the latest production attachments for the long-running `chatrun-38ad45fb-6cfd-4cf5-9c46-d976f3a5cca7` regression.
- Added Phase 45 to `task_plan.md`.
- Created `docs/superpowers/specs/2026-07-06-ai-qa-durable-stream-run-production-fix.md`.
- Confirmed the production root cause: durable deep runs called blocking backend chat execution, so worker progress/delta events were not persisted to `ai_chat_run` and the active UI could only show a stale running state.

### Implementation Work
- Added `KnowledgeChatService.chatWithProgress()` to reuse worker streaming while preserving conversation summary, Agent Trace, memory candidates, and index follow-up behavior.
- Updated `KnowledgeChatRunService` to write progress phase/message and partial answer into `ai_chat_run` while the background run is active.
- Updated the frontend durable run path to show partial answers, Chinese run status, Trace id when present, delayed polling, pending-run preservation, and an active-run timeout message.

### Verification Evidence So Far
- Backend red evidence: `mvn "-Dtest=KnowledgeChatRunServiceTest" "-DforkCount=0" test` first failed because `ChatProgressListener` did not exist.
- Frontend red evidence: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts` first failed because durable RUNNING progress and partial answer were not visible.
- Backend green evidence: `mvn "-Dtest=KnowledgeChatRunServiceTest" "-DforkCount=0" test` passed with 4 tests, 0 failures, 0 errors.
- Frontend green evidence: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts` passed with 32 tests.

### Final Review And Verification
- Re-reviewed the Phase 45 durable-run patch after the first fix and found one remaining recovery edge case: if `pendingRunId` existed in local storage but the assistant draft message was missing, `resumePendingRun()` could set loading without creating a visible message or scheduling useful recovery.
- Fixed the recovery edge case by creating a placeholder assistant message (`正在恢复后台回答`) before polling the saved run.
- Added frontend regression coverage for a pending durable run restored without an assistant draft message.
- Fresh verification:
  - `mvn "-Dtest=KnowledgeChatRunServiceTest" "-DforkCount=0" test` passed with 4 tests, 0 failures, 0 errors.
  - `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts` passed with 33 tests.
  - `mvn "-Dtest=KnowledgeChatRunServiceTest,KnowledgeChatServiceTest,LangGraphWorkerClientTest,KnowledgeControllerTest" "-DforkCount=0" test` passed with 39 tests, 0 failures, 0 errors.
  - `npm run type-check` passed.
  - `python -m pytest tests/test_knowledge_api.py -q` passed with 8 tests.
  - `python -m pytest tests/test_novel_research_agent.py -q` passed with 134 tests and 11 subtests after rerunning with a longer timeout.
  - `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.
- Phase 45 marked complete in `task_plan.md`.
- Packaging:
  - Created `D:\Git\agent\noval-release-ai-qa-agent-20260706_021003.tar.gz` (`2,175,294` bytes).
  - Archive verification found 1,089 entries, first entry `noval/.env.example`.
  - Required files are included: `phase15-ai-chat-run-production.sql`, `KnowledgeChatRunService.java`, `KnowledgeChatService.java`, `useKnowledgeChat.ts`, `KnowledgeChatView.vue`, `KnowledgeChatView.spec.ts`, `novel_research_agent.py`, `docker-compose.yml`, `docker/nginx/Dockerfile`, and `mcp-tools/Dockerfile`.
  - Archive exclusion verification found no `.git`, `node_modules`, `target`, `dist`, `.pytest_cache`, `__pycache__`, or nested release archives.

## Session Addendum: 2026-07-01 Novel Market Calibration Phase 26
- Started Phase 26 for current-market calibration of `让你做五毛特效，你请诸天打工？`.
- Added Phase 26 to `task_plan.md` and documented user concerns in `findings.md`.
- Next actions:
  - Re-read novel planning MD files in `D:\Git\myNote\noval`.
  - Inspect local Docker/data collection services and identify the narrowest command/API to refresh Fanqie male-new urban-brain ranking.
  - Fetch latest ranking and sample comparable titles/chapters where possible.
  - Compare ranking/chapter pacing against current first-ten-chapter prose and produce a concrete modification plan.

## Session Addendum: 2026-07-01 Novel Market Calibration Completion
- Re-read the novel-related MD set in `D:\Git\myNote\noval`, including goldfinger, first-ten-chapter design/prose, latest calibration notes, outline, roles, naming, and industry workflow files.
- Checked local Docker for the requested data-collection path; Docker Desktop daemon was unavailable, so the local Python crawler service code path was used instead.
- Refreshed Fanqie male-new urban-brain `male-new:262` Top30 at 2026-07-01 02:24 and confirmed the current front board is stable with strong high-concept/system/public-reaction signals.
- Fetched five chapter samples each for comparable ranks #1, #3, #4, #6, #9, #10, #13, #18, #25, #28, and #30.
- Generated research outputs under `D:\Git\agent\noval\.codex-research\novel-market-2026-07-01`:
  - `fanqie-male-new-262-rank30-refresh.json`
  - `fanqie-male-new-262-key-comparable-chapters-refresh.json`
  - `key-comparable-marker-summary.json`
- Wrote `D:\Git\myNote\noval\最新榜单校准-2026-07-01-开文修订落地.md`.
- Wrote `D:\Git\myNote\noval\前十章正文修订版-让你做五毛特效你请诸天打工.md`.
- Verification:
  - Confirmed the revised prose file exists and contains 10 chapter headings.
  - Confirmed the new analysis and revised prose files are UTF-8 readable through PowerShell.
- Phase 26 marked complete in `task_plan.md`.

## Session Addendum: 2026-04-26 AI Execution Consolidation Task 4
- Continued the interrupted `codex/ai-execution-consolidation` worktree session.
- Routed blocking legacy single-book analysis through `langgraph-worker` while keeping Java responsible for prompt/model resolution, metadata attachment, persistence, cache reuse, and response shaping.
- Preserved worker request compatibility by sending the normalized prompt template instead of invalid persisted prompt text, and by decrypting model-registry API keys only for runtime model resolution.
- Updated backend integration coverage so legacy single-book execution asserts worker usage, preserved chapter metadata, prompt/model payload passthrough, history reuse, and no Java OpenAI provider call on the blocking legacy path.
- Verification evidence: `mvn "-Dtest=Phase4AnalysisIntegrationTest,LangGraphWorkerClientTest" test` passed with 37 tests, 0 failures, 0 errors.

## Session Addendum: 2026-04-26 AI Execution Consolidation Task 5
- Added regression assertions that LangGraph-backed trend analysis still exits through Java-side normalization for both blocking and streaming paths.
- Locked normalized trend fields in backend coverage: `boardSummary`, `trendPreview`, `historicalWordCloud`, `themeDistribution`, `hotBooks`, `insightCards`, and `snapshotComparisons`.
- Verification evidence: `mvn "-Dtest=Phase4AnalysisIntegrationTest" test` passed with 34 tests, 0 failures, 0 errors.
- Verification evidence: `npm test -- --run src/views/trend/__tests__/TrendView.spec.ts` passed with 17 tests.

## Session Addendum: 2026-04-26 AI Execution Consolidation Task 6
- Red test verified: `mvn "-Dtest=Phase4AnalysisIntegrationTest,Phase5BackendIntegrationTest" test` failed because `analysis.runtime.mode` default still returned `legacy` while the new expectation is `langgraph`.
- Changed the `SystemConfigService` default for `analysis.runtime.mode` to `langgraph` and aligned the no-row fallback in `AnalysisService` to `langgraph`.
- Preserved rollback coverage by making legacy-path tests explicitly set `analysis.runtime.mode=legacy` before asserting OpenAI streaming, progress, chunk, and trend behavior.
- Verification evidence: `mvn "-Dtest=Phase4AnalysisIntegrationTest,Phase5BackendIntegrationTest" test` passed with 48 tests, 0 failures, 0 errors.

## Session Addendum: 2026-04-26 AI Execution Consolidation Task 7
- Removed Java direct AI provider execution from `AiGatewayService`, leaving prompt rendering/token estimation and the cancellation handle while `AnalysisService` routes blocking, streaming, chunk, and merge execution through `langgraph-worker`.
- Updated legacy-mode streaming assertions to verify LangGraph worker streaming payloads and zero Java OpenAI-compatible calls instead of old OpenAI streaming helper behavior.
- Verified residual search results: `[chunk-progress]` remains only as non-provider chunk progress text, and `ReflectionTestUtils` remains in focused helper tests/cache inspection rather than old streaming assertions.
- Verification evidence: `mvn "-Dtest=AiGatewayServiceTest,AnalysisServiceTimeoutTest,LangGraphWorkerClientTest" test` passed with 18 tests, 0 failures, 0 errors.
- Verification evidence: `mvn "-Dtest=Phase4AnalysisIntegrationTest" test` passed with 31 tests, 0 failures, 0 errors.

## Session Addendum: 2026-04-26 AI Execution Consolidation Task 8
- Fixed the backend AI regression fixture by giving `Phase5BackendIntegrationTest` a mock LangGraph worker and internal API key now that legacy-compatible trend execution also routes through the worker.
- Verification evidence: `mvn "-Dtest=Phase4AnalysisIntegrationTest,LangGraphWorkerClientTest,AiGatewayServiceTest,AnalysisServiceTimeoutTest,Phase5BackendIntegrationTest" test` passed with 63 tests, 0 failures, 0 errors.
- Verification evidence: `python -m unittest discover -s tests -v` passed with 32 tests.
- Verification evidence: `npm test -- --run src/views/trend/__tests__/TrendView.spec.ts src/views/analysis/__tests__/AnalysisView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts` passed with 3 files and 32 tests.
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

## Session Addendum: 2026-06-23 (AI Q&A Golden Agent Regression)
### Security / Git State Check
- Confirmed current branch is `codex/new-development-20260509`.
- Confirmed tracked env files are only `.env.example` and `frontend/.env.example`.
- Confirmed pushed public head/tag state from Git: `origin/main` points to `decf22f` and local `v4` points to the same release commit.
- Found a local ignored temp restore env at `.tmp-v4-restore/.../.env`; it is ignored by `.gitignore` and not tracked. Do not push or stage it.

### TDD Work
- Added a real `GoldenEvalRunner + NovelResearchAgent` regression for `mixed-creation-001`.
- Red test evidence: `python -m pytest tests/test_golden_eval_suite_mixed_creation.py -q` failed with `retrieval:context_recall_at_k 0.5000 < 0.6000`.
- Root cause: golden relevant IDs use domain-level `chapter:*`, while rank research pack chapter sources evaluated as `chapter_pack:*`.
- Fixed `source_eval_id` to normalize `chapter_pack` to `chapter`.

### Verification Evidence
- `python -m pytest tests/test_golden_eval_suite_mixed_creation.py -q` passed with `3 passed`.
- `python -m pytest tests/test_golden_eval_runner.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_edge.py tests/test_retrieval_eval.py -q` passed with `18 passed`.
- `python -m pytest tests/test_intent_eval_suite.py tests/test_intent_router.py tests/test_retrieval_fusion.py tests/test_agent_supervisor.py tests/test_context_assembler.py tests/test_novel_research_agent.py -q` passed with `137 passed, 25 subtests passed`.

### Remaining Follow-up
- Add seeded MySQL/Qdrant golden runs that exercise actual backend rank/vector storage, not only fake clients.
- Build or expose a cross-layer ops view connecting intent decision, retrieval query, selected sources, prompt policy, model latency, fallback, and final answer.

## Session Addendum: 2026-06-24 (AI Q&A Verification + Server Package)
### Verification Evidence
- Ran worker AI Q&A regression suite: `python -m pytest tests/test_golden_eval_runner.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_edge.py tests/test_retrieval_eval.py tests/test_intent_eval_suite.py tests/test_intent_router.py tests/test_retrieval_fusion.py tests/test_agent_supervisor.py tests/test_context_assembler.py tests/test_novel_research_agent.py -q`; result: `155 passed, 25 subtests passed`.
- Ran backend compile check: `mvn -DskipTests test-compile`; result: `BUILD SUCCESS`.
- Ran frontend type check: `npm run type-check`; result: success with `vue-tsc --noEmit`.

### Package Output
- Created server package `D:\Git\agent\noval\noval-release-ai-qa-agent-20260624_012717.tar.gz`.
- SHA256: `DE3B38D7E12314E1C02DC9119B704428050AD05CCF0B09CF58171A9F5E97B77A`.
- Package content count: 846 entries.
- Content scan found only allowed `.env.example`; no real `.env`, planning notes, docs, temp restore dirs, release dirs, logs, Redis dumps, appendonly files, or private-key-style files.

### Notes
- Package includes the current working-tree AI golden eval changes from `langgraph-worker/app/services/evaluation/golden.py` and `langgraph-worker/tests/test_golden_eval_suite_mixed_creation.py`.
- `progress.md` remains a local tracked modification and should not be staged for GitHub under `AGENTS_GIT_NOTE.md`.

## Session Addendum: 2026-06-25 (AI Q&A SSE / Rank Limit / AgentTrace Repair)
### Debug + TDD Work
- Reproduced frontend red tests for compact Markdown headings, citation-repaired final SSE answers being ignored, ToolRuns wide-table layout, and missing AgentTrace overview metadata.
- Reproduced worker red tests for Top10 rank fallback, citation repair preserving trend answer mode, mixed-creation postprocess correcting Top5 when Top10 evidence exists, and prompt evidence including rank #10.

### Implementation Work
- Updated frontend Markdown rendering to normalize `##Heading` / `###Heading` outside code fences.
- Updated knowledge chat SSE handling so `done.data.answer` is authoritative after citation repair.
- Updated worker trend/mixed creation fallback, citation repair, postprocess, and prompt source limits so rank evidence follows `rankLimit` instead of chapter count or hard-coded Top5.
- Reworked AgentTrace tool runs from a wide table into readable run cards and added a desktop overview strip to the trace detail page.

### Verification Evidence
- `npm test -- --run src/lib/__tests__/markdown.spec.ts src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/components/knowledge/trace/__tests__/ToolRunsTable.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts` passed with 30 tests.
- `npm run type-check` passed.
- `python -m pytest tests/test_novel_research_agent.py -q` passed with 100 tests and 11 subtests.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

## Session Addendum: 2026-06-26 (AI Q&A SSE Latest Rank Refresh Repair)
### Debug + TDD Work
- Reproduced the domain-tool freshness bug: `python -m pytest tests/test_domain_tools.py -q` first failed because `rank.lookup` / `rank.research_pack` did not forward `freshness`, `allowHistorical`, `timeWindowDays`, or `requireSnapshotTime` to the backend client.
- Reproduced the SSE bug: `python -m pytest tests/test_novel_research_agent.py -k "stream_should_refresh_stale_rank" -q` first failed because stream mode returned `insufficient_evidence` without calling `refresh_rank_board`.
- Root cause: blocking `run()` used the runtime supervisor refresh loop, while `stream()` manually jumped from evidence retrieval to answer writing and skipped supervisor retry.

### Implementation Work
- Forwarded rank source freshness policy through `build_domain_tool_registry()` to `KnowledgeBackendClient.lookup_rank()` and `get_rank_research_pack()`.
- Added supervisor handling to `NovelResearchAgent.stream()` so stale/missing latest-rank evidence triggers one refresh and execute-tools retry before answering or blocking.
- Updated worker regressions so old-vector-only trend questions prove refresh is attempted before returning `insufficient_evidence`.

### Verification Evidence
- `python -m pytest tests/test_domain_tools.py -q` passed with 2 tests.
- `python -m pytest tests/test_novel_research_agent.py -q` passed with 104 tests and 11 subtests.
- `python -m pytest tests/test_domain_tools.py tests/test_knowledge_client.py -q` passed with 11 tests.
- `python -m pytest tests/test_knowledge_api.py tests/test_intent_router.py -q` passed with 30 tests and 14 subtests.
- `python -m pytest tests/test_golden_eval_runner.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_edge.py tests/test_retrieval_eval.py tests/test_intent_eval_suite.py tests/test_intent_router.py tests/test_retrieval_fusion.py tests/test_agent_supervisor.py tests/test_context_assembler.py tests/test_novel_research_agent.py tests/test_domain_tools.py tests/test_knowledge_client.py tests/test_knowledge_api.py -q` passed with 182 tests and 25 subtests.
- `mvn -DskipTests test-compile` in `backend` passed.
- `npm run type-check` in `frontend` passed.
- `python -m pytest tests -q` in `crawler` passed with 56 tests and 25 warnings.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

### Package Output
- Created server package `D:\Git\agent\noval\noval-release-ai-qa-agent-20260626_023226.tar.gz`.
- SHA256: `6EB7A8C77B1C013AFC253C6CB851D9CD85A5630428DDAA81239CA8426C6443C2`.
- Package content count: 651 entries.
- Content scan found only allowed `.env.example`; no real `.env`, planning files, key-like files, or missing required AI Q&A files.

## Session Addendum: 2026-06-26 (Mixed Creation Rank Snapshot Degradation)
### Debug + TDD Work
- Reproduced the user trace class with `test_mixed_creation_should_degrade_when_refresh_returns_rank_rows_without_snapshot_time`; the red result was `answered != insufficient_evidence`.
- Root cause: refreshed rank tools could return 10 structured rows, but missing `snapshotTime` kept the latest-rank evidence gate failed and the supervisor re-blocked the mixed creation answer.

### Implementation Work
- Kept pure market/trend questions strict.
- Added a narrow mixed-creation degradation path after one refresh attempt when structured top-rank rows exist but snapshot metadata is missing, stale, or invalid.
- Marked degraded traces with `latestRankEvidenceDegraded`, preserved the original `trendGateReason`, and relaxed `requireSnapshotTime` only for that degraded mixed-creation path.

### Verification Evidence
- `python -m pytest tests/test_novel_research_agent.py -k "degrade_when_refresh_returns_rank_rows_without_snapshot_time" -q` passed.
- `python -m pytest tests/test_novel_research_agent.py -q` passed with 105 tests and 11 subtests.
- `python -m pytest tests/test_domain_tools.py tests/test_knowledge_client.py tests/test_knowledge_api.py tests/test_intent_router.py -q` passed with 41 tests and 14 subtests.
- Worker combo suite passed with 183 tests and 25 subtests.
- `mvn -DskipTests test-compile` in `backend` passed.
- `npm run type-check` in `frontend` passed.
- `python -m pytest tests -q` in `crawler` passed with 56 tests and 25 warnings.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

## Session Addendum: 2026-06-26 (Mixed Creation Chapter Evidence Gate Repair)
### Debug + TDD Work
- Reproduced the current user prompt class in the worker tests with real Chinese intent routing.
- Confirmed the router classifies the full low-level-job urban-brainstorm prompt as `mixed_creation_research`.
- Confirmed the failure was not missing skills or rank tools: the block happened after successful rank/tool retrieval because the answer writer required chapter-level evidence for creative keywords.

### Implementation Work
- Added a state-aware chapter-evidence gate in `NovelResearchAgent`: explicit existing-book analysis still requires chapter evidence, while new-book mixed creation can answer from fresh rank evidence plus labeled author inference.
- Updated the trend source policy to treat `snapshotId` as a valid structured rank snapshot marker when `snapshotTime` is absent.
- Added regressions for the full user prompt, snapshot-id-only rank evidence, and preserved existing single-book chapter-evidence behavior.

### Verification Evidence
- `python -m pytest tests/test_novel_research_agent.py -k "snapshot_id_only_rank_snapshot or chapter_level_book_question or book_research_pack_for_chapter_level_question" -q` passed with 4 tests and 102 deselected.
- `python -m pytest tests/test_intent_router.py tests/test_novel_research_agent.py -q` passed with 132 tests and 25 subtests.
- Worker AI Q&A suite passed: `185 passed, 25 subtests passed`.
- `mvn -DskipTests test-compile` in `backend` passed.
- `npm run type-check` in `frontend` passed.
- `python -m pytest tests -q` in `crawler` passed with 56 tests and 25 warnings.
- Frontend AI Q&A related Vitest suite passed with 8 files and 51 tests.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

### Package Output
- Created server package `D:\Git\agent\noval\noval-release-ai-qa-agent-20260626_235730.tar.gz`.
- SHA256: `B55B81AD9DB1306A978CB330FF5780E67CCA67DB784AF21DE9E44CF80EC4FEF2`.
- Package content count: 858 entries.
- Content scan found zero blocked entries and confirmed required AI Q&A, frontend project-space, backend knowledge, and crawler files are present.

## Session Addendum: 2026-06-27 (Mixed Creation Lookup-Only Snapshot Hardening)
### Debug + TDD Work
- Investigated user trace `206bc0e218dc4c9ea0c3e7fbd88965a1`, where `rank.lookup` returned 10 results but the answer still blocked with `insufficient_evidence`.
- Added lookup-only snapshotless mixed-creation regressions for both blocking `run()` and SSE `stream()`.
- Red test evidence: the tightened assertions failed because the current path required `retryCounts.market_refresh=1` before degradation.

### Implementation Work
- Changed `_should_degrade_latest_rank_gate` so mixed creation can degrade immediately for `missing_structured_rank_snapshot` when structured front-rank rows exist.
- Kept pure market-scan questions strict, and kept stale/invalid snapshot timestamps refresh-first.
- Updated the older mixed-creation degradation test to reflect the new first-pass degradation policy.

### Verification Evidence
- `python -m pytest tests/test_novel_research_agent.py -k "lookup_only_rank_lacks_snapshot_metadata or stream_should_not_block_lookup_only_snapshotless_rank" -q` passed with 2 tests.
- Neighboring boundary suite passed with 8 tests.
- Worker AI Q&A suite passed with 187 tests and 25 subtests.
- `mvn -DskipTests test-compile` in `backend` passed.
- `npm run type-check` in `frontend` passed.
- `python -m pytest tests -q` in `crawler` passed with 56 tests and 25 warnings.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

### Package Output
- Created server package `D:\Git\agent\noval\noval-release-ai-qa-agent-20260627_001918.tar.gz`.
- SHA256: `F0BA2F4AA67C4D8A49D6761BD8B15742D8A0BC7775DB02F72F2A971311C57C10`.
- Package content count: 858 entries.
- Content scan found zero blocked entries and confirmed required worker, frontend, backend, and crawler files are present.

## Session Addendum: 2026-06-27 (AI Agent MCP Skills Memory Full Plan)
### Planning Work
- Captured the full implementation direction for rebuilding AI Q&A into a multi-agent runtime with IntentAgent, EvidenceArbiter, FastMCP internal tools, LLM tool-calling, skills, scoped memory, Qdrant semantic memory, admin memory governance, and trace upgrades.
- Confirmed user decisions:
  - FastMCP tools must be callable by the agent through the external LLM tool-calling loop.
  - FastMCP must remain internal to Docker Compose and not be exposed publicly.
  - External LLM APIs receive schemas/results only; `langgraph-worker` executes MCP tools locally.
  - Memory must be scoped, bounded, summarized, and recall-limited rather than storing all chats forever.
  - Qdrant memory should use a separate `noval_ai_memory` collection.
  - Intent recognition may use a second LLM call.
  - Admin-only memory governance UI is required.

### Plan Output
- Created `D:\Git\agent\noval\docs\superpowers\plans\2026-06-27-ai-agent-mcp-skills-memory-full-implementation.md`.
- Plan includes 13 phases: EvidenceArbiter, IntentAgent V3, memory schema/Qdrant, MemoryAgent, FastMCP, MCP client/tool loop, skills, multi-agent handoff, trace, admin memory UI, security, golden eval, and deployment compatibility.

## Session Addendum: 2026-06-27 (EvidenceArbiter Phase 1 Integration)
### Debug + TDD Work
- Resumed implementation from session `019efa97-a027-7ff1-9632-35e2befe6960`.
- Confirmed `tests/test_evidence_arbiter.py` already covered the four Phase 1 arbiter cases.
- Reproduced the agent-level red test: mixed creation with successful `rank.lookup` and `rank.research_pack` still returned `insufficient_evidence` because the legacy trend gate blocked `mixed_structured_rank_snapshot`.

### Implementation Work
- Wired `EvidenceArbiter` into `NovelResearchAgent` only for `mixed_creation_research` when the legacy trend gate reason is `mixed_structured_rank_snapshot`.
- Added `evidenceContract` to `sourcePolicy`, preserved `trendGateOriginalReason`, and demoted the answer boundary through `latestRankEvidenceDegraded`.
- Tagged rank sources with `retrievalBackend` so arbiter scoring can prefer `rank.research_pack` over `rank.lookup`.
- Filtered final rank sources to the selected coherent snapshot group while keeping non-rank contextual sources.
- Kept pure market-scan mixed snapshot behavior strict: it still blocks or requests refresh.

### Verification Evidence
- `python -m pytest tests/test_evidence_arbiter.py -q` passed with 4 tests.
- Red/green agent regression passed: `python -m pytest tests/test_novel_research_agent.py -k "mixed_snapshot or mixed_creation_should_arbitrate" -q`.
- Targeted Phase 1 suite passed: `python -m pytest tests/test_evidence_arbiter.py tests/test_novel_research_agent.py -k "mixed_snapshot or mixed_creation or trend_rank_gate" -q` passed with 9 tests.
- Full worker agent test file passed: `python -m pytest tests/test_novel_research_agent.py -q` passed with 109 tests and 11 subtests.
- `git diff --check -- langgraph-worker/app/services/novel_research_agent.py langgraph-worker/app/models/evidence_contract.py langgraph-worker/app/services/runtime/evidence_arbiter.py langgraph-worker/tests/test_evidence_arbiter.py langgraph-worker/tests/test_novel_research_agent.py` passed; only Windows LF-to-CRLF warnings were emitted.

## Session Addendum: 2026-06-27 (IntentAgent Phase 2 Integration)
### TDD Work
- Added `IntentAgent` coverage for rule-first routing, optional LLM fallback, supervisor repair, mixed creation intent, project follow-up, and out-of-scope boundaries.
- Preserved compatibility for tests that replace `NovelResearchAgent.intent_router` with custom routers by disabling fallback for non-standard router instances.

### Implementation Work
- Added `langgraph-worker/app/services/runtime/intent_agent.py` with `FastIntentClassifier`, `LLMIntentAgent`, `IntentSupervisor`, and `IntentAgent`.
- Wired `NovelResearchAgent._classify_domain_intent()` through `IntentAgent.decide()`.
- Added `AGENT_INTENT_LLM_FALLBACK_ENABLED` and `AGENT_INTENT_LLM_MIN_CONFIDENCE` config support.

### Verification Evidence
- `python -m pytest tests/test_intent_agent.py tests/test_intent_router.py -q` passed with 33 tests and 14 subtests.
- `python -m pytest tests/test_intent_agent.py tests/test_intent_router.py tests/test_novel_research_agent.py -q` passed with 142 tests and 25 subtests.

## Session Addendum: 2026-06-27 (Memory Schema And Qdrant Phase 3)
### Debug + TDD Work
- Reproduced the backend memory service failure: H2 returned generated keys with `id`, `created_at`, and `updated_at`, so `GeneratedKeyHolder.getKey()` threw before candidate creation could complete.
- Added Qdrant memory collection tests and config validation tests for the dedicated `noval_ai_memory` collection.
- Added internal controller tests for memory candidate create/promote/search and conversation summary update/read.

### Implementation Work
- Added Phase 13 MySQL and H2 schema files for `ai_memory_item`, expanded-compatible `ai_memory_candidate`, and `ai_conversation_summary`.
- Added scoped memory DTOs and internal endpoints for memory candidates, confirmed memory search, and rolling conversation summaries.
- Added `KnowledgeProperties.qdrant.memoryCollection`, `QdrantClient.ensureMemoryCollection()`, and deployment config for `KNOWLEDGE_QDRANT_MEMORY_COLLECTION`.
- Fixed generated key extraction in `KnowledgeMemoryService` to read the `id` key from multi-column generated-key responses.

### Verification Evidence
- `mvn "-Dtest=KnowledgeMemoryServiceTest,KnowledgeConversationSummaryServiceTest" test` passed with 4 tests.
- `mvn "-Dtest=QdrantClientTest,KnowledgeConfigValidatorTest" test` passed with 14 tests.
- `mvn "-Dtest=KnowledgeInternalControllerTest" test` passed with 13 tests.
- Phase 3 aggregate verification passed: `mvn "-Dtest=KnowledgeMemoryServiceTest,KnowledgeConversationSummaryServiceTest,QdrantClientTest,KnowledgeConfigValidatorTest,KnowledgeInternalControllerTest" test` passed with 31 tests, 0 failures, 0 errors.

## Session Addendum: 2026-06-27 (MemoryAgent And MemoryExtractor Phase 4)
### TDD Work
- Added worker tests for scoped memory loading: conversation summary, project memory, user memory, and semantic memory recall with topK limits.
- Added worker tests for memory extraction of project settings, temporary thread preferences, long-term user preferences, and backend candidate persistence.
- Added `KnowledgeBackendClient` tests for conversation summary read, confirmed memory search, and memory candidate creation.

### Implementation Work
- Added `MemoryAgent` to load memory context from backend APIs without direct database access.
- Added `MemoryExtractor` to extract scoped candidates and persist them best-effort after answer generation.
- Extended `KnowledgeBackendClient` with memory summary, memory search, and candidate creation methods.
- Wired `NovelResearchAgent` to load `memory_context`, expose it in trace context, and write memory candidates on both graph and finalize fallback paths.

### Verification Evidence
- `python -m pytest tests/test_memory_agent.py tests/test_memory_extractor.py -q` passed with 6 tests.
- `python -m pytest tests/test_knowledge_client.py -q` passed with 12 tests.
- `python -m pytest tests/test_memory_agent.py tests/test_memory_extractor.py tests/test_knowledge_client.py tests/test_memory_candidates.py -q` passed with 22 tests.
- `python -m pytest tests/test_memory_agent.py tests/test_memory_extractor.py tests/test_novel_research_agent.py -q` passed with 115 tests and 11 subtests.

## Session Addendum: 2026-06-27 (FastMCP Internal Tool Service Phase 5)
### TDD Work
- Added `mcp-tools/tests/test_mcp_tools.py` for internal-token rejection, rank schema validation, backend token forwarding, project memory required args, and normal toolset hiding admin tools.

### Implementation Work
- Added a new internal `mcp-tools` FastAPI service with MCP-style `/mcp/tools` and `/mcp/call` endpoints.
- Added tool registry and tool groups for rank, book, knowledge, skill, memory, reader, and editor tools.
- Added internal token verification for MCP calls and backend internal-token forwarding for backend tool calls.
- Added `mcp-tools/Dockerfile`, `pyproject.toml`, and Compose service `fastmcp-tools` with `expose: 7001` and no public port mapping.
- Added `.env.example` and `langgraph-worker` compose variables for `MCP_INTERNAL_API_KEY` and `AI_MCP_BASE_URL`.

### Verification Evidence
- Red test evidence: `python -m pytest tests -q` in `mcp-tools` first failed because `app.backend_client` and service files did not exist.
- `python -m pytest tests -q` in `mcp-tools` passed with 5 tests.

## Session Addendum: 2026-06-27 (MCP Client And Tool-Calling Loop Phase 6)
### TDD Work
- Added worker MCP registry tests for OpenAI-compatible tool schema conversion, route allowlist enforcement, supervisor permission override, and required-argument validation.
- Added tool-call loop tests for model-requested MCP execution, disallowed tool rejection, invalid argument tool errors, rank refresh supervisor permission, and redacted tool results.

### Implementation Work
- Added `langgraph-worker/app/services/mcp/client.py` and `tool_registry.py`.
- Added `langgraph-worker/app/services/runtime/tool_call_loop.py` with route permissions, per-turn/per-tool limits, MCP execution, and redaction.
- Extended worker settings with `AI_MCP_BASE_URL`, `MCP_INTERNAL_API_KEY`, and `AI_MCP_TIMEOUT_MILLIS`.
- Extended `OpenAICompatibleProviderClient.invoke()` to accept optional OpenAI-compatible `tools` payloads while preserving existing call behavior.

### Verification Evidence
- `python -m pytest tests/test_mcp_tool_registry.py -q` passed with 3 tests.
- `python -m pytest tests/test_tool_call_loop.py -q` passed with 4 tests.
- `python -m pytest tests/test_provider_client.py tests/test_mcp_tool_registry.py tests/test_tool_call_loop.py -q` passed with 13 tests.

## Session Addendum: 2026-06-27 (Skills Capability Packs Phase 7)
### TDD Work
- Added skill registry coverage for task-graph based selection: market scan, rank evidence arbitration, outline building, reader risk review, and skill governance boundaries.
- Kept task-only skill packs out of legacy intent-only selection by using task/context selection fields.

### Implementation Work
- Extended runtime skill metadata with `appliesTo`, `allowedTools`, `requiredEvidence`, `guardrails`, and examples.
- Added task-context skill selection via `select_for_task`.
- Added new packs for rank arbitration, topic strategy, opening hook, reader risk, editor review, and project memory extraction.

### Verification Evidence
- `python -m pytest tests/test_skill_registry.py -q` passed with 9 tests.

## Session Addendum: 2026-06-27 (Multi-Agent Handoff Phase 8)
### TDD Work
- Added specialist-agent coverage for market scan, author strategy, reader risk, editor review, supervisor boundaries, and mixed-creation handoff ordering.

### Implementation Work
- Extended specialist result payloads with `status`, `summary`, `evidenceRefs`, `warnings`, and `toolCalls`.
- Added author strategy, reader risk, editor, and supervisor specialist agents.
- Wired `mixed_creation_research` handoff order through market scan, author strategy, opening strategy, outline, reader risk, editor, and supervisor.
- Added specialist context diagnostics for evidence contracts and memory context.

### Verification Evidence
- `python -m pytest tests/test_specialist_agents.py -q` passed with 9 tests.
- `python -m pytest tests/test_specialist_agents.py tests/test_novel_research_agent.py -k "specialist or inject_runtime_skills or mixed_creation" -q` passed with 18 tests and 100 deselected.

## Session Addendum: 2026-06-27 (AgentTrace Upgrade Phase 9)
### TDD Work
- Added frontend trace assertions for MCP calls, tool permissions, evidence contracts, snapshot arbitration, handoffs, and final answer boundary display.
- Confirmed backend trace hydration already covered intent, context, memory, source policy, supervisor decision, MCP calls, evidence contract, snapshot groups, specialist results, and final answer boundary.

### Implementation Work
- Extended frontend `AgentTraceSummary` with Phase 9 trace fields.
- Added admin trace sections for MCP tool calls, permission decisions, evidence contract, snapshot arbitration, agent handoffs, and final answer boundary.

### Verification Evidence
- `mvn "-Dtest=KnowledgeAgentTraceServiceTest" test` passed with 4 tests.
- `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts` passed with 2 tests.

## Session Addendum: 2026-06-27 (Admin Memory UI Phase 10)
### TDD Work
- Added admin memory UI coverage for listing, filtering, approving/rejecting candidates, deleting memory, and source trace visibility.
- Added shell/sidebar coverage for the admin memory entry.

### Implementation Work
- Added admin memory service methods and controller endpoints for listing confirmed memories, listing candidates, approving/rejecting candidates, and soft deleting memories.
- Added frontend memory admin API/types and `AdminMemoryView`.
- Added router/sidebar entry for `/knowledge/admin/memories`.

### Verification Evidence
- `npm test -- --run src/views/knowledge/__tests__/AdminMemoryView.spec.ts` passed with 1 test.
- `npm test -- --run src/views/knowledge/__tests__/AdminMemoryView.spec.ts src/layouts/__tests__/AppShell.spec.ts` passed with 10 tests.
- `mvn "-Dtest=KnowledgeMemoryServiceTest,KnowledgeInternalControllerTest,KnowledgeChatServiceTest" test` passed with 27 tests.

## Session Addendum: 2026-06-27 (Security And Abuse Controls Phase 11)
### TDD Work
- Added MCP security coverage for missing internal token, invalid tool name, admin tool denial on normal routes, and arbitrary URL/path/SQL argument rejection.
- Added worker tool-loop coverage for unsafe argument rejection.

### Implementation Work
- Added deny-by-default unsafe argument validation in `mcp-tools`.
- Validated raw tool arguments before Pydantic drops unknown risky fields, then validated parsed arguments again.
- Added matching unsafe argument validation in the worker MCP tool registry.

### Verification Evidence
- `python -m pytest tests/test_security.py tests/test_mcp_tools.py -q` in `mcp-tools` passed with 9 tests.
- `python -m pytest tests/test_tool_call_loop.py -q` in `langgraph-worker` passed with 5 tests.

## Session Addendum: 2026-06-27 (Golden Eval And Production Regressions Phase 12)
### TDD And Debug Work
- Added golden-runner metric coverage for tool selection, evidence contract correctness, answer boundary, citation presence, memory isolation, and trace completeness.
- Added production trace regressions:
  - `b41ae9117abb4285b2ec17433749ebcc`: mixed rank snapshots from `rank.lookup` and `rank.research_pack` must answer with an EvidenceContract instead of `insufficient_evidence`.
  - `206bc0e218dc4c9ea0c3e7fbd88965a1`: lookup-only snapshotless rank rows must answer mixed creation as `degraded_directional`.
  - Pure market mixed snapshot evidence must block or request refresh and must not use creative inference.
- Red test evidence: the lookup-only degraded path answered but did not attach `evidenceContract` to `sourcePolicy`.

### Implementation Work
- Added `GoldenEvalExpectedTrace` and runner trace/runtime metrics.
- Updated the degraded mixed-creation rank gate to attach the EvidenceArbiter contract to `sourcePolicy`.
- Added mixed-creation and market golden-suite fake clients that reproduce mixed snapshot and snapshotless rank evidence.

### Verification Evidence
- `python -m pytest tests/test_golden_eval_runner.py -q` passed with 6 tests.
- `python -m pytest tests/test_golden_eval_suite_mixed_creation.py -q` passed with 5 tests.
- `python -m pytest tests/test_golden_eval_suite_market.py -q` passed with 5 tests.
- `python -m pytest tests/test_golden_eval_runner.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_edge.py tests/test_novel_research_agent.py -q` passed with 130 tests and 11 subtests.

## Session Addendum: 2026-06-27 (Deployment And Compatibility Phase 13)
### Verification Work
- Used a parallel explorer subagent for read-only Phase 13 inspection. It confirmed `fastmcp-tools` has only internal `expose: 7001`, no service-level `ports`, worker MCP env wiring is present, and `/health` exists.
- Ran `docker compose config`; expanded config confirmed `fastmcp-tools` has no public port and `langgraph-worker` depends on it with internal MCP URL, token, and timeout configuration.
- Attempted `docker compose build fastmcp-tools langgraph-worker backend`; this was blocked because the local Docker Desktop Linux engine was not running and the Docker API pipe was unavailable.

### Final Verification Evidence
- Worker final matrix: `python -m pytest tests/test_evidence_arbiter.py tests/test_intent_agent.py tests/test_memory_agent.py tests/test_memory_extractor.py tests/test_mcp_tool_registry.py tests/test_tool_call_loop.py tests/test_skill_registry.py tests/test_specialist_agents.py tests/test_novel_research_agent.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_market.py -q` passed with 162 tests and 11 subtests.
- MCP full tests: `python -m pytest tests -q` in `mcp-tools` passed with 9 tests.
- Backend targeted matrix: `mvn "-Dtest=KnowledgeMemoryServiceTest,KnowledgeConversationSummaryServiceTest,KnowledgeAgentTraceServiceTest,KnowledgeSkillGovernanceServiceTest,KnowledgeChatServiceTest,KnowledgeInternalControllerTest" test` passed with 36 tests. The plan-listed `KnowledgeMemoryAdminServiceTest` class is not present in this codebase; admin memory behavior is covered in `KnowledgeMemoryServiceTest`.
- Frontend targeted matrix: `npm test -- --run src/views/knowledge/__tests__/AdminMemoryView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/views/knowledge/__tests__/KnowledgeChatView.spec.ts` passed with 31 tests.
- Frontend type check: `npm run type-check` passed.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

## Session Addendum: 2026-06-27 (Agent Runtime Gap Closure And DeepSeek Reasoning Mode)
### Re-Audit Work
- Re-checked the full AI Agent MCP/skills/memory plan against the current code after the user flagged that the implementation did not match the plan.
- Confirmed the main omissions: `ToolCallLoop` existed but was not wired into `NovelResearchAgent`, and specialist agents were deterministic prompt fragments rather than LLM-backed subagents.
- Checked DeepSeek official thinking-mode docs and the DeepSeek V4 WeChat article. Implementation target: `fast` sends `thinking.disabled`; `deep` sends `thinking.enabled` with `reasoning_effort=max`, and thinking-mode tool calls preserve `reasoning_content`.

### TDD Work
- Added backend tests for forwarding explicit `reasoningMode=deep` and using admin default `ai.knowledge.reasoning-mode.default` when the user request omits the mode.
- Added worker provider tests for DeepSeek thinking-mode payloads in fast/deep modes.
- Added worker tool-loop test proving thinking-mode tool calls preserve assistant `reasoning_content` and `tool_calls` before appending tool results.
- Added frontend chat coverage for selecting `reasoningMode=deep` and persisting that choice.
- Added admin system config coverage for updating `ai.knowledge.reasoning-mode.default` from a segmented fast/deep control.

### Implementation Work
- Added `reasoningMode` to backend and worker knowledge chat requests.
- Added known system config `ai.knowledge.reasoning-mode.default`, defaulting to `fast`.
- Wired backend `KnowledgeChatService` to normalize and forward explicit or admin-default reasoning mode to the worker.
- Extended `OpenAICompatibleProviderClient` to send DeepSeek thinking mode parameters and return `reasoning_content` / tool call metadata.
- Updated `ToolCallLoop` to pass reasoning mode and preserve assistant reasoning/tool-call messages for DeepSeek thinking-mode tool loops.
- Added the AI Q&A fast/deep mode selector to the chat composer and included `reasoningMode` in stream chat payloads.
- Added the administrator default reasoning mode control to the system config page.
- Restored the `KnowledgeChatService` constructor overload that accepts `KnowledgeMemoryCandidateService` without `SystemConfigService`, preserving existing unit-test/manual construction paths while falling back to `fast`.

### Verification Evidence
- `python -m pytest tests/test_provider_client.py tests/test_tool_call_loop.py -q` in `langgraph-worker` passed with 14 tests.
- `python -m pytest tests/test_specialist_agents.py tests/test_novel_research_agent.py -k "specialist or mcp_tools_in_main_answer_path" -q` in `langgraph-worker` passed with 12 tests and 108 deselected.
- `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts` passed with 33 tests.
- `npm run type-check` passed.
- `mvn "-Dtest=KnowledgeChatServiceTest,SystemConfigServiceTest" test` passed with 16 tests.
- `git diff --check -- backend/src/main/java/com/novelanalyzer/modules/knowledge/service/KnowledgeChatService.java frontend/src/types/knowledge.ts frontend/src/types/config.ts frontend/src/composables/useKnowledgeChat.ts frontend/src/views/knowledge/KnowledgeChatView.vue frontend/src/views/knowledge/__tests__/KnowledgeChatView.spec.ts frontend/src/views/config/system/SystemConfigView.vue frontend/src/views/config/system/__tests__/SystemConfigView.spec.ts` passed; only Windows LF-to-CRLF warnings were emitted.

## Session Addendum: 2026-06-28 (LLM-Backed Specialist Agents And Per-Agent MCP)
### TDD Work
- Added specialist-agent regressions proving selected handoff agents independently call the provider, can run their own MCP tool loop, preserve DeepSeek reasoning mode, and expose agent-level MCP tool runs.
- Added provider and tool-loop regressions for `reasoning_effort=high` while keeping deep-thinking default at `max`.
- Corrected the user's typo boundary: the public modes remain `fast` and `deep`; no `flash` reasoning mode alias was productized.

### Implementation Work
- Extended `BaseSpecialistAgent.run_llm()` so LLM-enabled specialists can either directly call the model or run `ToolCallLoop` with their own role-specific MCP route.
- Enabled all 13 selectable specialist agents for independent LLM calls. Market/book/evidence-heavy agents use deep `max` by default; lighter execution/review agents can request deep `high`.
- Wired `NovelResearchAgent` to pass MCP client/registry into specialist execution when MCP is configured or injected, and added top-level `specialistToolCalls` trace output.
- Added optional `reasoning_effort` support through provider and tool-call loop payloads.
- Fed specialist plans into pure creative answer prompts so specialist model calls influence the final answer instead of running as unused side work.

### Verification Evidence
- `python -m pytest tests/test_provider_client.py tests/test_tool_call_loop.py tests/test_specialist_agents.py tests/test_mcp_tool_registry.py tests/test_skill_registry.py -q` passed with 41 tests.
- `python -m pytest tests/test_novel_research_agent.py -q` passed with 111 tests and 11 subtests in 189.81s.
- `python -m pytest tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_market.py -q` passed with 10 tests.
- `python -m pytest tests -q` in `mcp-tools` passed with 9 tests.

## Session Addendum: 2026-06-28 (SSE Final Answer And Board Analysis Repair)
### Debug And TDD Work
- Reproduced the frontend bug with a red test: `citationRepairUsed=true` caused the done payload to overwrite the richer SSE streamed answer.
- Added worker regressions for Top30 rank evidence entering mixed-creation prompts and for citation repair preserving a structured market-plus-outline answer.
- Confirmed the old pure-trend fallback behavior should remain for unstructured single-sentence trend answers.

### Implementation Work
- Stopped the frontend from replacing the visible streamed answer with the citation-repaired done answer.
- Raised AI Q&A default rank coverage from 10 to 30 in the chat request.
- Raised worker rank-analysis runtime caps so explicit Top30/full-board requests can carry more rank evidence, while answer prompts and fallback blocks now cover up to 30 rank rows by default.
- Added in-place citation repair for structured answers so market analysis, trend sections, and outlines are preserved instead of collapsed into a fallback summary.
- Expanded `webnovel-market-scan` and `webnovel-outline-building` skill contracts so board analysis requires real sample coverage and mixed market-plus-outline answers produce concrete author-side structure.

### Verification Evidence
- `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts` passed with 27 tests.
- `python -m pytest tests/test_skill_registry.py -q` passed with 9 tests.
- `python -m pytest tests/test_novel_research_agent.py -q` passed with 113 tests and 11 subtests.

## Session Addendum: 2026-06-30 (MoE Expert Router Execution Plan)
### Recovery And Planning Work
- Resumed session `019f0e62-e721-76c1-9bbf-748b4e8085c1` from local Codex JSONL history and restored the active project plan context.
- Confirmed the current desktop tool list still does not expose a true subagent dispatch tool, so execution will use explicit worker/backend/frontend lanes in this session with TDD and review checkpoints.
- Re-read the approved MoE multi-agent design and current worker code. Existing runtime already includes TaskGraph, EvidenceArbiter, MCP tool loop, LLM-backed specialist agents, memory, and trace metadata.
- Identified the next narrow gap: specialist selection remains hard-coded in `agents/base.py`; there is no configurable `ExpertRegistry` / `ExpertRouter` layer or selected-expert route reason in trace.
- Added implementation plan `docs/superpowers/plans/2026-06-29-webnovel-moe-agent-admin-execution.md` covering ExpertRegistry, router trace, and a minimal admin/runtime-policy follow-up.

### TDD And Implementation Work
- Added `langgraph-worker/tests/test_expert_registry.py` red coverage for default mixed-creation handoff order, fast-mode Top-K cap, disabled experts, and TaskGraph task-type routing.
- Added `langgraph-worker/app/services/agents/expert_registry.py` with `ExpertProfile`, `ExpertRoute`, `ExpertRoutingResult`, `ExpertRegistry`, and `ExpertRouter`.
- Wired `agents/base.py` so existing `select_agents()` stays compatible while new `route_agents()` and `run_specialists_parallel(..., expert_route=...)` expose router decisions and diagnostics.
- Wired `NovelResearchAgent` to route specialists once per turn, use router max parallel metadata, expose `selectedExperts`, `expertRouter`, and `budgets.maxParallelSpecialists`, and mirror router data into trace.
- Added backend Agent Trace hydration for `selectedExperts` and `expertRouter`.
- Added frontend Admin Agent Trace `Expert Router` read-only section and TS fields.

### Verification Evidence
- Red test evidence: `python -m pytest tests/test_expert_registry.py -q` initially failed because `ExpertProfile` was not exported.
- Red test evidence: `python -m pytest tests/test_specialist_agents.py -k "route_agents or precomputed_route" -q` initially failed because `route_agents` was not exported.
- Red test evidence: `python -m pytest tests/test_novel_research_agent.py::NovelResearchAgentTest::test_should_inject_runtime_skills_and_specialist_agent_context -q` initially failed with missing `selectedExperts`.
- Red test evidence: `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts` initially failed because `Expert Router` was not rendered.
- Red test evidence: `mvn "-Dtest=KnowledgeAgentTraceServiceTest" test` initially failed at test compile because `KnowledgeAgentTraceVO` lacked `getSelectedExperts()` and `getExpertRouter()`.
- `python -m pytest tests/test_expert_registry.py tests/test_specialist_agents.py -q` passed with 19 tests.
- `python -m pytest tests/test_expert_registry.py tests/test_specialist_agents.py tests/test_novel_research_agent.py -k "expert or specialist or selected_expert or max_parallel" -q` passed with 21 tests and 111 deselected.
- `python -m pytest tests/test_novel_research_agent.py -q` passed with 113 tests and 11 subtests.
- `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts` passed with 2 tests.
- `mvn "-Dtest=KnowledgeAgentTraceServiceTest" test` passed with 4 tests.
- `npm run type-check` passed.
- `git diff --check -- <touched files>` passed; only Windows LF-to-CRLF warnings were emitted.

## Session Addendum: 2026-06-30 (MoE Full-Spec Re-Audit)
### Review Work
- Re-read the 2026-06-28 MoE design sections for architecture, admin configuration, DB/API suggestions, phased rollout, and priority list.
- Re-read the 2026-06-29 execution plan and confirmed it intentionally implements only the first production slice, not the complete MoE admin/eval/cache platform.
- Searched production code and tests for ExpertRouter, TaskGraph, ContextAssembler, SkillGovernance, SkillEvalRunner, cache/token stats, proposed DB tables, and proposed admin/internal APIs.

### Verification Evidence
- `python -m pytest tests/test_expert_registry.py tests/test_agent_task_graph.py tests/test_context_assembler.py tests/test_skill_registry.py tests/test_golden_eval_runner.py -q` passed with 30 tests.
- `mvn "-Dtest=KnowledgeAgentTraceServiceTest,KnowledgeSkillGovernanceServiceTest,SystemConfigServiceTest" test` passed with 10 tests.
- `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts` passed with 10 tests.

### Re-Audit Result
- The 2026-06-29 plan is substantially implemented for its declared slice: ExpertRegistry/ExpertRouter, route metadata, Trace visibility, and compatibility tests.
- The full 2026-06-28 design is not complete: DB-backed runtime/expert config, editable expert profile admin, SkillEvalRunner/eval gate, cache/token stats, and the proposed admin/internal API family remain future work.

## Session Addendum: 2026-06-30 (MoE Governance Completion Plan)
### Planning Work
- Added `docs/superpowers/plans/2026-06-30-webnovel-moe-governance-completion.md`.
- Split remaining work into Backend, Worker, Frontend, Skill Eval, Cache/Token, Trace-to-Golden, Preconditions/Golden, and Reviewer lanes.
- Recorded the local limitation that no true subagent dispatch tool is available, so execution proceeds through explicit lanes with sequential edits and review checkpoints.

### Next Execution Target
- Start with Runtime Policy + Expert Profile governance APIs because this is the smallest cross-layer slice that closes a concrete admin-config gap and feeds the already implemented ExpertRouter.

## Session Addendum: 2026-06-30 (MoE Governance Completion Execution And Acceptance)
### Execution Work
- Updated `docs/superpowers/plans/2026-06-30-webnovel-moe-governance-completion.md` with execution status after re-auditing the 2026-06-28 MoE governance design.
- Completed the Backend lane for Runtime Policy, Expert Profile, internal worker read APIs, and cache/token trace aggregation skeleton.
- Completed the Worker lane for governance config fetch, fail-open defaults, admin profile overlays in `ExpertRegistry`, and `runtimeConfig` / `expertRouter` trace metadata.
- Completed the Frontend lane for `AdminAgentGovernanceView`, API/types, route/sidebar entry, runtime policy edits, expert toggles/budgets, and read-only cache/token stats.
- Reviewer lane conclusion: Task 1, Task 2, Task 3, and Task 5 from the 2026-06-30 plan are implemented and verified. Task 4 is only a minimal failed-eval approval guard; Task 6 and Task 7 remain open.

### Verification Evidence
- `mvn "-Dtest=KnowledgeAgentGovernanceServiceTest,KnowledgeInternalControllerTest,KnowledgeAgentTraceServiceTest,KnowledgeSkillGovernanceServiceTest,SystemConfigServiceTest" test` in `backend` passed with 29 tests, 0 failures, 0 errors.
- `python -m pytest tests/test_expert_registry.py tests/test_knowledge_client.py tests/test_novel_research_agent.py tests/test_golden_eval_runner.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_edge.py -q` in `langgraph-worker` passed with 154 tests and 11 subtests.
- `npm test -- --run src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/layouts/__tests__/AppShell.spec.ts` in `frontend` passed with 4 files and 15 tests.
- `npm run type-check` in `frontend` passed.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

### Remaining Work
- Implement a real `SkillEvalRunner` release gate with structured metrics, publish/disable/rollback/version workflows, and admin eval result UI.
- Add Trace-to-Golden candidate creation from Agent Trace detail.
- Add a distinct `validate_preconditions` graph node and expand the named TaskGraph golden suite to 30-50 cases.
- Replace trace-derived cache/token stats with persisted cache events and token-by-node/expert metrics when the runtime starts emitting them consistently.

## Session Addendum: 2026-07-01 (MoE Governance Reconciliation And Final Verification)
### Reconciliation Work
- Re-read the active planning files and the 2026-06-30 MoE governance completion plan after resuming session `019f1464-aecf-71b3-b652-64527f7e0306`.
- Confirmed the desktop environment still does not expose a callable subagent dispatch tool, so review remained local and evidence-based.
- Reconciled stale plan status with code facts:
  - Task 4 now has a structured skill eval approval gate for failed status, missing/invalid eval JSON, and low required tool/evidence/faithfulness metrics.
  - Task 6 now creates a `DRAFT` trace-to-golden candidate payload from Agent Trace detail.
  - Task 7 now exposes `validate_preconditions` in trace and golden-runner trace checks, but still lacks a distinct production graph node and the full 30-50 named TaskGraph golden suite.
- Updated `docs/superpowers/plans/2026-06-30-webnovel-moe-governance-completion.md` to reflect the current completion boundary.

### Verification Evidence
- `mvn "-Dtest=KnowledgeAgentGovernanceServiceTest,KnowledgeInternalControllerTest,KnowledgeAgentTraceServiceTest,KnowledgeSkillGovernanceServiceTest,SystemConfigServiceTest" test` in `backend` passed with 32 tests, 0 failures, 0 errors.
- `python -m pytest tests/test_expert_registry.py tests/test_knowledge_client.py tests/test_novel_research_agent.py tests/test_golden_eval_runner.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_edge.py -q` in `langgraph-worker` passed with 156 tests and 11 subtests.
- `npm test -- --run src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/layouts/__tests__/AppShell.spec.ts` in `frontend` passed with 4 files and 18 tests.
- `npm run type-check` in `frontend` passed.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

### Remaining Work
- Build the full async/configurable `SkillEvalRunner` lifecycle: suite execution, publish/disable/rollback/version workflows, and admin eval-result UI.
- Promote `validate_preconditions` from trace wrapper metadata to a distinct production LangGraph node if the architecture still requires a hard node boundary.
- Expand the named TaskGraph golden acceptance suite to 30-50 cases.
- Replace trace-derived cache/token stats with persisted cache events and token-by-node/expert metrics.

## Session Addendum: 2026-07-01 (MoE Governance Review Follow-up Release Lifecycle)
### Review Work
- Re-reviewed the user's older audit conclusion against current code instead of applying it blindly.
- Confirmed several older gaps were already closed in later changes: Runtime Policy and Expert Profile admin APIs/UI, internal worker config reads, Trace-to-Golden draft creation, structured eval metric display, and read-only cache/token stats skeleton.
- Confirmed a still-valid gap in Skill Governance: candidate review existed, but publish/disable/rollback/version lifecycle actions were missing from the admin API and UI.

### TDD Work
- Added backend red tests for publishing an approved candidate, rolling back a previous published version, rejecting publish when eval metrics fail, disabling, rollback restore, and rejecting publish when structured eval results are missing.
- Red evidence:
  - `mvn "-Dtest=KnowledgeSkillGovernanceServiceTest" test` failed at compile because `publish`, `disable`, and `rollback` did not exist.
  - `npm test -- --run src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts` failed because `[data-test="publish-skill"]` was missing.
  - `mvn "-Dtest=KnowledgeSkillGovernanceServiceTest#shouldRejectPublishWhenStructuredEvalResultIsMissing" test` failed because publish allowed candidates without structured eval results.

### Implementation Work
- Added `KnowledgeSkillGovernanceService.publish`, `disable`, and `rollback`.
- Added admin endpoints:
  - `POST /api/knowledge/admin/skill-candidates/{candidateId}/publish`
  - `POST /api/knowledge/admin/skill-candidates/{candidateId}/disable`
  - `POST /api/knowledge/admin/skill-candidates/{candidateId}/rollback`
- Added frontend API methods and Skill Governance table actions for publish/disable/rollback.
- Limited Approve/Reject buttons to `PENDING` candidates and kept release actions status-specific.
- Publish now requires a structured eval result, while review approval remains compatible with older candidates.

### Verification Evidence
- `mvn "-Dtest=KnowledgeSkillGovernanceServiceTest" test` passed with 9 tests.
- `npm test -- --run src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts` passed with 5 tests.
- `mvn "-Dtest=KnowledgeSkillGovernanceServiceTest,KnowledgeControllerTest,KnowledgeAgentGovernanceServiceTest,KnowledgeAgentTraceServiceTest,SystemConfigServiceTest" test` passed with 29 tests, 0 failures, 0 errors.
- `npm test -- --run src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/layouts/__tests__/AppShell.spec.ts` passed with 4 files and 19 tests.
- `python -m pytest tests/test_golden_eval_runner.py tests/test_agent_task_graph.py tests/test_novel_research_agent.py -k "preconditions or golden or task_graph" -q` passed with 14 tests, 114 deselected, and 7 subtests.
- `npm run type-check` passed.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

### Remaining Work
- Implement the full async/configurable SkillEvalRunner execution center and persisted eval run/case result workflow.
- Add persisted cache events and token-by-node/expert telemetry.
- Expand TaskGraph golden acceptance coverage to 30-50 named cases.

## Session Addendum: 2026-07-01 (MoE Governance Comprehensive Gap Closure)
### Review Work
- Re-reviewed the user's earlier audit conclusion against the current code instead of treating the older findings as current fact.
- Confirmed these older gaps were already closed before this slice: Runtime Policy and Expert Profile admin APIs/UI, Trace-to-Golden draft creation, structured skill eval metric display, and skill publish/disable/rollback lifecycle.
- Confirmed the still-valid gaps were: persisted cache/token telemetry, a distinct production `validate_preconditions` graph node, larger TaskGraph golden coverage, and a visible admin Eval Center for persisted worker eval results.

### TDD Work
- Added backend red coverage for persisted `ai_agent_cache_event` / `ai_agent_token_metric` aggregation and prompt prefix stability reporting; initial failure was missing `getPromptPrefixStableRate()`.
- Added worker red coverage requiring at least 30 named TaskGraph golden subcases; initial failure showed only 7 cases.
- Added worker red coverage requiring a real `validate_preconditions` LangGraph node and trace node metadata; initial failures showed the node was absent from graph edges and lacked `sequenceNo`.
- Added backend red coverage for admin eval run/case result querying; initial failure was missing `KnowledgeAgentEvalService` and VO classes.
- Added backend controller red coverage for `/api/knowledge/admin/agent/eval-runs` and `/api/knowledge/admin/agent/eval-runs/{runId}/cases`; initial failure was an unmapped endpoint.
- Added frontend red coverage requiring `AdminAgentGovernanceView` to load and render Eval Center runs; initial failure showed `listAgentEvalRuns` was not called.

### Implementation Work
- Added persisted agent telemetry tables to phase12 MySQL/H2 schema:
  - `ai_agent_cache_event`
  - `ai_agent_token_metric`
- Updated `KnowledgeAgentGovernanceService.cacheTokenStats()` to prefer persisted telemetry when present, while preserving trace JSON fallback when telemetry tables are absent or empty.
- Added `promptPrefixStableRate` to backend/frontend cache-token stats and rendered it in Admin Agent Governance.
- Added `KnowledgeAgentEvalService`, `AgentEvalRunVO`, and `AgentEvalCaseResultVO`, plus admin eval endpoints for persisted eval runs and case results.
- Added frontend API/types and an Eval Center table to `AdminAgentGovernanceView`.
- Promoted `validate_preconditions` into the production LangGraph graph between `plan_tasks` and `execute_tools`.
- Added trace node `sequenceNo` and `durationMs` metadata, and kept `validate_preconditions` visible in trace with structured preconditions.
- Expanded named TaskGraph governance coverage to 34 subcases across market, market+creation, book breakdown, outline/chapter outline, character, worldbuilding, revision, reader/editor risk, memory isolation, follow-up, and admin skill-governance refusal.

### Verification Evidence
- `mvn "-Dtest=KnowledgeAgentGovernanceServiceTest,KnowledgeAgentEvalServiceTest,KnowledgeSkillGovernanceServiceTest,KnowledgeControllerTest,KnowledgeAgentTraceServiceTest,SystemConfigServiceTest" test` passed with 32 tests, 0 failures, 0 errors.
- `python -m pytest tests/test_golden_eval_runner.py tests/test_agent_task_graph.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_edge.py tests/test_novel_research_agent.py -k "preconditions or golden or task_graph or explicit_runtime_state_nodes" -q` passed with 30 tests, 113 deselected, and 34 subtests.
- `npm test -- --run src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/layouts/__tests__/AppShell.spec.ts` passed with 4 files and 19 tests.
- `npm run type-check` passed.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

### Remaining Work
- Full automated eval execution orchestration from the backend/UI is still a productization step: persisted runs can now be viewed, and worker runner/repository exists, but this slice does not add a backend-triggered async worker eval job.

## Session Addendum: 2026-07-01 (MoE Governance Async Eval Center Completion)
### Review Work
- Re-reviewed the user's requirement that the MoE governance platform must be production-usable rather than a thin demo.
- Confirmed most earlier gaps were already closed in local code: Runtime Policy, Expert Profile, Skill lifecycle actions, persisted cache/token stats, trace-to-golden draft, distinct `validate_preconditions`, and 34 named TaskGraph golden subcases.
- Identified the remaining productization blocker in Eval Center: admins could read persisted eval runs/cases, but could not trigger a worker-backed suite execution from the UI/backend.

### TDD Work
- Added worker red coverage for `POST /internal/knowledge/eval-runs`; initial failure was missing `MySqlGoldenEvalRepository` / `GoldenEvalRunner` wiring in `app.api.knowledge`.
- Added backend red coverage for `AgentEvalRunRequest`, `LangGraphWorkerClient.startKnowledgeEvalRun`, `KnowledgeAgentEvalService.startRun`, and `POST /api/knowledge/admin/agent/eval-runs`; initial failure was missing DTO/client/service/controller methods.
- Added frontend red coverage for Eval Center Run Suite controls and case-result viewing; initial failures were missing `data-test="eval-suite-name"` and `data-test="view-eval-cases-1"` UI elements.

### Implementation Work
- Added worker async eval run orchestration: the internal API reads active golden cases, creates a persisted `RUNNING` `ai_eval_run`, returns `202` with run metadata, and schedules `GoldenEvalRunner` in FastAPI background tasks.
- Extended `GoldenEvalRunner.run_suite` to reuse a pre-created run id and added repository `fail_run` so background exceptions mark the run `FAILED`.
- Added backend `AgentEvalRunRequest`, worker-client method, service `startRun`, and admin POST endpoint.
- Added frontend Eval Center controls for suite/case limit/model, Run Suite submit, run-list refresh, and case-result inspection.
- Updated `docs/superpowers/plans/2026-06-30-webnovel-moe-governance-completion.md` and marked Phase 25 complete in `task_plan.md`.

### Verification Evidence
- Targeted worker/backend/frontend red tests were observed failing before implementation and passing after implementation.
- `python -m pytest tests/test_knowledge_api.py::KnowledgeApiTest::test_should_accept_admin_eval_run_and_schedule_suite_execution tests/test_golden_eval_repository.py tests/test_golden_eval_runner.py -q` passed with 11 tests.
- `python -m pytest tests/test_expert_registry.py tests/test_knowledge_client.py tests/test_knowledge_api.py tests/test_golden_eval_repository.py tests/test_golden_eval_runner.py tests/test_golden_eval_suite_market.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_suite_edge.py -q` passed with 50 tests.
- `python -m pytest tests/test_novel_research_agent.py -q` passed with 115 tests and 11 subtests.
- `mvn "-Dtest=KnowledgeAgentGovernanceServiceTest,KnowledgeAgentEvalServiceTest,KnowledgeInternalControllerTest,KnowledgeAgentTraceServiceTest,KnowledgeSkillGovernanceServiceTest,KnowledgeControllerTest,LangGraphWorkerClientTest,SystemConfigServiceTest" test` passed with 57 tests, 0 failures, 0 errors.
- `npm test -- --run src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/layouts/__tests__/AppShell.spec.ts` passed with 4 files and 21 tests.
- `npm run type-check` passed.
- `git diff --check` passed; only Windows LF-to-CRLF warnings were emitted.

## Session Addendum: 2026-07-02 (MoE Governance Plan Re-Audit)
### Review Work
- Re-read `task_plan.md`, `progress.md`, `findings.md`, and `docs/superpowers/plans/2026-06-30-webnovel-moe-governance-completion.md`.
- Ran session catchup; Codex native session parsing is not implemented, so catchup skipped without changing files.
- Re-audited the current plan against the 2026-06-28 MoE design and current backend/worker/frontend code using targeted `rg` searches and key file reads.
- Updated `docs/superpowers/plans/2026-06-30-webnovel-moe-governance-completion.md` to remove stale "not yet complete" statements and add a 2026-07-02 production hardening backlog.
- Appended the re-audit findings to `findings.md`.

### Current Re-Audit Result
- No broad admin surface is still completely absent: Runtime Policy, Expert Profiles, Skill Governance, Eval Center, Cache & Token stats, and Agent Trace all have usable admin surfaces.
- The remaining work is runtime hardening and true closed-loop governance:
  - published skill candidates are not loaded as live runtime skills;
  - `allowedTools` / `requiredEvidence` are not hard runtime constraints;
  - cache/token telemetry is not emitted into the dedicated tables;
  - most Runtime Policy fields are not enforced by the worker yet;
  - Eval Center model/evaluator selection, progress, cancellation, retries, and durable execution are incomplete.

### Verification Evidence
- This was a read-only code/plan audit plus documentation update. No backend/worker/frontend test suite was rerun in this checkpoint.
- `git diff --check -- task_plan.md findings.md progress.md docs\superpowers\plans\2026-06-30-webnovel-moe-governance-completion.md` passed; PowerShell/Git only warned that `progress.md` will use CRLF in the working tree.

## Session Addendum: 2026-07-02 (MoE Governance Runtime Hardening Completion)
### Review And TDD Work
- Continued Phase 28 against the 2026-07-02 hardening plan after re-reading `task_plan.md`, `progress.md`, `findings.md`, and the active plan.
- Rechecked the remaining valid gap from the handoff: Eval Center accepted `modelName`, but the worker eval request and `NovelResearchAgent` provider calls still used `settings.default_model`.
- Added red tests:
  - `GoldenEvalRunnerTest.test_run_suite_injects_selected_model_into_case_request_limits` initially failed because eval case requests had no `limits["modelName"]`.
  - `NovelResearchAgentTest.test_request_model_name_overrides_provider_model_for_answer_generation` initially failed because provider calls still used the default model.
  - `KnowledgeApiTest.test_should_reject_unsupported_admin_eval_evaluator` initially failed because unsupported `evaluatorName` values were still accepted and queued.

### Implementation Work
- Updated `GoldenEvalRunner.run_suite/run_case/_build_request` so a selected eval `model_name` is injected into each `KnowledgeChatRequest.limits.modelName` while preserving existing limits.
- Added `NovelResearchAgent._model_name()` to read `limits.modelName` or `limits.model` and fall back to `settings.default_model`.
- Routed request-scoped model selection through streaming generation, domain-intent fallback, legacy intent fallback, creative answer generation, main answer generation, answer tool loops, and specialist execution.
- Added worker-side evaluator selection/validation for Eval Center. The currently supported evaluator is `rule-based`; unsupported evaluator names now return HTTP 400 before repository/run creation.
- Reconciled the 2026-07-02 hardening plan and the 2026-06-30 completion backlog so the closed runtime hardening items are no longer listed as open.

### Verification Evidence
- `python -m pytest tests/test_golden_eval_runner.py::GoldenEvalRunnerTest::test_run_suite_injects_selected_model_into_case_request_limits tests/test_novel_research_agent.py::NovelResearchAgentTest::test_request_model_name_overrides_provider_model_for_answer_generation -q` passed with 2 tests.
- `python -m pytest tests/test_golden_eval_repository.py tests/test_golden_eval_runner.py tests/test_knowledge_api.py -q` passed with 20 tests.
- `python -m pytest tests/test_knowledge_client.py tests/test_skill_registry.py tests/test_task_tool_executor.py tests/test_evidence_arbiter.py tests/test_novel_research_agent.py::NovelResearchAgentTest::test_should_apply_backend_agent_governance_to_expert_routing tests/test_novel_research_agent.py::NovelResearchAgentTest::test_should_enforce_runtime_policy_published_skills_and_emit_telemetry tests/test_novel_research_agent.py::NovelResearchAgentTest::test_request_model_name_overrides_provider_model_for_answer_generation -q` passed with 36 tests.
- `mvn "-Dtest=KnowledgeSkillGovernanceServiceTest,KnowledgeAgentEvalServiceTest,LangGraphWorkerClientTest" "-DforkCount=0" test` passed with 24 tests.
- `mvn "-Dtest=KnowledgeAgentGovernanceServiceTest,KnowledgeInternalControllerTest" "-DforkCount=0" test` passed with 22 tests.
- `npx vitest run src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts` passed with 5 tests.
- `npm run type-check` passed.
- `git diff --check` passed; Git only emitted Windows LF-to-CRLF warnings.

## Session Addendum: 2026-07-03 (MoE Governance Production Readiness Re-Audit)
### Review Work
- Continued the user's requested full re-audit for whether MoE governance is production-ready or merely thin placement code.
- Re-read `task_plan.md`, `findings.md`, `progress.md`, and `docs/superpowers/plans/2026-07-02-moe-governance-runtime-hardening.md`.
- Inspected backend runtime-skill publish/read paths, telemetry ingestion, Eval Center queue/cancel/retry code, worker skill registry, task tool executor policy, EvidenceArbiter, eval runner/repository, and frontend Eval Center controls.
- Confirmed several paths are real implementation rather than placeholders: runtime skill upsert/internal API, telemetry persistence, allowed-tool blocking, queue-backed eval execution, frontend cancel/retry controls, and eval `modelName` propagation.
- Found overstatements/gaps that should not be called fully production-ready:
  - runtime skill DB read failures are silently hidden and dashboard can fall back to worker/local skill data;
  - local markdown skill `requiredEvidence` is parsed but not used by the hard enforcement path;
  - EvidenceArbiter requires rank evidence even for chapter/project/vector-only requirements;
  - worker eval cancellation uses DB state, not Redis keys, and no stale `RUNNING` recovery loop was found;
  - eval settings metadata stores a pending cancel key before run id assignment.

### Probe Evidence
- Ran a minimal worker Python probe in `langgraph-worker` with `PYTHONPATH` set to the worker root.
- Probe result: `NovelResearchAgent._required_evidence_for_state()` returned `[]` for selected local skill `webnovel-opening-hook` when no backend runtime skill payload was present.
- Probe result: `EvidenceArbiter.evaluate()` returned `missing` with `missing_rank_evidence` for `required_evidence=["chapter_evidence"]`, `["book_chapter"]`, and `["CHAPTER"]` even when the source list contained a `CHAPTER` source.

### Current Status
- This checkpoint is a read-only production-readiness audit plus documentation update. No backend, worker, or frontend production code was changed.
- Next engineering fixes should address the recorded gaps before the hardening plan is marked production-ready without qualification.

## Session Addendum: 2026-07-03 (MoE Governance Production Readiness Fixes)
### Spec And Plan
- Created `docs/superpowers/specs/2026-07-03-moe-governance-production-readiness-fixes.md`.
- Created `docs/superpowers/plans/2026-07-03-moe-governance-production-readiness-fixes.md`.
- Added Phase 30 to `task_plan.md`.

### TDD Evidence
- Backend red evidence: `mvn "-Dtest=KnowledgeSkillGovernanceServiceTest,KnowledgeAgentEvalServiceTest" "-DforkCount=0" test` failed at test compile because `KnowledgeAgentEvalService.recoverStaleRuns(Duration)` did not exist.
- Worker red evidence: `python -m pytest tests/test_evidence_arbiter.py tests/test_golden_eval_repository.py tests/test_novel_research_agent.py -k "required_evidence or create_run or runtime_policy" -q` failed with:
  - CHAPTER-only `chapter_evidence` returned `missing` due to `missing_rank_evidence`;
  - `create_run(total_cases=3)` did not persist `total_cases`;
  - selected local skill `webnovel-opening-hook` did not contribute `user_premise_or_project_memory` to required evidence.

### Implementation Work
- Changed backend runtime skill reads to fail visibly on DB/query/mapping errors instead of silently returning an empty list.
- Added backend eval stale-run recovery and real run-scoped `cancelKey`/`progressKey` persistence in eval run settings.
- Added `KnowledgeAgentEvalScheduler` so stale eval recovery runs automatically while the eval queue is enabled.
- Changed worker required-evidence collection to merge selected local/backend skills through `SkillRegistry.load_all()`.
- Changed `EvidenceArbiter` to allow non-rank required evidence contracts to pass without rank evidence when the explicit requirements are satisfied.
- Changed worker eval repository `create_run()` to insert the requested initial `total_cases`.

### Verification Evidence
- `mvn "-Dtest=KnowledgeSkillGovernanceServiceTest,KnowledgeAgentEvalServiceTest" "-DforkCount=0" test` passed with 17 tests, 0 failures, 0 errors.
- `mvn "-Dtest=KnowledgeSkillGovernanceServiceTest,KnowledgeAgentEvalServiceTest,KnowledgeAgentEvalSchedulerTest" "-DforkCount=0" test` passed with 19 tests, 0 failures, 0 errors.
- `python -m pytest tests/test_evidence_arbiter.py tests/test_golden_eval_repository.py tests/test_novel_research_agent.py -k "required_evidence or create_run or runtime_policy" -q` passed with 6 tests, 122 deselected.
- `python -m pytest tests/test_evidence_arbiter.py tests/test_skill_registry.py tests/test_golden_eval_repository.py tests/test_novel_research_agent.py -k "required_evidence or create_run or runtime_policy" -q` passed with 6 tests, 132 deselected.
- `git diff --check` passed; Git only emitted Windows LF-to-CRLF warnings.

## Session Addendum: 2026-07-03 (Full AI Q&A Agent Architecture Re-Audit)
### Review Work
- Added Phase 31 to `task_plan.md` for a full AI Q&A architecture/functionality re-audit.
- Re-read planning memory and inspected the current working tree without reverting any existing uncommitted changes.
- Reviewed backend chat/SSE, internal API security, memory/admin endpoints, worker LangGraph graph, streaming path, checkpointing, MCP tool loop/service, retrieval/fusion, eval runner/faithfulness metrics, and frontend chat/admin memory surfaces.
- Used Codex/Claude Code/opencode/Hermes-style production agent expectations as the comparison frame: shared execution kernel, durable/resumable runs, route-scoped tools, curated user-controlled memory, strong trace, and eval gates that verify retrieval/tool/grounding quality.

### Current Re-Audit Result
- Current AI Q&A is no longer just a skeleton: it has real TaskGraph/LangGraph, MCP-style tools, runtime policy, skill governance, eval center, trace, memory storage, and retrieval layers.
- The main architecture risks are now deeper: streaming bypasses the compiled LangGraph graph, trace can show synthetic completed nodes, checkpointing is a one-blob custom saver, memory is admin-governed rather than user-governed, and eval/faithfulness checks are still too shallow for a strict launch gate.
- No production code was changed in this checkpoint.

### Verification Evidence
- This was a read-only architecture/code review plus documentation update. Backend/worker/frontend test suites were not rerun for this checkpoint.

## Session Addendum: 2026-07-03 (AI Q&A Production-Agent Architecture Fixes)
### Spec And Plan
- Created `docs/superpowers/specs/2026-07-03-ai-qa-agent-production-architecture-fixes.md`.
- Created `docs/superpowers/plans/2026-07-03-ai-qa-agent-production-architecture-fixes.md`.
- Added and completed Phase 32 in `task_plan.md`.
- Confirmed product boundary: ordinary users do not need or receive a "my long-term memory/project memory" management feature in this slice.

### TDD Evidence
- Worker red evidence: stream finalization test failed because `memoryCandidatesPersisted` stayed `0`.
- Worker red evidence: truthful trace test failed because `_runtime_nodes_for_trace()` did not respect `executed_runtime_nodes`.
- Worker red evidence: tool-loop route propagation failed because fake MCP calls received `route=None`.
- Broader parity evidence exposed a real adjacent bug: mixed market-plus-creation streaming could continue to answer while blocking execution rejected on required evidence, and skill metadata selected unrelated hard requirements.

### Implementation Work
- Added executed runtime node marking to stream execution and refreshed trace status from the executed node set.
- Routed all stream final responses through `_finalize_trace_node()` so citation verification, memory candidate persistence, trace attachment, and telemetry emission run before the final `done` payload.
- Propagated business route from `ToolCallLoop` to `McpClient.call_tool()` and into the MCP `/mcp/call` JSON payload.
- Added MCP service test coverage for route-bearing calls.
- Added TaskGraph-based domain intent rescue when the task graph clearly identifies webnovel work but the domain router falls back to out-of-scope.
- Tightened `SkillRegistry.select_for_intent()` so broad task-level packs do not pollute top-level intent selection, and sorted task-level skills so direct `appliesTo` matches win over generic intent matches.
- Corrected local skill pack metadata and requiredEvidence values so EvidenceArbiter enforces machine-checkable constraints instead of blocking on user/project-memory prerequisites.

### Verification Evidence
- `python -m pytest tests/test_novel_research_agent.py::NovelResearchAgentTest::test_stream_should_finalize_memory_telemetry_and_truthful_trace tests/test_novel_research_agent.py::NovelResearchAgentTest::test_trace_nodes_should_not_claim_unexecuted_runtime_stages tests/test_tool_call_loop.py -q` passed with 9 tests.
- `python -m pytest tests/test_novel_research_agent.py::NovelResearchAgentTest::test_stream_done_should_match_blocking_metadata_for_mixed_rank_creative_request -q` passed with 1 test.
- `python -m pytest tests/test_tool_call_loop.py tests/test_skill_registry.py -q` passed with 17 tests.
- `python -m pytest tests/test_novel_research_agent.py -k "stream_should_finalize_memory_telemetry_and_truthful_trace or trace_nodes_should_not_claim_unexecuted_runtime_stages or stream_done_should_match_blocking_metadata or stream_should_replace_partial_stream or runtime_state_nodes or validate_preconditions_stage" tests/test_tool_call_loop.py tests/test_skill_registry.py -q` passed with 6 selected worker tests.
- `python -m pytest tests/test_mcp_tools.py tests/test_security.py -q` passed in `mcp-tools` with 9 tests.

## Session Addendum: 2026-07-04 (AI Q&A Agent Architecture Re-Audit After Phase 32)
### Review Work
- Continued Phase 33 after re-reading `task_plan.md`, `progress.md`, `findings.md`, and current `git status --short`.
- Reviewed the current backend chat/SSE path, worker LangGraph graph and stream path, checkpointing, memory/context assembly, eval runner/faithfulness gate, MCP route enforcement, retrieval/fusion, and frontend knowledge/admin-memory routing.
- Compared the implementation against mainstream production agent design expectations from Codex/Claude Code/opencode/Hermes-style systems: single execution kernel, durable resumability, modular tool/runtime layers, route-scoped tool enforcement, trace-visible degradation, and evals that check retrieval/tool/grounding quality.

### Current Re-Audit Result
- Phase 32 fixes are real for the targeted slice: stream final responses now go through finalization, trace node status respects `executed_runtime_nodes`, and MCP route is propagated to the MCP service payload.
- Remaining highest-priority gaps are deeper architecture issues: streaming still manually mirrors the compiled graph, checkpointing is still a pickled one-row saver, eval faithfulness/tool metrics are not strong enough for a launch gate, and memory/context failures need trace/admin diagnostics rather than normal-user memory UI.
- Confirmed the latest product boundary: ordinary users still do not have a "my long-term memory/project memory" route; memory governance is admin-only, while ordinary Q&A exposes project selection/project space.

### Verification Evidence
- This checkpoint was a read-only architecture/code review plus documentation update. Backend, worker, and frontend test suites were not rerun.
- One read-only `rg` command with a Windows-invalid wildcard path failed; the same target was inspected by directly reading `langgraph-worker/app/services/runtime/context_assembler.py`.

## Session Addendum: 2026-07-04 (AI Q&A Industrial Hardening Spec And Execution Start)
### Spec And Plan
- Added Phase 34 to `task_plan.md`.
- Created `docs/superpowers/specs/2026-07-04-ai-qa-agent-industrial-hardening-design.md`.
- Created `docs/superpowers/plans/2026-07-04-ai-qa-agent-industrial-hardening.md`.
- Scope is intentionally industrial rather than thin: graph-driven streaming, row-level checkpoints, stronger eval gates, memory/context diagnostics, MCP service route allowlist, and retrieval/Admin Trace diagnostics.
- Product boundary preserved: no ordinary-user "my long-term memory/project memory" page; memory visibility remains runtime/admin trace governance.

### Execution Notes
- Current desktop session has no subagent dispatch tool exposed, and the active workspace already carries the ongoing uncommitted AI Q&A continuation state. Implementation proceeds inline in the current workspace with TDD and without reverting unrelated changes.
- A PowerShell command using bash here-doc syntax failed while inspecting LangGraph saver signatures; reran with a PowerShell here-string and used the printed signatures.

## Session Addendum: 2026-07-04 (AI Q&A Industrial Hardening Completion)
### Implementation Work
- Completed Phase 34 against the industrial hardening spec and implementation plan.
- Landed graph-driven worker streaming through compiled graph event updates and trace node refresh from actual executed graph nodes.
- Reworked durable checkpoint persistence to row-level namespace/thread/checkpoint namespace storage.
- Strengthened local golden eval gates with grounded-claim support plus required tool/source contract failures and metrics.
- Surfaced memory/context degradation diagnostics in worker result JSON and Agent Trace without adding a normal-user memory UI.
- Added MCP server-side normal-route allowlist enforcement.
- Added retrieval diagnostics from worker fusion and backend retrieval fallback into Agent Trace/Admin Trace.
- Marked Phase 34 complete in `task_plan.md` and checked off the 2026-07-04 hardening plan.

### Verification Evidence
- Worker: `python -m pytest tests/test_novel_research_agent.py tests/test_checkpointing.py tests/test_memory_agent.py tests/test_memory_extractor.py tests/test_golden_eval_runner.py tests/test_retrieval_fusion.py tests/test_evidence_arbiter.py -q --durations=20` passed with 157 tests and 11 subtests in 242.49s.
- MCP: `python -m pytest tests/test_mcp_tools.py tests/test_security.py -q` passed with 10 tests.
- Backend: `mvn "-Dtest=KnowledgeRetrievalServiceTest,KnowledgeAgentTraceServiceTest,KnowledgeChatServiceTest,KnowledgeControllerTest" "-DforkCount=0" test` passed with 34 tests, 0 failures, 0 errors. RabbitMQ localhost connection-refused and Tomcat Native version warnings were local test-environment noise and did not fail the build.
- Frontend: `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/layouts/__tests__/AppShell.spec.ts` passed with 2 files and 12 tests.
- Frontend type check: `npm run type-check` passed.
- Diff hygiene: `git diff --check` passed; Git only emitted Windows LF-to-CRLF warnings.

## Session Addendum: 2026-07-04 (Admin LangGraph Runtime View)
### Implementation Work
- Added Phase 35 to `task_plan.md`.
- Added `frontend/src/components/knowledge/trace/LangGraphRuntimeGraph.vue` to render runtime graph nodes from `resultJson.trace`.
- Integrated the component into `AdminAgentTraceView.vue` as the `LangGraph Runtime` collapse section before the existing business TaskGraph section.
- Added frontend runtime trace types in `frontend/src/types/knowledge.ts`.
- Preserved the admin-only boundary by reusing the existing `knowledge/admin/traces` route with `roles: ['ADMIN']`; no ordinary-user route/sidebar/page was added.

### TDD Evidence
- Red test: `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts` failed because `LangGraph Runtime` was absent.
- Green test: the same command passed after adding the component and Admin Trace integration.

### Verification Evidence
- `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/layouts/__tests__/AppShell.spec.ts` passed with 2 files and 12 tests.
- `npm run type-check` passed.
- `git diff --check` passed; Git only emitted Windows LF-to-CRLF warnings.

## Session Addendum: 2026-07-04 (Admin LangGraph Runtime View Enhancement)
### Implementation Work
- Added Phase 36 to `task_plan.md`.
- Added component-level coverage in `frontend/src/components/knowledge/trace/__tests__/LangGraphRuntimeGraph.spec.ts`.
- Enhanced `LangGraphRuntimeGraph.vue` with ordered runtime path edges, status filter buttons, slowest-node summary, failed/skipped summary, click-to-select node cards, and a node detail panel showing sequence, duration, executed flag, error text, and raw node JSON.
- Kept the feature admin-only by preserving the existing `AdminAgentTraceView.vue` integration under the `knowledge/admin/traces` route.

### TDD Evidence
- Red test: `npm test -- --run src/components/knowledge/trace/__tests__/LangGraphRuntimeGraph.spec.ts` failed because `Runtime Path` and status-filter/detail controls were absent.
- Green test: the same command passed after the Graph View enhancement.

### Verification Evidence
- `npm test -- --run src/components/knowledge/trace/__tests__/LangGraphRuntimeGraph.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/layouts/__tests__/AppShell.spec.ts` passed with 3 files and 13 tests.
- `npm run type-check` passed.

## Session Addendum: 2026-07-04 (AI Q&A Degraded Answer And Trace Audit)
### Review Work
- Read the attached user report for the degraded mixed-creation prompt and trace IDs `b419b9c31fc24f79b73c2a06ad53c11a` / `491b09e1-f681-4d7d-8b0d-aa3b80fa8160`.
- Re-read `task_plan.md`, `findings.md`, and `progress.md`, then inspected current worker/backend/frontend paths for SSE, answer generation, fallback, tool budgeting, ExpertRouter, memory diagnostics, skills/governance, telemetry, and Admin Trace UI.
- Confirmed the bad answer structure matches `NovelResearchAgent._compose_mixed_creation_fallback_answer()` rather than the expected deep creative answer path.
- Confirmed current compiled graph stream sends the final answer as one `delta` after graph completion, so it preserves graph parity but does not provide token-level final-answer streaming.
- Confirmed runtime node durations are defaulted to `0`, and TaskGraph tool budget failures can coexist with later underscore-named legacy retrieval successes in the same trace.
- Confirmed current targeted tests pass while not checking this product-quality failure.

### Verification Evidence
- `python -m pytest tests/test_golden_eval_suite_mixed_creation.py::GoldenEvalMixedCreationTest::test_production_trace_206_lookup_only_snapshotless_rows_answer_degraded tests/test_novel_research_agent.py::NovelResearchAgentTest::test_stream_should_emit_single_graph_delta_when_provider_stream_is_empty -q` passed with 2 tests.
- A local Python probe calling `_compose_mixed_creation_fallback_answer()` reproduced the same section structure as the user-visible degraded answer.

### Documentation
- Added `docs/superpowers/findings/2026-07-04-ai-qa-degraded-answer-trace-audit.md`.
- Added and completed Phase 37 in `task_plan.md`.

## Session Addendum: 2026-07-04 (AI Q&A Degraded Answer Production Fix Start)
### Spec And Plan
- Added Phase 38 to `task_plan.md`.
- Created `docs/superpowers/specs/2026-07-04-ai-qa-degraded-answer-trace-production-fix-design.md`.
- Created `docs/superpowers/plans/2026-07-04-ai-qa-degraded-answer-trace-production-fix.md`.
- Scope is production-grade rather than thin: exact-prompt answer quality, graph-compatible answer streaming, fail-loud fallback, provider/model trace, true runtime timing, canonical tool accounting, expert/skill/governance/memory observability, Admin Trace health UX, and eval gate enforcement.
- Product boundary preserved: no ordinary-user long-term/project memory management UI.

### TDD Red Evidence
- `python -m pytest tests/test_novel_research_agent.py::NovelResearchAgentTest::test_exact_mixed_creation_prompt_should_repair_shallow_model_answer_with_quality_gate tests/test_novel_research_agent.py::NovelResearchAgentTest::test_mixed_creation_provider_failure_should_be_degraded_and_trace_visible tests/test_novel_research_agent.py::NovelResearchAgentTest::test_stream_should_emit_provider_answer_deltas_from_compiled_graph tests/test_novel_research_agent.py::NovelResearchAgentTest::test_runtime_nodes_should_not_default_unknown_duration_to_zero -q` failed with 4 expected failures.
- Failures confirm current behavior: mixed-creation shallow answer/fallback is not repaired, provider failure lacks `degraded` diagnostics, compiled graph streaming emits one final delta, and unknown node duration is written as `0`.

### Worker Implementation Checkpoint
- Added provider-call diagnostics, answer degradation state, mixed-creation quality gate with one repair pass, graph-state answer deltas for compiled graph streaming, runtime node timing capture for graph stream, and canonical tool run annotations.
- Adjusted citation repair so quality-passed mixed-creation answers are repaired in place instead of being replaced by the generic fallback template.
- Red/green verification: the same four worker tests now pass with `4 passed in 12.58s`.

### Frontend TDD Checkpoint
- Added Admin Trace expectations for row health blocks, Trace Health detail summary, and row-click focus detail mode.
- Added chat expectation that degraded/fallback answers show a visible "降级回答" notice and the degradation reason.
- Implemented degraded/fallback message metadata propagation, chat bubble degraded badge, Admin Trace health block parsing from `resultJson.trace.health`, and focus-detail mode with a back-to-list control.
- Verification: `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/views/knowledge/__tests__/KnowledgeChatView.spec.ts` passed with 31 tests.

## Session Addendum: 2026-07-04 (AI Q&A Degraded Answer Production Fix Completion)
### Additional Implementation Work
- Fixed the backend paged Agent Trace summary API to include raw `resultJson`, so Admin Trace list health blocks work against the real paginated endpoint.
- Added backend regression `shouldExposeResultJsonOnPagedTraceSummaryForHealthBlocks`.
- Extended `GoldenEvalExpectedTrace` and `GoldenEvalRunner` with production answer-quality gates: forbid fallback, require provider success, require selected experts, require answer terms, and forbid old fallback patterns.
- Added the reported mixed-creation prompt to `langgraph-worker/tests/golden_cases/mixed_creation_cases.json` and verified it through `test_reported_bottom_occupation_outsourcing_prompt_passes_quality_gate`.
- Fixed stream final-answer parity by resyncing `answerDeltas` after citation/finalization changes the answer.
- Tightened mixed-creation quality checks so short/placeholder/stale streamed answers trigger repair/degraded handling instead of being accepted after a rank evidence prefix.
- Updated outdated worker tests from the old single-delta / no-provider-stream contract to the new graph-compatible streaming contract: concatenated deltas must equal `done.answer`.

### TDD And Debug Evidence
- Backend red: `mvn "-Dtest=KnowledgeAgentTraceServiceTest#shouldExposeResultJsonOnPagedTraceSummaryForHealthBlocks" "-DforkCount=0" test` failed because paged summary `resultJson` was null.
- Backend green: same command passed after selecting `result_json` and setting it in `summaryMapper()`.
- Eval red: `python -m pytest tests/test_golden_eval_runner.py::GoldenEvalRunnerTest::test_fails_when_answer_quality_trace_contract_is_violated -q` failed because `GoldenEvalExpectedTrace` lacked production quality fields.
- Eval green: same command passed after adding the fields and runner checks.
- Worker matrix first exposed 10 failures; most were stale test expectations, but one was a real stream parity bug where stale provider text stayed in the final answer. Fixed by final delta resync plus stronger mixed-creation quality gating.

### Verification Evidence
- Worker: `python -m pytest tests/test_novel_research_agent.py tests/test_golden_eval_suite_mixed_creation.py tests/test_golden_eval_runner.py tests/test_task_tool_executor.py -q` passed with 145 tests and 11 subtests in 310.33s.
- Backend: `mvn "-Dtest=KnowledgeChatServiceTest,KnowledgeAgentTraceServiceTest,KnowledgeControllerTest,LangGraphWorkerClientTest" "-DforkCount=0" test` passed with 37 tests, 0 failures, 0 errors. RabbitMQ localhost connection-refused and Tomcat Native 1.2.33 warnings were local environment noise.
- Frontend: `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/components/knowledge/trace/__tests__/LangGraphRuntimeGraph.spec.ts` passed with 3 files and 32 tests.
- Frontend type check: `npm run type-check` passed.
- Diff hygiene: `git diff --check` passed; Git only emitted Windows LF-to-CRLF warnings.
- Phase 38 marked complete in `task_plan.md`.

## Session Addendum: 2026-07-04 (AI Q&A Context Trace Governance Skill Production Fix Start)
### Review Work
- Read the user's follow-up report about missing context memory, empty memory diagnostics, Trace UX, English/admin labels, Agent Governance clarity, LangGraph graph expectations, and skill upload/extension.
- Re-read `task_plan.md`, `findings.md`, `progress.md`, the attached pasted text, and current backend/worker/frontend code.
- Confirmed two concrete memory wiring defects:
  - backend chat writes `knowledge_chat_memory`, while worker memory reads `ai_conversation_summary`;
  - backend's empty project profile can prevent worker from fetching real project memory.
- Confirmed the current UI still uses many English admin labels and a Trace split-pane pattern that does not meet the requested list-first/detail workflow.
- Created `docs/superpowers/specs/2026-07-04-ai-qa-context-trace-governance-skill-production-fix-design.md`.
- Created `docs/superpowers/plans/2026-07-04-ai-qa-context-trace-governance-skill-production-fix.md`.
- Added Phase 39 to `task_plan.md`.

## Session Addendum: 2026-07-04 (AI Q&A Context Trace Governance Skill Production Fix Completion)
### Implementation Work
- Completed Phase 39 against the 2026-07-04 context/trace/governance/skill production-fix spec.
- Backend/worker work from the continuation is preserved: successful chat/stream turns update `ai_conversation_summary`, admin can create skill candidates through `POST /api/knowledge/admin/skill-candidates`, shell project profiles no longer block worker project-memory fetches, and worker responses attach `contextBudget` to result JSON and trace diagnostics.
- Frontend chat now persists and displays `contextBudget`/`traceId` as a compact Chinese status strip showing used tokens, remaining ratio, compression state, memory layer count, and Trace id.
- Admin Trace is now list-first: the paged list no longer auto-fetches the first detail row, detail opens after row click, health blocks and section titles are Chinese, and the LangGraph runtime graph labels old rows without `executedRuntimeNodes` as compatibility inference.
- Admin Agent Governance now uses Chinese labels and includes an operator-facing runtime topology: context -> intent -> task graph -> tools/evidence -> experts -> answer -> memory/trace.
- Admin Skill Governance now includes a manual skill upload form that creates a `PENDING` candidate, preserves the existing review/publish/disable/rollback flow, and renders eval metrics in Chinese.
- Admin Memory remains admin-only, but its title, filters, tables, actions, and detail drawer are now Chinese so memory diagnostics no longer read like an unfinished English console.

### TDD And Verification Evidence
- Red test evidence: the new frontend tests failed before implementation for missing context strip, automatic Trace detail loading, English LangGraph labels, missing compatibility inference, English Governance labels, and missing skill upload fields/API call.
- Green frontend evidence: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/components/knowledge/trace/__tests__/LangGraphRuntimeGraph.spec.ts src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/views/knowledge/__tests__/AdminMemoryView.spec.ts` passed with 6 files and 46 tests.
- Frontend type evidence: `npm run type-check` passed.
- Backend evidence: `mvn "-Dtest=KnowledgeChatServiceTest,KnowledgeSkillGovernanceServiceTest,KnowledgeControllerTest,KnowledgeInternalControllerTest" "-DforkCount=0" test` passed with 53 tests, 0 failures, 0 errors. Local RabbitMQ connection-refused and Tomcat Native version warnings did not fail the build.
- Worker evidence: `python -m pytest tests/test_context_assembler.py tests/test_memory_agent.py tests/test_novel_research_agent.py tests/test_skill_registry.py -q` passed with 147 tests and 11 subtests.
- Diff hygiene evidence: `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.
- Packaging evidence: created `D:\Git\agent\noval\noval-release-ai-qa-agent-20260704_235545.tar.gz` (2,027,511 bytes). Archive verification found 1,106 entries, first entry `noval/`, included frontend/backend/worker/mcp-tools key files plus Admin Memory, and excluded `.git`, `node_modules`, `target`, and old `noval-release-*.tar.gz` archives.

## Session Addendum: 2026-07-05 (AI Q&A Production Release Packaging)
### Release Hardening
- Added and completed Phase 40 in `task_plan.md` for final release verification and packaging.
- Re-audited deployment schema behavior and found a real production-update risk: `docker-entrypoint-initdb.d` only runs on empty MySQL volumes, and existing `phase11` / `phase12` scripts use `CREATE TABLE IF NOT EXISTS`, which does not upgrade old tables with new Eval Center progress/cancel/retry fields or Skill Governance eval metric fields.
- Added `backend/sql/mysql/phase14-ai-agent-production-upgrade.sql` as an idempotent existing-database upgrade script. It adds missing `ai_eval_run` progress/cancel/retry/recovery columns and indexes, missing `ai_skill_candidate` structured eval metric columns, and compatibility columns/indexes for `ai_runtime_skill` when upgrading older deployments.

### Fresh Verification Evidence
- Frontend targeted tests: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/components/knowledge/trace/__tests__/LangGraphRuntimeGraph.spec.ts src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/views/knowledge/__tests__/AdminMemoryView.spec.ts` passed with 6 files and 46 tests.
- Frontend type check: `npm run type-check` passed.
- Backend targeted tests: `mvn "-Dtest=KnowledgeChatServiceTest,KnowledgeSkillGovernanceServiceTest,KnowledgeControllerTest,KnowledgeInternalControllerTest" "-DforkCount=0" test` passed with 53 tests, 0 failures, 0 errors after adding the phase14 SQL script.
- Worker targeted tests: `python -m pytest tests/test_context_assembler.py tests/test_memory_agent.py tests/test_novel_research_agent.py tests/test_skill_registry.py -q` passed with 147 tests and 11 subtests.
- Diff hygiene: `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.
- Expected final archive: `D:\Git\agent\noval\noval-release-ai-qa-agent-20260705_001037.tar.gz`.

## Session Addendum: 2026-07-05 (AI Q&A Follow-up Memory Context Production Fix Completion)
### Implementation Work
- Completed Phase 41 against `docs/superpowers/specs/2026-07-05-ai-qa-followup-memory-context-production-fix-design.md` and `docs/superpowers/plans/2026-07-05-ai-qa-followup-memory-context-production-fix.md`.
- Backend runtime policy now defaults `maxTotalInputTokens` to `1000000`, allows up to `1200000`, and forwards the runtime budget to worker payload `limits.maxInputTokens`.
- Worker now treats short creative follow-ups such as `给出完整的大纲设计` as context-backed continuation when the thread context contains a web-novel premise, instead of falsely entering book-candidate selection.
- Worker progress labels are Chinese, and provider-call diagnostics are preserved in result JSON/trace health so Admin Trace no longer reports `model=not_called` after a model-backed creative response.
- Frontend normalizes legacy English stream progress labels to Chinese, while keeping memory management admin-only and not exposing a normal-user long-term/project memory page.

### Fresh Verification Evidence
- Backend: `mvn "-Dtest=KnowledgeChatServiceTest,KnowledgeAgentGovernanceServiceTest,KnowledgeControllerTest,KnowledgeInternalControllerTest" "-DforkCount=0" test` passed with 47 tests, 0 failures, 0 errors.
- Worker: `python -m pytest tests/test_context_assembler.py tests/test_memory_agent.py tests/test_novel_research_agent.py tests/test_skill_registry.py -q` passed with 148 tests and 11 subtests.
- Frontend batch 1: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts` passed with 2 files and 32 tests.
- Frontend batch 2: `npm test -- --run src/components/knowledge/trace/__tests__/LangGraphRuntimeGraph.spec.ts src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts src/views/knowledge/__tests__/AdminMemoryView.spec.ts` passed with 4 files and 14 tests.
- Frontend type check: `npm run type-check` passed.
- Diff hygiene before packaging: `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.
- Local backend logs still show RabbitMQ localhost connection-refused and Tomcat Native 1.2.33 warnings in tests. They are environment warnings and did not fail the verification matrix.

### Packaging Evidence
- First packaging attempt failed because bash-style `\` line continuations were used in PowerShell. Re-ran with a PowerShell argument array.
- Second packaging attempt timed out and left a 0-byte temp archive in the parent directory with a still-running `tar` process. Stopped that process and removed the temp file before rebuilding.
- Final archive created: `D:\Git\agent\noval\noval-release-ai-qa-agent-20260705_025714.tar.gz` (`1,314,566` bytes).
- Archive verification: `tar -tzf` found 1,066 entries, first entry `noval/`; required backend, frontend, worker, mcp-tools, docker-compose, `phase13-agent-memory-mcp.sql`, and `phase14-ai-agent-production-upgrade.sql` files are included.
- Archive exclusion verification: `.git`, `node_modules`, `target`, `dist`, `.codex-research`, and old `noval-release-ai-qa-agent-*.tar.gz` files are not included.

## Session Addendum: 2026-07-05 (AI Q&A Runtime Memory Graph Skill Deep Cache Production Fix Start)
### Review Work
- Read the new production report, screenshots, and trace text for `80c954776fbb4d4bbf92178bde2b8ebf`.
- Re-read `task_plan.md`, `findings.md`, and `progress.md` before changing code.
- Confirmed Phase 41 did not fully close the production UX/runtime gaps: capacity display lacks denominator/explanation, memory diagnostics are not visible enough, Governance lacks a real runtime graph, Skill upload lacks `.md` import, and DeepSeek cache usage fields are not parsed.
- Added Phase 42 to `task_plan.md`.
- Created `docs/superpowers/specs/2026-07-05-ai-qa-runtime-memory-graph-skill-deep-cache-production-fix-design.md`.
- Created `docs/superpowers/plans/2026-07-05-ai-qa-runtime-memory-graph-skill-deep-cache-production-fix.md`.

## Session Addendum: 2026-07-05 (AI Q&A Runtime Memory Graph Skill Deep Cache Production Fix Completion)
### Implementation Work
- Completed Phase 42 against the 2026-07-05 runtime/memory/graph/skill/deep/cache production spec.
- Worker provider diagnostics now propagate the provider result on the main `compose_answer` path, so `providerCalls` include requested reasoning mode, requested model, actual model, thinking-enabled state, usage summary, and DeepSeek cache hit/miss tokens.
- Provider streaming now parses official DeepSeek cache usage from stream `usage` payloads and worker graph streaming carries those fields into result JSON/Trace provider calls.
- Normal chat context capacity now displays total context budget, used tokens, used ratio, remaining ratio, remaining tokens, compression threshold, compression state, memory layer count, memory-layer summary, and Trace id.
- Admin Agent Governance now renders the latest real LangGraph runtime graph from Agent Trace, keeps the static topology as context, and explains Runtime Policy effects in Chinese.
- Admin Skill Governance supports `.md` / `.markdown` import into the same governed pending-candidate form.

### Fresh Verification Evidence
- TDD red evidence: the mixed-creation provider-call test failed because the first `compose_answer` call did not include cache tokens; the provider stream test failed because stream `done` omitted cache usage.
- Worker: `python -m pytest tests/test_provider_client.py tests/test_novel_research_agent.py -q` passed with 137 tests and 11 subtests.
- Frontend: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts src/views/knowledge/__tests__/AdminAgentGovernanceView.spec.ts src/views/knowledge/__tests__/AdminSkillGovernanceView.spec.ts` passed with 3 files and 41 tests.
- Frontend type check: `npm run type-check` passed.
- Diff hygiene: `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.
- Backend targeted Maven command ran the selected backend classes with 45 tests, 0 failures, 0 errors in Surefire reports, but Maven returned failure after the fork JVM hit local native memory allocation errors (`Native memory allocation failed`, CDS commit failure). No backend code changed in Phase 42.

### Packaging Evidence
- Initial packaging attempt timed out and produced an oversized 485 MB archive because broad directory packaging included local caches, worktrees, backups, Redis dump, and old release artifacts. Removed that generated bad archive.
- Rebuilt the release with an explicit whitelist of production roots and explicit cache/build exclusions.
- Final archive created: `D:\Git\agent\noval\noval-release-ai-qa-agent-20260705_154403.tar.gz` (`2,028,723` bytes).
- Archive verification found 1,062 entries and confirmed required files: `docker-compose.yml`, phase13/phase14 SQL, backend AI Q&A services, frontend Knowledge/Governance/Skill views, worker `novel_research_agent.py` / `provider_client.py`, `mcp-tools`, `crawler`, and nginx Dockerfile.
- Archive exclusion verification found no `.git`, `node_modules`, `target`, `dist`, `.pytest_cache`, `__pycache__`, `.codex-research`, or nested `noval-release-ai-qa-agent-*` artifacts.

## Session Addendum: 2026-07-05 (AI Q&A Top30 Deep Memory Session Production Fix Start)
### Review Work
- Read the new production report and attached trace for `14bfcd481b3a4c51866fa56794fcaf80`.
- Re-read `task_plan.md`, `findings.md`, and `progress.md` before changing code.
- Added Phase 43 to `task_plan.md`.
- Created `docs/superpowers/specs/2026-07-05-ai-qa-top30-deep-memory-session-production-fix-design.md`.
- Created `docs/superpowers/plans/2026-07-05-ai-qa-top30-deep-memory-session-production-fix.md`.
- Confirmed the initial root causes to test:
  - Worker overwrites frontend/backend `rankLimit=30` with parsed lookup `limit=10`.
  - Worker parser does not recognize `30名榜单`.
  - Backend currently caps `rankLimit` at 20.
  - Worker deep mode does not select a deep/pro model unless explicitly overridden.
  - Memory health collapses partial conversation-memory success into `unavailable`.
  - Frontend needs an explicit `新建会话` action rather than only `清空会话`.

## Session Addendum: 2026-07-05 (AI Q&A Top30 Deep Memory Session Production Fix Completion)
### Implementation Work
- Completed Phase 43 against `docs/superpowers/specs/2026-07-05-ai-qa-top30-deep-memory-session-production-fix-design.md` and `docs/superpowers/plans/2026-07-05-ai-qa-top30-deep-memory-session-production-fix.md`.
- Worker now preserves explicit/requested `rankLimit`, parses `整体30名榜单` / `完整30名` / `30名榜单` / `前三十`, preserves requested rank rows beyond the old Top10 fusion cutoff, and treats `rankLimit=30` as 30 rank rows plus supplemental evidence space.
- Worker deep mode now selects configurable `AI_OPENAI_COMPATIBLE_DEEP_MODEL` with default `deepseek-v4-pro`, while request-level model overrides still win.
- Worker memory health now reports `partial` when conversation/thread memory is loaded but project memory failed, instead of collapsing the whole trace to `unavailable`.
- Backend `KnowledgeChatService` now forwards `rankLimit` up to 50 instead of capping Top30 requests at 20.
- Frontend now exposes an explicit `新建会话` action that resets the volatile chat session without losing selected project, reasoning mode, or chapter count.

### Fresh Verification Evidence
- Worker targeted red/green: the Phase 43 four-test set first failed with 4 expected failures, then passed with 4 tests after implementation.
- Worker broader matrix: `python -m pytest tests/test_provider_client.py tests/test_retrieval_fusion.py tests/test_novel_research_agent.py tests/test_golden_eval_suite_mixed_creation.py -q` passed with 154 tests and 11 subtests.
- Frontend chat matrix: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts` passed with 30 tests.
- Frontend type check: `npm run type-check` passed.
- Backend compile gate: `mvn -DskipTests test-compile` passed.
- Backend targeted assertion attempt: `KnowledgeChatServiceTest#shouldForwardTopThirtyRankLimitToWorkerPayload` could not complete locally because the Spring/Surefire JVM crashed with native memory/page-file allocation failures before executing assertions, even after reducing Maven/JVM memory.
- Diff hygiene: `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.

### Packaging Evidence
- Final archive created: `D:\Git\agent\noval\noval-release-ai-qa-agent-20260705_165914.tar.gz` (`2,115,342` bytes).
- Archive verification found 1,084 entries, first entry `noval/backend/`; required backend, frontend, worker, crawler, mcp-tools, docker-compose, phase13, and phase14 files are included.
- Archive exclusion verification found no `.git`, `node_modules`, `target`, `dist`, `.pytest_cache`, `__pycache__`, `.codex-research`, or nested `noval-release-ai-qa-agent-*` artifacts.
## Session Addendum: 2026-07-05 (AI Q&A Durable Chat Runtime Production Fix Start)
### Review Work
- Read the new production trace attachment for `1d3c84186e184420bc6b93d877fb27c4` and compared it against the current Phase 43 implementation.
- Re-read `task_plan.md`, `findings.md`, and `progress.md` before changing implementation code.
- Confirmed remaining production gaps:
  - frontend conversation id is still response-created instead of pre-run durable;
  - AI Q&A does not have a server-side resumable chat-run model;
  - worker graph still has no real `route_experts` node despite selected experts;
  - flat tool budget can starve required memory/skill tools;
  - final answer output token caps are hard local limits;
  - Eval Center has expected cases in UI but no active `agent-runtime` cases in DB.
- Created `docs/superpowers/specs/2026-07-05-ai-qa-durable-chat-memory-runtime-production-design.md`.
- Created `docs/superpowers/plans/2026-07-05-ai-qa-durable-chat-memory-runtime-production.md`.
- Added Phase 44 to `task_plan.md`.

## Session Addendum: 2026-07-06 (AI Q&A Durable Chat Runtime Production Fix Completion)
### Implementation Work
- Added backend durable chat-run persistence with `ai_chat_run`, `KnowledgeChatRunService`, `KnowledgeChatRunVO`, and user-owned APIs for start/detail/conversation-list/cancel.
- Added `backend/sql/mysql/phase15-ai-chat-run-production.sql`, including the chat-run table and idempotent ACTIVE `agent-runtime` eval cases so Eval Center no longer fails with an empty runnable suite after upgrade.
- Added H2 test schema coverage for `ai_chat_run`.
- Frontend now has chat-run API/types, persists `pendingRunId`, restores answered background runs after remount, and routes deep-mode long answers through durable background execution while keeping fast-mode SSE.
- Worker Phase 44 fixes were verified and two matrix regressions were corrected: strong opening-strategy prompts no longer get stolen by market scan, and satisfied rank preconditions now expose `trendGateReason=satisfied`.

### Verification Evidence
- Backend red/green: `mvn "-Dtest=KnowledgeChatRunServiceTest" "-DforkCount=0" test` first failed for missing service/VO, then passed with 3 tests, 0 failures, 0 errors after implementation.
- Backend compile gate: `mvn -DskipTests test-compile` passed.
- Backend broader controller attempt: `KnowledgeChatRunServiceTest,KnowledgeControllerTest` passed the new service tests, then local Surefire fork crashed during Spring/Hikari startup with the same native-memory class of failure previously recorded; no assertion failure was reported before the fork crash.
- Frontend chat tests: `npm test -- --run src/views/knowledge/__tests__/KnowledgeChatView.spec.ts` passed with 31 tests.
- Frontend type check: `npm run type-check` passed.
- Worker targeted matrix: `python -m pytest tests/test_domain_tools.py tests/test_task_tool_executor.py tests/test_intent_router.py tests/test_novel_research_agent.py -q` passed with 167 tests and 25 subtests after fixing the two discovered regressions.
- Diff hygiene: `git diff --check` passed; Git emitted only Windows LF-to-CRLF warnings.
- Packaging: created `D:\Git\agent\noval-release-ai-qa-agent-20260706_002505.tar.gz` (`1,866,601` bytes).
- Archive verification: `tar -tzf` found 994 entries, first entry `noval/backend/`; required phase15 SQL, chat-run backend service/VO, frontend chat-run API/composable/types, worker intent/router files, and docker-compose are included.
- Archive exclusion verification found no `.git`, `node_modules`, `target`, `dist`, `.pytest_cache`, `__pycache__`, `.codex-research`, or nested `noval-release-ai-qa-agent-*` archives.

## Session Update: 2026-07-07 Project Knowledge Structured Lookup
- Red backend test for structured project knowledge lookup failed because H2 CLOB fields were returned as JdbcClob inside Map responses and Jackson could not serialize them.
- Added a small ResultSet column conversion helper in KnowledgeProjectWorkService so structured lookup text fields serialize as strings.

- Second backend red run reached JSON assertions and exposed that timeline lookup omitted title from the structured response contract; added the missing title mapping.

- Added worker red test for structured project tools: project.foreshadowing.list, project.timeline_lookup, project.character_state_lookup, and project.world_rule_lookup must preserve user/project/work scope.

- Implemented worker client and registry support for project.foreshadowing.list, project.timeline_lookup, project.character_state_lookup, and project.world_rule_lookup, and added them to the project knowledge skill allowed tool contract.

- Added frontend red test requiring project space to expose work knowledge sections (works, chapters, settings, foreshadowing, timeline) and import chapters for the selected work.

- Implemented frontend project-space work/chapter API types, API methods, work creation, chapter listing, chapter import, and ordinary-user knowledge sections labeled ��Ʒ����/�½�/�趨/����/ʱ����.

- Added worker Trace red test requiring project tool runs to be summarized into projectKnowledge trace fields for chapters, foreshadowings, and world rules.

- Implemented projectKnowledge Trace summary in NovelResearchAgent so project tool runs expose projectId/workId plus retrieved chapters, foreshadowings, timeline events, character states, and world rules.

- Added Admin Trace projectKnowledge display section showing project/work binding, retrieved chapters, foreshadowing, world-rule, timeline, and character-state hits from resultJson.trace.projectKnowledge.

### Verification Evidence - Project Knowledge Agent Slice
- Backend targeted matrix: mvn -Dtest=KnowledgeProjectWorkServiceTest,KnowledgeInternalControllerTest,KnowledgeControllerTest -DforkCount=0 test passed with 33 tests, 0 failures, 0 errors.
- Worker targeted matrix: python -m pytest tests/test_domain_tools.py tests/test_skill_registry.py selected NovelResearchAgent project Trace/cache tests -q passed with 20 tests.
- Frontend targeted matrix: npm test -- --run KnowledgeProjectSpace.spec.ts AdminAgentTraceView.spec.ts passed with 9 tests.
- Frontend type-check: npm run type-check passed.

### Release Package
- First package D:\\Git\\agent\\noval-release-ai-qa-agent-20260707_032949.tar.gz was rejected because crawler pytest/__pycache__ artifacts were present.
- Final package created and verified: D:\\Git\\agent\\noval-release-ai-qa-agent-20260707_033025.tar.gz.
- Archive verification: 1005 entries, required phase16/backend/frontend/worker/docker files present, no .git/node_modules/target/dist/.pytest_cache/__pycache__/.codex-research/nested release archive entries.

## Session Addendum: 2026-07-08 (Project Knowledge Production Gap Fix Progress)
### Implementation
- Implemented real project vector RAG fallback chain: chapter import now attempts EmbeddingClient + QdrantClient upsert with strict user/project/work payload; project chunk search tries Qdrant first and falls back to lexical MySQL search.
- Added internal project/work resolution through POST /internal/knowledge/projects/resolve and worker tool project.resolve.
- Updated webnovel-project-knowledge-qa skill to require project.resolve before project-scoped retrieval when work scope is not explicit.
- Extended worker projectKnowledge Trace summary with project.resolve status/title and retained retrievedChunks.
- Updated Admin Trace to display retrievedChunks and normalized several Trace UI labels to Chinese; sidebar admin labels are now Chinese.
- Added cache-first regression for rank refresh mode: ordinary trend questions remain AUTO; explicit realtime/no-cache refresh becomes FORCE.

### Verification So Far
- Backend: mvn -q "-Dtest=KnowledgeProjectWorkServiceTest,KnowledgeInternalControllerTest" "-DforkCount=0" test passed.
- Worker: python -m pytest tests/test_domain_tools.py tests/test_skill_registry.py tests/test_novel_research_agent.py::NovelResearchAgentTest::test_project_tool_runs_are_summarized_for_trace tests/test_novel_research_agent.py::NovelResearchAgentTest::test_rank_refresh_mode_is_auto_unless_user_explicitly_forces_refresh -q passed with 22 tests.
- Frontend: npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/components/knowledge/__tests__/KnowledgeProjectSpace.spec.ts passed with 9 tests.
- Frontend type-check: npm run type-check passed.

## Session Addendum: 2026-07-08 (Project Knowledge Production Gap Fix Completion)
### Implementation Completion
- Completed Phase 50 against `docs/superpowers/specs/2026-07-08-project-knowledge-production-gap-fix-design.md` and `docs/superpowers/plans/2026-07-08-project-knowledge-production-gap-fix.md`.
- Backend now attempts real EmbeddingClient + QdrantClient upsert for imported project chunks with strict user/project/work payload, and searches Qdrant first with lexical fallback.
- Added user-owned project/work title resolution through `project.resolve`, including unique, ambiguous, and not-found states for new chat binding.
- Worker exposes `project.resolve`, updates the project knowledge skill contract, summarizes resolve status and retrieved chunks into Trace, and preserves AUTO rank refresh unless the user explicitly forces real-time/no-cache refresh.
- Admin Trace displays retrieved project chunks, and admin navigation labels are localized to Chinese.

### Fresh Verification Evidence
- Backend: `mvn -q "-Dtest=KnowledgeProjectWorkServiceTest,KnowledgeInternalControllerTest,KnowledgeControllerTest" "-DforkCount=0" test` passed with exit 0.
- Worker: `python -m pytest tests/test_domain_tools.py tests/test_skill_registry.py tests/test_novel_research_agent.py::NovelResearchAgentTest::test_project_tool_runs_are_summarized_for_trace tests/test_novel_research_agent.py::NovelResearchAgentTest::test_rank_refresh_mode_is_auto_unless_user_explicitly_forces_refresh -q` passed with 22 tests.
- Frontend: `npm test -- --run src/views/knowledge/__tests__/AdminAgentTraceView.spec.ts src/components/knowledge/__tests__/KnowledgeProjectSpace.spec.ts` passed with 9 tests.
- Frontend type check: `npm run type-check` passed.
- Diff hygiene: `git diff --check` passed; only LF-to-CRLF warnings were emitted.

### Packaging Evidence
- Final archive created: `D:\Git\agent\noval-release-ai-qa-agent-20260708_023244.tar.gz` before final plan log update, then rebuilt after plan/progress finalization.
- Archive verification required Phase 16/17 SQL, backend project knowledge service/resolve DTO/controller, worker project tools/skill/trace code, frontend Admin Trace/sidebar, Phase 50 spec/plan, and docker-compose.
- Archive exclusion verification found no `.git`, `.idea`, `node_modules`, `target`, `dist`, `.pytest_cache`, `__pycache__`, `.codex-research`, local cache/release folders, Redis dump, logs, or nested release archives.
- Final rebuilt archive after plan/progress finalization: D:\Git\agent\noval-release-ai-qa-agent-20260708_023500.tar.gz (1,370,335 bytes before final metadata refresh).
