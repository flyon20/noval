# Trend And Auth Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复趋势页图表和结果展示问题，补齐可用数据展示，并新增无验证码注册能力。

**Architecture:** 趋势页优先复用现有 `/api/data/visual` 和 `/api/analysis/trend` 两条链路，前端负责中文映射、预览态与详情抽屉，后端仅补现有接口可稳定提供的统计字段。登录注册沿用现有 JWT 登录体系，在 `sys_user` / `sys_user_role` 上增加最小注册落库逻辑，不引入验证码或额外认证链路。

**Tech Stack:** Vue 3, Element Plus, vue-echarts, Vitest, Spring Boot, JdbcTemplate, MockMvc, H2

---

### Task 1: 趋势页问题建模与测试兜底

**Files:**
- Modify: `frontend/src/views/trend/__tests__/TrendView.spec.ts`
- Modify: `frontend/src/views/trend/TrendView.vue`
- Modify: `frontend/src/components/trend/TrendChartCard.vue`
- Modify: `frontend/src/components/trend/TrendSummaryCards.vue`
- Modify: `frontend/src/components/trend/TrendComparisonList.vue`
- Modify: `frontend/src/components/trend/TrendSnapshotTable.vue`

- [ ] **Step 1: 写失败测试，覆盖中文标签、预览态、详情入口**
- [ ] **Step 2: 跑趋势页测试，确认当前失败点**
- [ ] **Step 3: 为趋势页补中文映射工具和预览/详情状态**
- [ ] **Step 4: 更新图表、摘要卡片、对比列表、快照表格的展示逻辑**
- [ ] **Step 5: 跑趋势页测试确认转绿**

### Task 2: 趋势页数据补齐与移动端适配

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/service/DataQueryService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/vo/VisualDataVO.java`
- Modify: `frontend/src/types/data.ts`
- Modify: `frontend/src/views/trend/TrendView.vue`
- Modify: `frontend/src/components/trend/TrendContextBar.vue`
- Modify: `frontend/src/components/trend/TrendTagCloud.vue`
- Modify: `frontend/src/components/trend/TrendSummaryCards.vue`

- [ ] **Step 1: 盘点当前视觉数据缺口，确定可从 rank/history/theme 结果派生的字段**
- [ ] **Step 2: 后端补充稳定统计字段，不依赖大模型必须返回结构化明细**
- [ ] **Step 3: 前端把新增字段接入趋势页卡片和说明文案**
- [ ] **Step 4: 调整桌面与手机端布局，保证详情抽屉和图表在窄屏可用**
- [ ] **Step 5: 运行趋势页前后端相关测试**

### Task 3: 注册接口与登录页双态表单

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/RegisterRequest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`
- Modify: `frontend/src/api/auth.ts`
- Modify: `frontend/src/types/auth.ts`
- Modify: `frontend/src/views/login/LoginView.vue`
- Modify: `frontend/src/views/login/__tests__/LoginView.spec.ts`

- [ ] **Step 1: 先写注册成功、重复用户名失败、登录注册切换的失败测试**
- [ ] **Step 2: 后端补注册 DTO、接口、仓储落库和默认 USER 角色绑定**
- [ ] **Step 3: 前端补登录/注册切换表单、基础校验和错误提示**
- [ ] **Step 4: 跑登录注册前后端测试并修正细节**
- [ ] **Step 5: 做手机端表单和交互回归**

### Task 4: 最终验证与提交整理

**Files:**
- Verify only

- [ ] **Step 1: 运行趋势页、登录页、分析/扫榜回归测试**
- [ ] **Step 2: 运行前端 `npm run type-check`**
- [ ] **Step 3: 运行后端认证与趋势相关测试**
- [ ] **Step 4: 用浏览器验证趋势页与登录注册桌面/手机端行为**
- [ ] **Step 5: 分主题提交改动并整理交付说明**
