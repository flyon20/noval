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
