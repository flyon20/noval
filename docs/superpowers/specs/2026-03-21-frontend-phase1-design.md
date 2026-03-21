# 前端第一阶段设计

## 目标

在不扩散到完整前端大版本的前提下，先落地一个可真实联调的前端基础盘，覆盖以下范围：

- 初始化 `frontend/` 下的 Vue 3 工程与基础构建配置。
- 完成与后端真实实现对齐的 JWT 单 token 登录鉴权、会话恢复、`401 -> refresh -> retry once` 流程。
- 完成首版全局布局与路由守卫。
- 完成 `/login` 与 `/rank` 两个业务页面。
- 在扫榜页内提供书籍详情查看与抓章入口，为第二阶段分析页打通跳转上下文。

## 范围边界

本阶段实现：

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `POST /api/crawler/rank`
- `GET /api/crawler/book/{id}`
- `POST /api/crawler/chapters`

本阶段不实现：

- `/analysis` 页面与流式渲染体验
- `/history`、`/trend`、`/config/prompt`、`/config/system`
- 图表、markdown 富文本分析结果、SSE UI 组件

说明：

- 后端当前已经存在分析流式接口，但前端第一阶段先不消费，避免初始化期范围失控。
- 页面和数据流会为第二阶段预留接口层与路由扩展位，不提前实现具体业务 UI。

## 现状约束

- 前端工程当前尚未初始化，`frontend/` 下只有占位 README。
- 统一接口响应为 `ApiResponse<T>`，包含 `code / message / data / timestamp / traceId`。
- 鉴权是单 token JWT 模式，只返回 `accessToken / tokenType / expiresIn`，没有 `refreshToken`。
- 角色来源于 JWT claims 中的 `roles`，前端需要自行解码构造本地 `AuthSession`。
- `logout` 需要同时带 `Authorization: Bearer <token>` 和请求体 `{ token }`。
- 业务平台 V1 固定为 `fanqie`。

## 方案对比

### 方案 A：先打基础盘，再接分析页

内容：

- 初始化工程、鉴权、布局、登录页、扫榜页、抓章入口。
- 第二阶段再接分析页和流式体验。

优点：

- 变更面小，容易联调和验收。
- 可以先把最容易出问题的鉴权、会话、接口封装打稳。
- 后续分析页接入时不会反复重构基础设施。

缺点：

- 第一阶段没有 AI 分析结果展示。

### 方案 B：第一阶段把分析页也一并做完

优点：

- 用户更快看到完整业务闭环。

缺点：

- 需要同时处理路由、鉴权、扫榜、抓章、分析、流式和回退逻辑。
- 初始 diff 会明显放大，联调定位成本更高。

### 结论

采用方案 A。先把“能登录、能扫榜、能看详情、能抓章并带参数进入分析上下文”的最小闭环做成稳定版本。

## 技术选型

- 框架：Vue 3 + TypeScript + Vite
- 路由：Vue Router
- 状态管理：Pinia
- HTTP：Axios
- UI：Element Plus
- 测试：Vitest + Vue Test Utils
- 样式：SCSS + CSS Variables

选择理由：

- 与已有文档约定一致。
- Element Plus 适合中后台信息密度和表单交互。
- Vue 3 生态足够轻量，便于后续拆分分析页和配置页。

## 视觉与交互基线

整体风格不做常见“蓝白通用后台”，而采用偏内容控制台的方向：

- 主背景使用暖灰到浅米色的纵向渐变，弱化工具感，贴近“文本分析工作台”。
- 主色使用深墨绿，强调色使用琥珀橙，错误态用偏砖红，保证信息密度高时仍能稳定分层。
- 标题字体优先使用偏书卷气的中文 serif fallback，正文保持清晰的 sans-serif，形成“编辑台 + 数据台”的组合气质。
- 卡片使用中低强度阴影和较大圆角，不做重玻璃或过度拟物。
- 登录页采用双栏结构：左侧品牌说明，右侧表单卡片；移动端退化为单栏。
- 扫榜页采用“过滤区 + 榜单表格 + 详情侧板/章节抽屉”的工作流布局。

## 信息架构

### 路由

- `/login`
- `/rank`

预留但暂不实现：

- `/analysis`
- `/history`
- `/trend`
- `/config/prompt`
- `/config/system`

### 一级导航

本阶段侧边栏只展示：

- 扫榜台 `/rank`

顶部区域固定展示：

- 当前登录用户名
- 角色标签
- 登出按钮

## 核心模块拆分

### 1. App Shell

职责：

- 承载应用主题变量、全局背景、主布局骨架。
- 登录态进入后显示侧边导航与顶部操作区。

边界：

- 不耦合具体业务数据请求。

### 2. Auth 模块

职责：

- 登录、登出、刷新 token。
- 解析 JWT claims，恢复 `AuthSession`。
- 暴露 `isAuthenticated`、`hasRole`、`refreshIfNeeded` 等能力。

边界：

- 只管理会话和权限，不承担页面跳转逻辑之外的业务状态。

### 3. API 基础层

职责：

- 创建统一 axios 实例。
- 注入 `Authorization`。
- 处理统一错误对象。
- 在收到 `401` 时执行单次 refresh 和原请求重试。

边界：

- 不写页面提示文案，只返回结构化错误供页面消费。

### 4. Rank 业务模块

职责：

- 加载榜单、查看书籍详情、抓取章节。
- 维护选中书籍和抓章参数。
- 将后端返回的数据转为页面展示所需的轻量 view model。

边界：

- 不直接执行分析接口。
- 抓章成功后只负责把上下文保存并跳转到预留分析路由。

## 鉴权设计

### LocalStorage 键名

- `noval.access_token`
- `noval.token_type`
- `noval.token_expire_at`

### AuthSession 生成

登录或刷新成功后：

1. 读取 `TokenResponse`
2. 解码 JWT payload
3. 解析 `uid / username / roles / exp`
4. 计算 `expireAt`
5. 持久化 token 与 session

### 刷新策略

- 普通请求收到 `401` 时，仅允许自动 refresh 一次。
- refresh 请求体为 `{ token: currentToken }`。
- refresh 成功后覆写本地 token，再自动重放原请求一次。
- refresh 失败则清空登录态并跳转 `/login`。

### 路由守卫

- 未登录访问受保护路由时跳转 `/login`。
- 已登录访问 `/login` 时跳转 `/rank`。
- 第二阶段起对 `/config/system` 增加 `ADMIN` 守卫；本阶段先保留角色工具函数。

## 页面设计

### `/login`

目标：

- 快速登录并恢复会话。
- 明确反馈账号密码错误、限流与通用异常。

关键状态：

- 初始空态
- 提交中
- 登录失败

关键交互：

- 用户名与密码均为必填。
- 提交中禁用按钮并展示 loading。
- 成功后跳转 `/rank`。
- 失败时优先展示后端 `message`，同时保留 `traceId` 便于排查。

### `/rank`

目标：

- 选择榜单分类并查看榜单数据。
- 查看单本书详情。
- 抓取章节预览并携带分析上下文进入下一页。

布局：

- 顶部为筛选区：平台、分类、抓章数、查询按钮。
- 中部为榜单表格，字段至少包含排名、书名、作者、简介摘要。
- 右侧或抽屉展示书籍详情。
- 章节预览使用底部抽屉或弹层展示，避免主表格跳动。

关键交互：

- `platform` 固定 `fanqie`，只做只读展示或隐藏。
- `category` 默认 `male-hot-a`。
- `chapterCount` 仅提供 `1 / 3 / 5 / 10`。
- 点击“详情”时请求 `GET /api/crawler/book/{id}`。
- 点击“抓章”时请求 `POST /api/crawler/chapters`。
- 抓章成功后提供“进入分析页”按钮，并把 `bookId/platform/chapterCount` 写入路由 query 或临时 store。

## 错误处理

- 所有页面错误提示都优先使用 HTTP Status 判定。
- 对 `400` 展示表单内或页面内错误。
- 对 `401` 由基础层统一接管刷新。
- 对 `403` 展示无权限态。
- 对 `429` 展示“请求过于频繁，请稍后再试”。
- 对 `500` 展示通用错误和 `traceId`。
- 列表、详情、抓章三个请求分别维护 loading 和 error，避免一个失败拖垮整个页面。

## 测试策略

先写失败测试，再写实现。第一阶段至少覆盖：

- JWT claims 解析与角色拆分。
- token 存储与会话恢复。
- `401 -> refresh -> retry once` 成功链路。
- refresh 失败时清理会话。
- 登录页提交成功与错误展示。
- 路由守卫对未登录用户的拦截。
- 扫榜页表单触发榜单请求。
- 详情抽屉与抓章抽屉的基本展示。

## 验收标准

- `frontend/` 下可以独立安装依赖并启动开发环境。
- 登录成功后能进入 `/rank` 并恢复会话。
- 刷新浏览器后登录态能恢复。
- token 失效时能自动 refresh 并重试一次原请求。
- `/rank` 能成功查询榜单、查看详情、抓取章节。
- 退出登录后受保护路由无法继续访问。
- 第一阶段代码结构可直接承接第二阶段分析页与流式模块。
