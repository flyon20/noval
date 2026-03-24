# 前端第二阶段分析页设计

## 目标

在第一阶段已完成的登录鉴权、工作台布局、扫榜页和抓章入口基础上，落地真正可用的 `/analysis` 单书分析页，形成“扫榜 -> 抓章 -> 分析”的前端闭环。

本阶段只覆盖单书分析页，不把趋势页、历史页和配置页并入同一轮实现。

## 范围边界

本阶段实现：

- `/analysis` 页面正式替换占位页
- 三种单书分析模式：
  - `deconstruct`
  - `structure`
  - `plot`
- 真 SSE 协议流优先
- 阻塞接口兜底
- 重新生成与 `forceReanalyze=true`
- 从扫榜页带参数进入分析页
- 分析结果卡片、流式态、完成态、错误态

本阶段不实现：

- `/trend`
- `/history`
- `/config/prompt`
- `/config/system`
- 趋势图表和多页结果总览

## 后端对齐基线

### 已落地接口

- `POST /api/analysis/deconstruct`
- `POST /api/analysis/structure`
- `POST /api/analysis/plot`
- `POST /api/analysis/deconstruct/stream`
- `POST /api/analysis/structure/stream`
- `POST /api/analysis/plot/stream`

### 请求体

与后端 [AnalysisRequest.java](/D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/dto/AnalysisRequest.java) 对齐：

```ts
interface AnalysisRequest {
  platform: 'fanqie';
  bookId: number;
  chapterCount: number; // 1-10
  forceReanalyze?: boolean;
}
```

### 响应体

与后端 [AnalysisResultVO.java](/D:/Git/agent/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/vo/AnalysisResultVO.java) 对齐：

```ts
interface AnalysisResult {
  id: number;
  bookId: number;
  analysisType: 'deconstruct' | 'structure' | 'plot';
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  tokenUsed: number;
}
```

### SSE 真实特征说明

后端当前已提供 `text/event-stream` 接口，但它不是模型 token 级原生实时流，而是：

1. 先发 `start`
2. 后端完成分析后按固定片段大小切成多段 `delta`
3. 最后发 `done`

因此前端可以提供主流 AI 风格的“边生成边显示”体验，但要在文案和实现上明确这是“服务端分段流”，不是模型逐 token 直推。

## 方案对比

### 方案 A：单工作台，多分析模式共用一套流式引擎

内容：

- `/analysis` 一个页面承载三种模式
- 模式切换使用标签页或分段控制
- 共用同一套 SSE 解析、状态机、错误处理、结果卡片

优点：

- 从扫榜页跳转后体验最顺
- 复用度最高
- 第二阶段范围集中，不会把很多重复 UI 做散

缺点：

- 页面状态比拆页版稍复杂

### 方案 B：三种分析模式拆成三个子页面

优点：

- 每页职责最直观

缺点：

- 结果卡片、SSE、空态、错误态几乎都会重复
- 模式切换体验差
- 后续维护成本更高

### 方案 C：单页但只做阻塞接口，流式体验延期

优点：

- 改动最保守

缺点：

- 明显背离已有后端能力
- 用户体验与产品目标不匹配

## 结论

采用方案 A：一个 `/analysis` 工作台承载三种单书分析模式，共用一套流式分析引擎，并保留阻塞接口兜底。

## 页面设计

### 页面入口

`/analysis` 通过 query 或后续共享 store 接收：

```ts
{
  bookId: string;
  platform: 'fanqie';
  chapterCount: '1' | '3' | '5' | '10';
}
```

本阶段优先沿用第一阶段已打通的 query 方式，不额外引入复杂跨页会话状态。

### 页面结构

#### 1. 上下文条

展示：

- 书名
- 作者
- `bookId`
- 平台
- 抓章数
- 当前模式

说明：

- 书名和作者通过 `crawlerApi.getBookDetail` 补拉
- 不依赖扫榜页本地内存状态

#### 2. 模式切换区

固定三种模式：

- 拆文 `deconstruct`
- 结构 `structure`
- 情节 `plot`

行为：

- 初次进入页面后默认自动执行 `deconstruct`
- 切换模式时中止当前任务并发起新任务
- 已完成结果在当前会话内可按模式缓存，减少来回切换时的重复等待

#### 3. 结果工作区

流式态：

- 顶部显示“正在分析”
- 主区域显示渐进式文本
- 底部带闪烁光标

完成态：

- 切换为稳定 markdown 富文本结果
- 底部显示 `traceId / modelName / tokenUsed`

异常态：

- 显示错误说明
- 提供“重新生成”

#### 4. 操作区

固定提供：

- `停止生成`
- `重新生成`
- `复制结果`

交互规则：

- `停止生成` 终止当前 SSE 或阻塞兜底过程
- `重新生成` 带 `forceReanalyze=true`
- `复制结果` 复制当前稳定结果文本，不复制中间态状态文案

## 视觉方向

第二阶段继续沿用第一阶段的暖灰纸面和深墨绿基调，但让分析页更像“编辑台”而不是列表页：

- 上下文条更紧凑，强调书籍与任务信息
- 结果卡片作为页面视觉中心，宽度优先照顾长文本阅读
- 流式输出阶段使用轻微呼吸感光标和渐进 reveal，而不是花哨动画
- 对模式切换和按钮交互加入轻量过渡，避免全页突然跳变

## 数据流设计

### 1. 页面初始化

1. 读取路由 query
2. 校验 `bookId / platform / chapterCount`
3. 参数缺失时显示空态，并提供返回扫榜页入口
4. 参数有效时请求 `getBookDetail`
5. 自动执行默认模式 `deconstruct`

### 2. 分析发起

1. 构造 `AnalysisRequest`
2. 若 token 将在 60 秒内过期，先 refresh
3. 优先走对应的 `/stream` 接口
4. 若流式前置失败，则自动回退到阻塞接口

### 3. 模式切换

1. 若当前正在流式输出，先 abort
2. 切换当前模式
3. 若会话内已有该模式稳定结果，可优先本地回显
4. 然后是否自动重新发起：
   - 本阶段建议自动重新发起，保持“切换模式 = 切换分析任务”的直觉

### 4. 重新生成

1. 保留当前模式与上下文
2. 发送 `forceReanalyze=true`
3. 重新走“流式优先 / 阻塞兜底”流程

## 流式协议处理

### 事件格式

前端按以下类型处理：

```ts
type StreamEventType = 'start' | 'delta' | 'done' | 'error';
```

### 调用方式

固定使用：

- `fetch`
- `ReadableStream`
- `AbortController`

不使用 `EventSource`，因为请求必须携带 `Authorization` 头。

### 状态机

```ts
type AnalysisRunPhase =
  | 'idle'
  | 'preparing'
  | 'streaming'
  | 'fallback-blocking'
  | 'done'
  | 'error'
  | 'aborted';
```

### 流式失败与兜底规则

1. 若开流前 refresh 成功，则使用新 token 发起流式请求
2. 若流式请求返回 `401`，仅允许 refresh 并重开一次
3. 若返回 `404/405/501`，直接回退阻塞接口
4. 若 `Content-Type` 不是 `text/event-stream`，直接回退阻塞接口
5. 若在收到任何 `start/delta` 之前解析失败，直接回退阻塞接口
6. 若已经收到部分 `delta` 后网络中断：
   - 保留已生成文本
   - 标记为“连接中断，可重试”
   - 不自动切阻塞兜底，避免重复内容

## 阻塞兜底设计

当流式不可用时：

1. 调用对应阻塞接口
2. 拿到完整 `AnalysisResult`
3. 前端按分段节流方式模拟打字机渲染
4. 页面提示“非实时输出”
5. 最终以阻塞接口返回的完整结果为准

## 模块拆分

### API 层

新增：

- `src/types/analysis.ts`
- `src/api/analysis.ts`

职责：

- 定义单书分析 request/response 类型
- 暴露阻塞调用方法
- 暴露流式调用方法

### 流式基础层

新增：

- `src/lib/analysis-stream.ts`

职责：

- 解析 SSE chunk
- 维护 `AbortController`
- 管理 start/delta/done/error 回调
- 封装流式与阻塞兜底切换

### 页面状态层

新增：

- `src/composables/useAnalysisRun.ts`

职责：

- 管理当前模式、结果缓存、运行阶段、流式文本、错误信息
- 对页面提供简单接口：
  - `runAnalysis`
  - `stopAnalysis`
  - `rerunAnalysis`
  - `switchMode`

### 组件层

新增：

- `src/components/analysis/AnalysisContextBar.vue`
- `src/components/analysis/AnalysisModeTabs.vue`
- `src/components/analysis/AnalysisResultCard.vue`
- `src/components/analysis/AnalysisToolbar.vue`
- `src/components/analysis/AnalysisEmptyState.vue`

### 页面层

替换：

- [AnalysisPlaceholderView.vue](/D:/Git/agent/noval/frontend/src/views/analysis/AnalysisPlaceholderView.vue)

为真实分析页：

- `src/views/analysis/AnalysisView.vue`

### 路由层

更新：

- [router/index.ts](/D:/Git/agent/noval/frontend/src/router/index.ts)

使 `/analysis` 指向真实页面。

## 错误处理

- query 缺失：空态 + 返回扫榜页按钮
- `400`：上下文参数错误或章节缺失，页内错误卡片展示
- `401`：统一 refresh，一次失败后回登录页
- `403`：权限不足态
- `404`：书籍不存在或接口不可用
- `429`：分析过于频繁，请稍后再试
- `500`：通用错误 + `traceId`

所有错误展示都要保留 `traceId`，便于后端排查。

## 测试策略

先写失败测试，再写实现。第二阶段至少覆盖：

- SSE 事件解析
- `start -> delta -> done` 成功链路
- `error` 事件处理
- 流式请求 `401` 后只 refresh 并重开一次
- 流式首包前失败时回退阻塞接口
- 中途断流时保留部分文本
- `forceReanalyze=true` 重新生成
- 路由 query 缺失时显示空态
- 从 query 进入页面后自动执行默认分析
- 切换分析模式时会中止旧任务并启动新任务

## 验收标准

- 从 [RankView.vue](/D:/Git/agent/noval/frontend/src/views/rank/RankView.vue) 点击“进入分析页”后，能进入真实分析工作台
- 页面自动执行默认 `deconstruct`
- 三种分析模式都可切换并正常出结果
- SSE 正常时，页面可按 `start -> delta -> done` 渐进展示
- SSE 不可用时，能自动回退阻塞接口
- 重新生成会带 `forceReanalyze=true`
- 停止生成能终止当前任务
- 完成态显示 `traceId / modelName / tokenUsed`
- 所有第二阶段新增逻辑通过测试、类型检查和构建验证
