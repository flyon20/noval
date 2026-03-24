# 前端第三阶段趋势与历史/PWA设计

## 目标

在第二阶段已经落地的 `/analysis` 单书分析页基础上，完成第三阶段的数据闭环能力：

1. 新增 `/trend` 趋势分析页，支持 `theme` 趋势流式分析、阻塞兜底和基础可视化。
2. 新增 `/history` 历史回看页，支持筛选、列表回看和详情复看。
3. 同步补齐移动端导航与布局，使趋势和历史在手机端可正常使用。
4. 落地最小可用 PWA 壳层，实现“可安装、可启动、可离线打开壳层”的工作台体验。

第三阶段只做“趋势分析 + 历史回看 + 手机端 + PWA 基础架构”，不把提示词配置和系统配置混入同一轮实现。

## 范围边界

### 本阶段实现

- `/trend`
- `/history`
- 左侧导航扩展
- 手机端底部导航
- 趋势分析流式输出和阻塞兜底
- 图表与主题摘要可视化
- 历史筛选、列表、详情回看
- PWA manifest、Service Worker 注册、基础缓存策略

### 本阶段不实现

- `/config/prompt`
- `/config/system`
- 推送通知
- 后台同步
- 复杂离线写回
- 完整历史正文离线缓存

## 后端真实基线

### 趋势分析接口

- `GET /api/analysis/trend`
- `POST /api/analysis/trend/stream`

请求体与后端 [TrendAnalysisRequest.java](/D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/dto/TrendAnalysisRequest.java) 对齐：

```ts
interface TrendRequest {
  platform: 'fanqie';
  category?: string;
}
```

响应体与后端 [TrendAnalysisVO.java](/D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/vo/TrendAnalysisVO.java) 对齐：

```ts
interface TrendAnalysisResult {
  analysisType: 'theme';
  platform: 'fanqie';
  category?: string;
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  sourceSnapshotCount: number;
}
```

### 数据查询接口

- `GET /api/data/visual`
- `GET /api/data/history`

后端 [VisualDataVO.java](/D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/data/vo/VisualDataVO.java) 已聚合好图表和主题数据，前端只消费，不重复统计：

```ts
interface VisualData {
  analysisTypeDistribution: ChartItem[];
  analysisDailyTrend: DailyCount[];
  rankCategoryDistribution: ChartItem[];
  latestSnapshots: RankSnapshot[];
  wordCloud: ThemeWordCloudItem[];
  themeTable: ThemeTableItem[];
  comparisonSummary: string | null;
  snapshotComparisons: SnapshotThemeComparison[];
}
```

历史结果与后端 [AnalysisHistoryItemVO.java](/D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/data/vo/AnalysisHistoryItemVO.java) 对齐：

```ts
interface AnalysisHistoryItem {
  id: number;
  bookId: number;
  bookName?: string | null;
  analysisType: 'deconstruct' | 'structure' | 'plot' | 'theme';
  chapterCount: number;
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  createdAt: string;
}
```

### 鉴权与安全

第三阶段严格复用现有前端安全口径：

- `Authorization: Bearer <token>`
- 单 token 模式，无 `refreshToken`
- 普通请求 `401 -> refresh -> retry once`
- 流式请求开流前判断过期时间，60 秒内先 refresh
- 流式请求收到 `401` 时只允许 refresh 并重开一次
- refresh 失败后清会话并跳回 `/login`

## 方案对比

### 方案 A：双页分治

- `/trend` 专注趋势分析和图表
- `/history` 专注回看和详情

优点：

- 与现有路由和业务边界一致
- 流式分析和历史复看互不污染状态
- 手机端更容易做信息优先级裁剪

缺点：

- 需要同时补两条路由和两套页面骨架

### 方案 B：一个大页内双 Tab

优点：

- 入口少，看起来集中

缺点：

- 页面状态会过重
- 图表、流式文本、表格、详情挤在同一页，后续维护成本高

### 方案 C：趋势页主导，历史做抽屉

优点：

- 初版实现最快

缺点：

- 历史筛选和复看体验偏弱
- 不像正式业务页，扩展性差

## 结论

采用方案 A：使用 `/trend` 和 `/history` 两个正式业务页，桌面端双栏、手机端单列，PWA 提供基础安装与离线壳层，不把完整受保护结果离线持久化。

## 页面设计

### 1. `/trend`

#### 页面结构

桌面端采用“两层一侧栏”：

1. 顶部上下文条
2. 中央趋势分析结果区
3. 下方图表区
4. 右侧主题摘要区

页面块划分：

- `TrendContextBar`
  - 平台
  - 分类
  - 快照数
  - 当前分析状态
- `TrendResultPanel`
  - 流式输出卡片
  - 停止/重跑/复制
  - `traceId / modelName / sourceSnapshotCount`
- `TrendVisualSection`
  - 分析类型分布饼图
  - 分析日趋势折线图
  - 榜单分类柱状图
  - 最近快照表
- `TrendInsightAside`
  - comparison summary
  - theme table
  - tag cloud
  - snapshot comparisons

#### 手机端布局

手机端改为单列卡片流：

1. 顶部标题和筛选
2. 流式结果卡
3. 关键摘要卡
4. 图表卡片按顺序堆叠
5. 主题表和标签云排在后面

规则：

- 不允许横向滚动
- 先结果，后图表
- 图表在手机端高度缩短，保证首屏可见主流程

### 2. `/history`

#### 页面结构

桌面端采用“列表 + 详情”双栏：

- 左侧：
  - 筛选条
  - 历史列表
- 右侧：
  - 详情元信息
  - Markdown 回看区
  - `resultJson` 摘要区

#### 手机端布局

手机端采用“列表 + 底部抽屉详情”：

- 筛选区吸顶
- 历史列表纵向滚动
- 详情点击后从底部 Drawer 打开

历史页不做重新生成，不做流式，不做 AI 二次请求。

## 数据流设计

### `/trend` 初始化

1. 读取默认筛选：
   - `platform = fanqie`
   - `category = male-hot-a`
2. 并行请求：
   - `GET /api/data/visual?platform=fanqie`
   - 发起趋势分析任务
3. 趋势分析任务优先走：
   - `POST /api/analysis/trend/stream`
4. 如果流式不可用，再回退：
   - `GET /api/analysis/trend?platform=fanqie&category=...`

### `/trend` 分类切换

1. 中止当前趋势流任务
2. 更新 category
3. 重新并行请求：
   - `visual`
   - `trend stream`
4. 若有上一次稳定结果，可短暂保留旧结果直到新结果进入 `start`

### `/history` 初始化

1. 默认请求：
   - `GET /api/data/history?platform=fanqie&limit=20`
2. 列表按后端结果倒序展示
3. 默认选中第一项作为详情

### `/history` 筛选

支持：

- `platform`
- `bookId`
- `analysisType`
- `limit`

筛选后重新请求列表，不在前端做本地筛选伪装。

## 流式与兜底策略

### 事件格式

沿用现有流式协议：

```ts
type StreamEventType = 'start' | 'delta' | 'done' | 'error';
```

### 趋势流式调用方式

- `fetch + ReadableStream`
- `AbortController`
- 不使用 `EventSource`

### 趋势流式失败兜底

以下场景直接回退阻塞接口：

- `404`
- `405`
- `501`
- `Content-Type` 非 `text/event-stream`
- 首包前解析失败

以下场景不自动回退：

- 已经收到部分 `delta` 后断线

处理方式：

- 保留已生成内容
- 页面显示“连接中断，可重试”
- 用户手动点击“重新生成”

## 可视化策略

### 图表选型

第三阶段只做基础图表：

- `analysisTypeDistribution`：饼图
- `analysisDailyTrend`：折线图
- `rankCategoryDistribution`：柱状图
- `latestSnapshots`：表格
- `wordCloud`：响应式标签云
- `themeTable`：表格
- `snapshotComparisons`：摘要卡列表

图表库使用 ECharts，标签云不引入额外图表扩展，直接使用 CSS 标签云组件，避免依赖膨胀。

## PWA 设计

### 本阶段 PWA 目标

- 可安装
- 可启动
- 有 manifest
- 有 Service Worker
- 可离线打开前端壳层
- 可缓存静态资源和最近一次可视化只读数据

### 缓存边界

允许缓存：

- HTML / JS / CSS / 字体 / 图标
- `GET /api/data/visual`
- 最近访问的非敏感壳层页面

不默认缓存：

- `POST /api/analysis/trend/stream`
- `GET /api/analysis/trend`
- `GET /api/data/history` 的完整详情正文
- 用户生成的完整 AI 长文本结果

原因：

- 历史和趋势结果属于内部分析数据
- 不把完整受保护数据长期持久化到 Service Worker 缓存

### 离线行为

- 离线时可打开应用壳层
- 离线时趋势页可显示最近一次缓存的 `visual` 数据
- 离线时不可发起新的流式分析
- 离线时历史页显示“当前离线，不提供完整历史正文离线回看”

## 组件与文件边界

### 新增类型与 API

- `frontend/src/types/trend.ts`
- `frontend/src/types/data.ts`
- `frontend/src/api/data.ts`
- `frontend/src/api/analysis.ts` 扩展 trend 方法

### 新增趋势模块

- `frontend/src/composables/useTrendRun.ts`
- `frontend/src/components/trend/TrendContextBar.vue`
- `frontend/src/components/trend/TrendSummaryCards.vue`
- `frontend/src/components/trend/TrendChartCard.vue`
- `frontend/src/components/trend/TrendSnapshotTable.vue`
- `frontend/src/components/trend/TrendTagCloud.vue`
- `frontend/src/components/trend/TrendComparisonList.vue`
- `frontend/src/views/trend/TrendView.vue`

### 新增历史模块

- `frontend/src/components/history/HistoryFilterBar.vue`
- `frontend/src/components/history/HistoryListPanel.vue`
- `frontend/src/components/history/HistoryDetailPanel.vue`
- `frontend/src/views/history/HistoryView.vue`

### 新增布局/PWA模块

- `frontend/src/components/layout/AppBottomNav.vue`
- `frontend/src/constants/navigation.ts`
- `frontend/src/pwa/register-sw.ts`
- `frontend/public/manifest.webmanifest` 或 Vite PWA manifest 配置

### 复用现有模块

- 继续复用现有 `analysis-stream.ts`
- 趋势结果卡尽量复用第二阶段的结果视觉语言
- Markdown 渲染继续复用 `markdown.ts`

## 错误处理

### 趋势页

- `400`：参数不合法
- `401`：刷新失败后跳登录
- `403`：无权限
- `404`：趋势数据或接口不可用
- `429`：请求过频
- `500`：通用错误 + `traceId`

### 历史页

- 列表请求失败时显示空态错误卡，不隐藏筛选条
- 详情解析失败时列表保留，右侧详情区单独报错

## 测试策略

第三阶段继续测试先行，至少覆盖：

- 趋势流式成功链路
- 趋势流式回退阻塞接口
- 趋势流式中断保留部分文本
- `/trend` 初始化并行请求 visual + trend
- 切换分类时中止旧任务并发起新任务
- `/history` 默认查询和筛选查询
- 历史列表点击后详情展示
- 手机端导航可见性
- PWA 注册逻辑
- 路由和导航守卫不回归

## 验收标准

- `/trend` 可在登录后正常访问
- 趋势分析支持流式优先和阻塞兜底
- 基础图表与主题摘要显示正常
- `/history` 支持筛选、列表和详情回看
- 手机端无横向滚动，底部导航可用
- PWA 可安装，离线可打开壳层
- 不出现完整受保护历史正文被默认离线持久化
- 全量测试、类型检查、构建通过
