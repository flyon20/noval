# 趋势分析重构与认证体验升级 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重做登录注册体验并将趋势分析改造成围绕具体榜单的结构化 JSON 契约链路，确保桌面端与手机端都可直接验收。

**Architecture:** 后端以现有 Spring Boot + MyBatis-Plus 为基础，认证链路补齐可读错误语义与密码规则；趋势链路围绕 `platform + channelCode + boardCode` 聚合快照与历史分析，使用 LangChain4j JSON 输出能力解析结构化结果并直存 `analysis_result.result_json`。前端以 Vue 3 + Element Plus 为基础，趋势页改为用户显式触发分析、榜单上下文驱动展示，所有图表与卡片直接消费后端结构化字段。

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vitest, Spring Boot 3.2, Java 17, MyBatis-Plus, MockMvc, H2, LangChain4j

---

### Task 1: 固化本轮设计与文件化计划

**Files:**
- Create: `docs/superpowers/specs/2026-03-24-trend-analytics-rework-and-auth-ux-design.md`
- Create: `docs/superpowers/plans/2026-03-24-trend-analytics-rework-and-auth-ux.md`
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`

- [ ] **Step 1: 写出设计文档，明确认证与趋势分析目标、边界和 JSON 契约**
- [ ] **Step 2: 写出实施计划，按测试先行拆分任务**
- [ ] **Step 3: 更新项目根目录计划文件，记录当前阶段、风险和后续动作**

### Task 2: 用失败测试锁定认证体验目标

**Files:**
- Modify: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`
- Modify: `frontend/src/views/login/__tests__/LoginView.spec.ts`

- [ ] **Step 1: 新增后端测试，覆盖用户名不存在、密码错误、密码规则不合规、用户名重复**
- [ ] **Step 2: 新增前端测试，覆盖密码规则提示、中文错误映射、异常兜底显示**
- [ ] **Step 3: 运行认证相关测试，确认当前实现先失败**

### Task 3: 重做后端认证错误语义与密码规则

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/RegisterRequest.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/common/exception/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/novelanalyzer/common/result/ResultCode.java`
- Modify: `backend/src/main/java/com/novelanalyzer/common/exception/BusinessException.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthRepository.java`

- [ ] **Step 1: 为注册密码规则补充稳定校验逻辑**
- [ ] **Step 2: 区分登录失败场景并返回中文可读信息**
- [ ] **Step 3: 统一校验异常输出，避免只返回笼统英文**
- [ ] **Step 4: 运行后端认证测试并修正回归**

### Task 4: 重做前端登录注册交互

**Files:**
- Modify: `frontend/src/views/login/LoginView.vue`
- Modify: `frontend/src/lib/http-error.ts`
- Modify: `frontend/src/types/auth.ts`
- Modify: `frontend/src/api/auth.ts`

- [ ] **Step 1: 在注册态展示密码规则和输入要求**
- [ ] **Step 2: 新增前端友好错误映射，屏蔽奇怪数字和技术描述**
- [ ] **Step 3: 优化桌面端与手机端表单布局和错误展示**
- [ ] **Step 4: 运行登录页前端测试**

### Task 5: 用失败测试锁定趋势分析重构目标

**Files:**
- Modify: `backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java`
- Modify: `frontend/src/views/trend/__tests__/TrendView.spec.ts`

- [ ] **Step 1: 新增后端测试，要求趋势分析围绕具体榜单生成结构化结果**
- [ ] **Step 2: 新增数据接口测试，要求 `/api/data/visual` 返回榜单级结构化展示字段**
- [ ] **Step 3: 新增前端测试，要求页面挂载不自动分析、点击后才开始分析**
- [ ] **Step 4: 运行趋势相关测试并确认当前实现失败**

### Task 6: 重构趋势分析后端契约与存储

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/repository/AnalysisRepository.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/controller/AnalysisController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/dto/TrendAnalysisRequest.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/vo/TrendAnalysisVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/model/AiInvokeResult.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptConfigEntity.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/repository/CrawlerRepository.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/service/CrawlerService.java`
- Modify: `backend/sql/mysql/phase5-seed.sql`
- Modify: `backend/src/test/resources/sql/phase5-data-h2.sql`

- [ ] **Step 1: 为趋势分析请求补齐 `channelCode`、`boardCode` 与榜单上下文字段**
- [ ] **Step 2: 让提示词约束输出结构化 JSON，并用 LangChain4j JSON 模式解析**
- [ ] **Step 3: 将结构化字段直接写入 `result_json`，保留摘要文本用于流式展示**
- [ ] **Step 4: 运行趋势分析后端测试并修正回归**

### Task 7: 重构榜单级可视化查询接口

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/service/DataQueryService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/repository/DataQueryRepository.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/controller/DataController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/vo/VisualDataVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/vo/RankSnapshotVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/vo/SnapshotThemeComparisonVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/vo/ThemeTableItemVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/vo/ThemeWordCloudItemVO.java`

- [ ] **Step 1: 让 `/api/data/visual` 接口按榜单维度查询**
- [ ] **Step 2: 返回历史词云、题材演进、代表作品、风险信号等结构化展示字段**
- [ ] **Step 3: 统一中文展示所需字段，减少前端二次猜测**
- [ ] **Step 4: 运行数据接口测试**

### Task 8: 重做趋势页前端上下文、交互与移动端布局

**Files:**
- Modify: `frontend/src/views/trend/TrendView.vue`
- Modify: `frontend/src/components/trend/TrendContextBar.vue`
- Modify: `frontend/src/components/trend/TrendResultPreview.vue`
- Modify: `frontend/src/components/trend/TrendSummaryCards.vue`
- Modify: `frontend/src/components/trend/TrendComparisonList.vue`
- Modify: `frontend/src/components/trend/TrendSnapshotTable.vue`
- Modify: `frontend/src/components/trend/TrendTagCloud.vue`
- Modify: `frontend/src/composables/useTrendRun.ts`
- Modify: `frontend/src/lib/trend-display.ts`
- Modify: `frontend/src/api/analysis.ts`
- Modify: `frontend/src/api/data.ts`
- Modify: `frontend/src/api/crawler.ts`
- Modify: `frontend/src/types/trend.ts`
- Modify: `frontend/src/types/data.ts`
- Modify: `frontend/src/types/crawler.ts`

- [ ] **Step 1: 趋势页接入榜单目录与用户偏好，恢复当前榜单上下文**
- [ ] **Step 2: 去掉自动分析，改成显式点击后再发起流式请求**
- [ ] **Step 3: 让图表、词云、摘要、详情弹层全部消费后端结构化字段**
- [ ] **Step 4: 优化手机端卡片堆叠、抽屉关闭和长内容浏览体验**
- [ ] **Step 5: 运行趋势页前端测试并修正回归**

### Task 9: 全量验证与整理提交

**Files:**
- Verify only

- [ ] **Step 1: 运行后端认证与趋势相关测试**
- [ ] **Step 2: 运行前端登录页与趋势页测试、类型检查**
- [ ] **Step 3: 手动检查关键桌面端与手机端交互**
- [ ] **Step 4: 更新 `progress.md` 和 `findings.md`，记录验证结果**
- [ ] **Step 5: 整理 git 状态并提交本轮代码**
