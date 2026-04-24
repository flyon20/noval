# Task Plan: 补齐网文项目后端 V1 落地能力

## Goal
基于 `docs/项目总设计-v2.md`、`docs/分步开发计划.md` 及历史技术文档，检查并补齐当前仓库中未落地的 V1 后端能力，同时完善数据库、Redis 和必要的工程支撑配置。

## Current Phase
Phase 6

## Phases

### Phase 1: 文档与现状梳理
- [x] 阅读项目设计文档与历史技术文档
- [x] 对照当前后端接口、数据库脚本、Redis 使用情况
- [x] 识别已落地与未落地能力
- **Status:** complete

### Phase 2: 方案确认与范围锁定
- [x] 与用户确认是否包含未落地工程支撑
- [x] 输出补齐方案与推荐实现路径
- [x] 获得用户确认后进入实现
- **Status:** complete

### Phase 3: 测试先行与功能补齐
- [x] 先为缺失能力补充失败测试
- [x] 补齐后端接口、服务、仓储与脚本
- [x] 补齐数据库与 Redis 配套落点
- **Status:** complete

### Phase 4: 验证与回归
- [x] 运行相关单测/集成测试
- [x] 检查接口契约、缓存策略与脚本一致性
- [x] 修复验证中发现的问题
- **Status:** complete

### Phase 5: 交付总结
- [x] 汇总修改范围、验证结果与剩余风险
- [x] 明确后续建议
- **Status:** in_progress

## Key Questions
1. 文档承诺但当前未落地的 V1 后端能力具体有哪些？
2. 数据库表结构、初始化脚本、Redis 缓存/安全配置是否与设计基线一致？
3. 哪些工程支撑应一并补齐，才能让后端能力真正可运行、可验证？

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 以 `docs/项目总设计-v2.md` 作为主基线 | 该文档已明确为项目内唯一设计基线 |
| 本次范围包含“未落地工程支撑” | 用户已明确要求未落地内容也补齐 |
| 先做设计确认，再进入测试与实现 | 受 brainstorming 工作流约束，先确认方案可减少返工 |
| 主线数据访问迁移到 MyBatis-Plus，鉴权安全层保留现状 | 满足用户对 ORM 的要求，同时避免把风险扩散到非目标链路 |
| Python 番茄抓取采用页面内嵌 `window.__INITIAL_STATE__` 解析 | 实测可直接拿到榜单、书籍详情、目录与正文，稳定性高于硬拆 DOM |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| 读取历史文档时中文乱码 | 1 | 改用正确编码重新读取，确认历史文档可正常解析 |
| `rg.exe` 在当前环境执行被拒绝 | 1 | 改用 PowerShell `Get-ChildItem` / `git grep` 完成检索 |
| Python 依赖安装与测试并发执行导致测试先失败 | 1 | 识别为时序问题后，改为先安装依赖再顺序执行测试 |

## Notes
- 实现阶段遵循小步提交、优先补齐 V1 明确承诺的能力。
- 任何来自文档的新增需求，若明显超出 V1 范围，需要单独标记而不顺手扩张。
- 完成前必须有新鲜的测试/验证证据。
## Session Addendum 2026-03-21
- Added OCR-based chapter deobfuscation for Fanqie reader content.
- Added structured JSON result storage for `analysis_result` via `result_json`.
- Verified real chapter fetching returns readable Chinese with `PUA=0` for sampled chapters.
- Verified crawler service HTTP contract by launching a temporary local Uvicorn server and calling `/health`, `/internal/book`, and `/internal/chapters` successfully.
## Session Addendum 2026-03-21 (Backend Review)
- Phase 6 started for a full backend replay review across functionality, interface health, exception handling, and security framework coverage.
- Deliverable for this phase is an endpoint coverage matrix plus targeted fixes for any gaps found under auth, validation, error, or rate-limit scenarios.
- Any discovered issue should be reproduced by a failing test before implementation, then verified with fresh regression evidence.
## Phase 6 Completion Summary
- Verified that documented backend controllers exist for auth, crawler, analysis, config, data, system, and security coverage.
- Reproduced three concrete backend review gaps with failing tests: unsecured logout access, missing required query parameter returning 500, and blank validated query parameter returning 500.
- Fixed the review gaps with minimal production changes and completed both targeted and full backend regression.
- Backend replay review phase is complete for this session; remaining policy-level risk is whether prompt config write access should stay open to `USER`.

## Session Addendum 2026-03-24

### Goal
- Rework authentication UX and trend analysis so the product can be accepted end-to-end without further user handholding.
- Authentication must provide clear Chinese feedback for login/register failures and enforce password rules.
- Trend analysis must target the exact selected rank board rather than platform-wide mixed data.

### Current Phase
- Phase 1: design and implementation planning

### Planned Phases
- Phase 1: write the design spec and execution plan for auth + trend rework
- Phase 2: test-first auth error semantics and password rule upgrade
- Phase 3: test-first trend contract rework around `platform + channelCode + boardCode`
- Phase 4: backend structured JSON storage/query refactor
- Phase 5: frontend trend/auth UX rework with mobile adaptation
- Phase 6: verification, git cleanup, and commit

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Trend analysis will be scoped to a concrete board, not broad platform data | This matches the product expectation from the rank page and avoids meaningless mixed insights |
| Trend AI output will be constrained to JSON and stored directly in `analysis_result.result_json` | Structured data is faster to parse, easier to render, and reusable for history/visual pages |
| Trend page will no longer auto-run analysis on mount | User explicitly required "don't start unless I click" |
| Authentication errors will be expressed as stable Chinese user-facing messages | Prevents raw backend/axios noise from leaking into the UI |

### Risks
- Current theme prompt seed and test fixtures only cover a shallow JSON contract and will need coordinated updates.
- Trend-related front-end components currently assume platform-level visualization fields and will need a broader type migration.

### Execution Update: 2026-03-24
- Phase 2 complete: auth error semantics and password rules remain green under `AuthControllerTest` and `LoginView.spec.ts`.
- Phase 3 complete: trend contract was reworked to `platform + channelCode + boardCode`, with fresh structured seed data for the shared board snapshots.
- Phase 4 complete: backend trend analysis and `/api/data/visual` now return board-scoped structured payloads.
- Phase 5 complete: trend page no longer auto-runs, now loads rank context from crawler preference, supports manual run only, and keeps mobile-friendly preview/detail behavior.
- Phase 6 in progress: verification is complete, remaining work is git hygiene and commit creation without including unrelated temp artifacts.

## Session Addendum 2026-03-25

### Goal
- Rework AI model configuration from a single comma-separated list into a real model registry that supports multiple OpenAI-compatible models, per-model temperature ranges/defaults, and a single user-facing model selector shared by all analyses.
- Rework trend analysis so the AI input/output JSON contract becomes explicit, visible, and editable from the admin prompt config page, while the runtime always enforces the required board-scoped structured fields.
- Rebuild the trend page around the strict contract so charts, representative works, board summary, word cloud, theme distribution, and insight cards all render from stored structured JSON instead of fallback guesswork.

### Current Phase
- Phase 1: design and execution planning

### Planned Phases
- Phase 1: write the design spec and implementation plan for model registry + trend JSON contract rework
- Phase 2: test-first backend model registry and prompt-config contract fields
- Phase 3: implement backend model registry resolution for legacy Java gateway + LangGraph worker
- Phase 4: implement admin configuration UI and guarded JSON contract editing
- Phase 5: rework trend analysis contract, persistence, and trend page rendering/mobile layout
- Phase 6: verification, local run, and final delivery summary

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Use a structured model registry JSON in system config instead of only `ai.available-models` | The product now needs per-model request settings, temperature metadata, and future expansion without adding more scattered keys |
| Keep user-side model choice as one shared preference key | User explicitly requires that single-book analysis and trend analysis use the same selected model |
| Extend prompt config with admin-visible input/output JSON contract fields | The AI chain must expose the request/response contract in the UI while still enforcing the contract at runtime |
| Treat trend JSON fields such as `boardSummary`, `historicalWordCloud`, `themeDistribution`, `hotBooks`, and `insightCards` as mandatory contract outputs | The trend page should render directly from stored structured data instead of backend placeholder synthesis |
| Preserve existing prompt专业性 but append runtime-enforced contract instructions | Admin prompt text remains expressive, while the required structured output becomes stable enough for parsing/storage/rendering |

### Risks
- The current LangGraph worker only knows global OpenAI-compatible base URL and API key, so per-model registry support requires coordinated Java + Python request changes.
- Existing prompt config rows, system config seeds, and trend test fixtures all assume the old flat model list and shallow trend schema, so schema/data migration must stay incremental.
- Trend page components currently mix result JSON and backend fallback data; this needs a careful migration to avoid blank panels during the transition.

### Execution Update: 2026-03-25
- Phase 2 complete: backend model registry endpoints, prompt-config input contract fields, and per-request runtime model resolution are now wired through Java and LangGraph worker.
- Phase 3 complete: trend analysis normalization no longer fabricates board-level business conclusions; it now preserves stored JSON fields, derives only structural equivalents, and returns empty structures when contract-driven fields are absent.
- Phase 4 complete: trend prompt seeds/examples and persisted theme fixtures now include `boardSummary`, `themeDistribution`, `themeTable.representativeBooks`, `hotBooks.rankNo`, and richer `snapshotComparisons`.
- Phase 5 complete: trend page was rebuilt around the strict contract, keeping click-to-run behavior, compact preview + detail drawer, fuller desktop result support panels, and mobile-compatible single-column fallback.
- Phase 6 complete: targeted and broader verification are both green; remaining work is local git hygiene/commit only, excluding unrelated artifacts such as `appendonly.aof`.

## Session Addendum 2026-03-25 (Prompt Contract Visibility Follow-up)

### Goal
- Make the admin prompt-config page show the system-preconfigured input/output JSON contracts instead of blank boxes.
- Finish the model-registry + prompt-contract + trend-contract work to the product level the user requested, not just to "API exists" level.

### Current Phase
- Phase 2: reproduce, lock root cause, and add failing coverage before implementation.

### Locked Findings
- Local MySQL `prompt_config` rows for `deconstruct/structure/plot/theme` currently have `input_json_schema`, `input_example_json`, `output_json_schema`, `output_example_json`, and `parse_config_json` all empty.
- `PromptConfigService` currently reads DB rows as-is and has no default-contract backfill path, so the admin page can only render blanks when legacy rows exist.
- Current SQL seed only writes contract fields for `theme`; the other three prompt types still have no system default contract data.
- The model-registry row exists in `system_config`, so the remaining complaint on that page is about clarity/presentation and not raw API absence.
- Trend visual rendering still contains structural fallbacks and a fake bar-chart word cloud path that need tightening.

## Session Addendum 2026-03-26 (Repo Understanding + Local Run)

### Goal
- Read the project docs and key entry-point code to rebuild an accurate mental model of the system.
- Bring the project up successfully in the current Windows environment with the least risky local path.

### Current Phase
- Phase complete: local stack verification and delivery summary

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Prefer the documented local联调 path over Docker Compose | `docker` is not installed in the current environment, while Java/Node/Python/Redis local tooling is available |
| Start backend against H2 test-classpath initialization instead of assuming local MySQL schema state | This matches `docs/本地联调说明.md` and avoids coupling startup success to unknown local MySQL seed state |
| Keep Redis local, and start crawler + langgraph-worker as separate Python services | This mirrors the documented service topology and verifies the real inter-service chain used by the frontend/backend |

### Verification Summary
- Confirmed the docs baseline is `docs/项目总设计-v2.md`, with local run details in `docs/本地联调说明.md`.
- Confirmed local service ports are listening: `5000` (crawler), `8001` (langgraph-worker), `6379` (redis), `8080` (backend), `5173` (frontend).
- Confirmed HTTP health checks pass for crawler, langgraph-worker, backend, and frontend proxy to backend.
- Confirmed the frontend renders the login page in a browser, not just static HTML.

### Notes
- Initial crawler/langgraph-worker startup failed because the injected environment variable command was quoted incorrectly; retrying with `cmd.exe /c "set ... && ..."` resolved both.
- Initial frontend startup used `npm run dev` via `Start-Process`, which passed host/port as positional args to Vite; restarting with `node_modules/.bin/vite.cmd --host=127.0.0.1 --port=5173` fixed the route handling.


## Session Addendum 2026-03-26 (Analysis Chain Deep Dive)

### Goal
- Rebuild the end-to-end analysis mental model from docs and real code.
- Focus on the exact path for chapter/trend analysis, model selection, and LangGraph worker organization.

### Current Phase
- Phase complete: architecture understanding and chain summary

### Key Findings
| Finding | Why it matters |
|---------|----------------|
| The project currently has two AI runtimes: `legacy` and `langgraph`, and the default system config is still `legacy` | LangGraph exists in code, but it is not the always-on main path |
| `AnalysisService` is the real orchestration center in Java | It decides cache reuse, data loading, prompt lookup, runtime branching, SSE, and persistence |
| The current Python worker uses one shared `LangGraphAnalysisService` with a small `StateGraph`, not four separately implemented agent modules yet | The code has begun the LangGraph migration, but it has not fully reached the multi-agent design doc yet |
| Model registry + prompt contract now bridge Java and Python together | Frontend model choice, backend runtime resolution, and worker provider config are already connected |
| Trend analysis is much more contract-driven than single-book analysis | Trend flow strongly depends on structured JSON, normalization, and board-scoped fields |

### Notes
- Docs should be read in two layers: current baseline (`README.md`, `docs/project-design-v2.md`) and forward-looking design (`docs/superpowers/specs/2026-03-24-phase2-langgraph-multi-agent-design.md`).
- When explaining the project to others, it is most accurate to say: "business orchestration lives in Java; AI execution can run through either LangChain4j or the LangGraph worker depending on config."


## Session Addendum 2026-03-26 (Single-book 10-Chapter Stream Fix)

### Goal
- Fix the single-book analysis page failure when requesting 10 chapters.
- Preserve real streaming typing while removing fake progress leakage on failure.

### Current Phase
- Phase complete: targeted fix verified

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Fix chapter fetch at crawler source instead of only masking backend errors | The immediate failure came from one broken reader URL aborting the whole fetch |
| Keep stream typing but ignore transport placeholder progress on the frontend | Real token streaming must stay, but `[analysis-progress]` should not pollute result text or block fallback |
| Use minimal targeted tests in crawler + frontend stream runtime | Fastest way to lock the bug and verify no regression in the affected path |


## Session Addendum 2026-03-26 (Actual Chapter Count UX)

### Goal
- Make the single-book analysis page clearly show when requested chapter count and actually fetched chapter count differ.
- Keep the existing streaming typing interaction unchanged.

### Current Phase
- Phase complete: UX clarification shipped with regression coverage

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Store actual/requested chapter metadata in `result_json` | Reuses the existing cached/persisted analysis payload shape and keeps API widening minimal |
| Show a ratio only when actual fetched chapters are lower than requested | Keeps normal successful runs visually compact while making degraded fetches explicit |
| Apply metadata attachment in all single-book analysis paths | Avoids drift between blocking, streaming, chunked, and LangGraph-backed runs |


## Session Addendum 2026-03-26 (10-Chapter Timeout Budget)

### Goal
- Prevent 10-chapter single-book analysis from failing due to a hard 15-second timeout during fallback/blocking execution.
- Keep the current streaming UX and fallback path intact.

### Current Phase
- Phase complete: timeout budget fix verified

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Use a targeted longer timeout only for long single-book analysis | Fixes the reported path without broadening every request in the system |
| Align frontend blocking timeout with backend AI timeout budget | Avoids the browser aborting the fallback request before the backend finishes |
| Keep trend timeout logic separate from single-book timeout logic | The project already treats trend analysis as a distinct timeout class |


## Session Addendum 2026-03-26 (Forced Chunking For 8+ Chapters)

### Goal
- Prevent 8-10 chapter single-book analysis from staying on a single huge legacy LLM call.
- Make the fallback path survivable under real long-content load.

### Current Phase
- Phase complete: forced chunking + longer fallback budget verified

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Force chunking by chapter count for large single-book analysis | Token estimation alone was too optimistic for real 8-10 chapter content |
| Use fixed-size chapter chunks for this path | Simpler and more predictable than trying to retune global token heuristics only |
| Raise frontend fallback timeout above backend per-call timeout | The browser must allow chunk + merge to finish as a whole request |


## Session Addendum 2026-03-26 (Persistent Analysis/Trend Context)

### Goal
- Keep the current single-book analysis and trend-view context stable across navigation and page refresh.
- Persist rank-page chapter-count preference separately from rank sample-count preference.

### Current Phase
- Phase complete: persistent context + result restoration verified

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Persist analysis/trend view context in `user_config` instead of route only | Matches the requirement that refresh and page switching should not force reselection |
| Restore analysis panels from history instead of auto-rerunning | Preserves the user?s current viewed result and avoids unnecessary model calls |
| Keep trend context separate from rank-page preference | Prevents `/rank` board changes from unexpectedly replacing the current `/trend` workspace |
| Persist rank chapter count with its own key | `rankFetchCount` already exists, but chapter count is a distinct user preference |


## Session Addendum 2026-03-26 (Rank Mobile Refresh Flow)

### Goal
- Replace mobile rank-page pagination with a refresh-flow / infinite-scroll experience while keeping the existing rank-card layout.
- Preserve a clear path back to the current board list top.

### Current Phase
- Phase complete: mobile refresh flow verified

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Keep layout unchanged and only replace the mobile page-turn interaction | Matches the user?s clarified requirement and avoids unnecessary UI churn |
| Use `IntersectionObserver` plus a manual fallback load-more button | Gives smooth auto paging but still has a recovery path when auto loading misses |
| Keep desktop pagination as-is | Mobile and desktop usage patterns differ, and desktop pagination still works well |
| Suspend disruptive poll replacement when mobile flow has already loaded multiple pages | Prevents the appended list from collapsing unexpectedly during background refresh |


## Session Addendum 2026-03-26 (Single-book Analysis First-Run UX)

### Goal
- Prevent first-run 10-chapter analysis from overloading the page by auto-starting every panel.
- Make long single-book streaming output readable without requiring manual refresh.

### Current Phase
- Phase complete: first-run behavior and streaming display fixed

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| First trigger starts only the active analysis panel | Avoids unnecessary concurrent long-running calls on the first interaction |
| Keep other panels manual/on-demand | Preserves independence between the three analysis modes |
| Show full streaming text on the analysis page | Prevents the false impression that generation stopped after a few hundred characters |


## Session Addendum 2026-03-26 (UI Copy + Drawer + Trend Visual Cleanup)

### Goal
- Remove low-value UI copy, improve mobile navigation access, fix the desktop chapter drawer, and make trend visuals clearer.

### Current Phase
- Phase complete: targeted UI cleanup verified

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Reduce copy instead of redesigning whole page structures | Matches the request to keep only basic function names |
| Keep mobile top bar sticky at all times | Makes logout consistently reachable on phones |
| Rebuild the chapter drawer layout rather than patching its old bottom-sheet structure | The old desktop interaction problem was structural, not just spacing |
| Use a colorful SVG cloud and hide pie labels inside slices | Solves the trend visual complaints without introducing a heavy new chart dependency |
| Present theme data in a real table | Improves scanability and prevents the old mixed, card-like layout from feeling chaotic |

## Session Addendum 2026-03-29

### Goal
- Finish the remaining admin/runtime configuration hardening needed for production rollout.
- Support secure admin-side model key management and automatic admin promotion for the requested phone.

### Current Phase
- Phase complete: targeted implementation and verification

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Encrypt backend-managed model keys and runtime secret config values at rest | Prevents plaintext secret leakage from DB dumps and admin API reads |
| Return only masked key state to the frontend | Browser/devtools should never receive reusable provider secrets |
| Keep env-var fallback but let admin-configured key override immediately | Meets the product need for后台可配置生效 while preserving deploy-time flexibility |
| Use `auth.bootstrap-admin-phones` to auto-sync `ADMIN` role | Gives a low-risk path to make `15599316908` admin without adding a full user-management subsystem right now |

## Session Addendum 2026-03-29 (Turnstile + SMS Anti-Abuse)

### Goal
- Add Cloudflare Turnstile to SMS send flow and harden SMS abuse protection for production.

### Current Phase
- Phase complete: targeted implementation and verification

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Only gate `/api/auth/sms/send` with Turnstile in this round | Minimizes UX impact while directly protecting the expensive SMS action |
| Expose Turnstile site key through a public backend endpoint | Keeps secret server-side and avoids hard-coding per-environment frontend builds |
| Keep SMS risk control Redis-first but add local fallback | Avoids total loss of protection when Redis is temporarily unavailable |

## Session Addendum 2026-03-29 (Password Login Anti-Bruteforce)

### Goal
- Harden password login against repeated requests and brute-force attempts before production rollout.

### Current Phase
- Phase complete: dedicated auth risk-control shipped with targeted verification

### Key Decisions
| Decision | Rationale |
|----------|-----------|
| Keep generic rate limit and add a dedicated password-login risk layer | Generic request throttling alone is too coarse for real password attack patterns |
| Count failures by phone, IP, and phone+IP pair | Covers both targeted brute-force and IP-based account sweeping |
| Clear phone-scoped counters on successful login | Reduces lockout pain for legitimate users after they finally enter the right password |

## Session Addendum 2026-04-24 (Project Understanding Review)

### Goal
- Build an accurate mental model of the current project without changing business code.
- Focus primarily on frontend and backend logic, with a lighter pass over the crawler service.

### Current Phase
- Phase 1: documentation, entrypoint, and dependency scan

### Planned Phases
- Phase 1: read project docs, package manifests, runtime compose/config files, and entrypoints
- Phase 2: trace frontend routing, API clients, auth/session state, and core views
- Phase 3: trace backend controllers, services, repositories, security/config, and external clients
- Phase 4: skim crawler API/security and Fanqie crawling implementation
- Phase 5: summarize end-to-end data flow, key modules, and risks for handoff

### Completion Summary
- Phase 1 complete: runtime topology, dependencies, compose wiring, and backend/frontend entrypoints were confirmed.
- Phase 2 complete: frontend auth bootstrap, routing, API adapters, core views, and SSE/composable flow were traced.
- Phase 3 complete: backend controller layout, auth/security pipeline, analysis orchestration, crawler integration, data read models, and config services were traced.
- Phase 4 complete: crawler internal API security and Fanqie crawling logic were traced, along with the internal LangGraph worker boundary.
- Phase 5 complete: end-to-end user/data flow and the current migration state were summarized for future implementation work.

## Session Addendum 2026-04-25 (Prompt Governance Redesign)

### Goal
- Redesign prompt-template governance so admin templates become publishable global defaults, user templates can be bound or copied under permission limits, and runtime selection/history become explainable and auditable.

### Current Phase
- Phase 1: requirements clarification and design shaping

### Planned Phases
- Phase 1: lock product semantics for admin publish, user binding/copying, fallback rules, and history behavior
- Phase 2: write the design spec for schema, permission, publish flow, runtime resolution, and API/UI changes
- Phase 3: write the implementation plan and migration strategy for server rollout

### Execution Update
- Phase 1 complete: product semantics were locked with the user, including:
  - history snapshots instead of single backup field
  - user bind or copy modes
  - admin draft + explicit publish flow
- Phase 2 complete: formal spec written to `docs/superpowers/specs/2026-04-25-prompt-governance-redesign-design.md`
- Phase 3 pending: implementation plan and migration SQL are waiting for user review of the written spec
