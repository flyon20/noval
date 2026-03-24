# Noval Frontend

当前前端基于 `Vue 3 + Vite + TypeScript + Element Plus`，已经接入登录、扫榜、单书分析、趋势分析、历史回看，以及基础 PWA 壳层。

## 环境要求

- Node.js：`20 LTS`
- Node 管理：建议使用 `nvm`
- 后端默认地址：`http://localhost:8080`

## 启动方式

```bash
nvm use 20
npm install
cp .env.example .env
npm run dev
```

Windows PowerShell：

```powershell
nvm use 20
npm install
Copy-Item .env.example .env
npm run dev
```

## 常用命令

```bash
npm run test -- --run
npm run type-check
npm run build
```

## 当前已实现

- `/login`：登录页
- `/rank`：扫榜页，支持榜单、书籍详情、章节预览
- `/analysis`：单书分析页，支持 `deconstruct / structure / plot`
- `/trend`：趋势分析页，支持流式优先、图表快照、标签云、趋势摘要
- `/history`：历史回看页，支持过滤、列表、详情、移动端抽屉
- JWT 单 token 会话恢复
- `401 -> refresh -> retry once`
- `Authorization: Bearer <token>`
- 路由守卫与菜单显隐
- PWA manifest、Service Worker 注册、基础离线壳层

## 流式与回退

- 单书分析和趋势分析都优先走 SSE 协议流
- 当前使用 `fetch + ReadableStream`，不使用原生 `EventSource`
- 流式不可用时自动回退到阻塞接口
- 历史回看页不触发重新生成

## PWA 边界

允许缓存：

- HTML / JS / CSS / 图标等静态壳层资源
- `GET /api/data/visual`

默认不缓存：

- `POST /api/analysis/*/stream`
- `GET /api/analysis/trend`
- `GET /api/data/history` 的完整正文
- 用户生成的完整 AI 长文本结果

## 依赖版本基线

- `vite@^7.3.1`
- `@vitejs/plugin-vue@^6.0.5`
- `vite-plugin-pwa@^1.2.0`
- `echarts@^6.0.0`
- `vue-echarts@^8.0.1`

## 相关文档

- [前端接口设计-v1.md](/D:/Git/agent/noval/docs/前端接口设计-v1.md)
- [2026-03-21-frontend-phase1-design.md](/D:/Git/agent/noval/docs/superpowers/specs/2026-03-21-frontend-phase1-design.md)
- [2026-03-21-frontend-phase2-analysis-design.md](/D:/Git/agent/noval/docs/superpowers/specs/2026-03-21-frontend-phase2-analysis-design.md)
- [2026-03-21-frontend-phase3-trend-history-pwa-design.md](/D:/Git/agent/noval/docs/superpowers/specs/2026-03-21-frontend-phase3-trend-history-pwa-design.md)
