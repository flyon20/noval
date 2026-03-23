# Rank Console UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重做控制台壳层、扫榜页和详情/抓章抽屉，并补齐用户扫榜版块偏好恢复能力。

**Architecture:** 保持现有 Vue 3 + Element Plus + Spring Boot 架构不变，前端以 `RankView + Drawer + AppShell` 为主改造对象，后端只新增最小用户偏好存储与接口。榜单分页、抓章与分析链路继续复用现有接口与缓存策略。

**Tech Stack:** Vue 3, TypeScript, Element Plus, SCSS, Vitest, Spring Boot 3.2, Java 17, MySQL/H2, MockMvc

---

### Task 1: 补失败测试锁定用户扫榜偏好恢复

**Files:**
- Modify: `D:\Git\agent\noval\frontend\src\views\rank\__tests__\RankView.spec.ts`
- Modify: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\crawler\CrawlerPhase3IntegrationTest.java`
- Modify: `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-schema-h2.sql`

- [ ] Step 1: 在 `RankView` 测试中补“初始化优先恢复用户偏好版块”的失败用例
- [ ] Step 2: 在 `RankView` 测试中补“切换频道/榜单时会保存偏好”的失败用例
- [ ] Step 3: 在 backend 集成测试中补“保存/读取用户扫榜偏好”的失败用例
- [ ] Step 4: 运行前端与 backend 定向测试，确认先红

### Task 2: 落地用户扫榜偏好后端存储与接口

**Files:**
- Modify: `D:\Git\agent\noval\backend\sql\mysql\phase5-schema.sql`
- Modify: `D:\Git\agent\noval\backend\src\test\resources\sql\phase5-schema-h2.sql`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\dto\UserRankPreferenceRequest.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\vo\UserRankPreferenceVO.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\repository\CrawlerRepository.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\service\CrawlerService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\crawler\controller\CrawlerController.java`

- [ ] Step 1: 新增 `user_rank_preference` 表结构
- [ ] Step 2: 写最小 DTO/VO 承载 `platform + channelCode + boardCode`
- [ ] Step 3: 在 `CrawlerRepository` 中实现按 `user_id + platform` 查询和 upsert
- [ ] Step 4: 在 `CrawlerService` 中实现读取/保存当前用户偏好
- [ ] Step 5: 在 `CrawlerController` 中暴露 `GET/POST /api/crawler/preference`
- [ ] Step 6: 运行 backend 定向测试并确认变绿

### Task 3: 扫榜页接入用户偏好恢复与回写

**Files:**
- Modify: `D:\Git\agent\noval\frontend\src\types\crawler.ts`
- Modify: `D:\Git\agent\noval\frontend\src\api\crawler.ts`
- Modify: `D:\Git\agent\noval\frontend\src\views\rank\RankView.vue`

- [ ] Step 1: 补齐用户偏好前端类型与 API 包装
- [ ] Step 2: 在 `RankView` 初始化时增加“拉目录 -> 拉偏好 -> 选版块 -> 刷新榜单”的链路
- [ ] Step 3: 在频道/榜单切换时增加偏好回写
- [ ] Step 4: 保证偏好不存在或失效时自动回退到第一个可用版块
- [ ] Step 5: 运行 `RankView` 测试并确认通过

### Task 4: 扫榜页视觉重做与分页 5/10

**Files:**
- Modify: `D:\Git\agent\noval\frontend\src\constants\crawler.ts`
- Modify: `D:\Git\agent\noval\frontend\src\views\rank\RankView.vue`
- Modify: `D:\Git\agent\noval\frontend\src\views\rank\__tests__\RankView.spec.ts`

- [ ] Step 1: 将默认分页大小改为 `10`
- [ ] Step 2: 为扫榜页增加每页 `5 / 10` 切换控件
- [ ] Step 3: 切换每页数量时回第一页并重新拉分页数据
- [ ] Step 4: 将当前纯表格样式调整为高信息密度内容列表卡风格
- [ ] Step 5: 将简介在列表层统一裁到约 `100` 字
- [ ] Step 6: 跑前端定向测试

### Task 5: 控制台壳层轻奢改版

**Files:**
- Modify: `D:\Git\agent\noval\frontend\src\styles\tokens.scss`
- Modify: `D:\Git\agent\noval\frontend\src\layouts\AppShell.vue`
- Modify: `D:\Git\agent\noval\frontend\src\components\layout\AppHeader.vue`
- Modify: `D:\Git\agent\noval\frontend\src\components\layout\AppSidebar.vue`

- [ ] Step 1: 重整全局颜色、边框、阴影和字体 token
- [ ] Step 2: 提升 `AppShell` 的空间层级和内容舞台感
- [ ] Step 3: 简化 `AppHeader`，强化用户会话与关键操作
- [ ] Step 4: 重做 `AppSidebar` 品牌区与选中态样式
- [ ] Step 5: 跑已有壳层相关测试

### Task 6: 重做书籍详情与抓章抽屉

**Files:**
- Modify: `D:\Git\agent\noval\frontend\src\components\rank\BookDetailDrawer.vue`
- Modify: `D:\Git\agent\noval\frontend\src\components\rank\ChapterPreviewDrawer.vue`
- Modify: `D:\Git\agent\noval\frontend\src\views\rank\RankView.vue`
- Modify: `D:\Git\agent\noval\frontend\src\views\rank\__tests__\RankView.spec.ts`

- [ ] Step 1: 书籍详情抽屉展示完整简介
- [ ] Step 2: 抓章抽屉默认切为“章节摘要列表”视图
- [ ] Step 3: 每章仅显示标题、字数、摘要片段
- [ ] Step 4: 点击章节进入单章详情态展示全文
- [ ] Step 5: 保留并整理“进入分析 / 重新抓取章节 / 次数提示”
- [ ] Step 6: 运行前端定向测试

### Task 7: 最终验证

**Files:**
- Verify only

- [ ] Step 1: 运行 `python -m unittest discover -s tests -v`
- [ ] Step 2: 运行 `npx vitest run src/views/rank/__tests__/RankView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts --maxWorkers=1`
- [ ] Step 3: 运行 `mvn '-Dtest=PythonCrawlerClientTest,Phase5BackendIntegrationTest,CrawlerPhase3IntegrationTest' test`
- [ ] Step 4: 本机拉起 `crawler` 与 `backend`
- [ ] Step 5: 手动验证“保存偏好 -> 重新进入扫榜页 -> 恢复上次版块”
- [ ] Step 6: 手动验证“每页 5 / 10、简介摘要、章节摘要、章节详情全文”
