# Frontend Phase 3 Trend / History / PWA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `/trend` and `/history` with mobile-first layouts, trend streaming + blocking fallback, charted visual data, and a minimal secure PWA shell.

**Architecture:** Extend the current Vue 3 frontend by reusing the Phase 2 stream runtime and markdown renderer, while adding dedicated type/api/composable layers for trend and history data. Keep trend, history, layout navigation, and PWA shell as separate modules so the desktop/mobile behavior stays maintainable and the secure caching boundary remains explicit.

**Tech Stack:** Vue 3, TypeScript, Vite, Vue Router, Pinia, Axios, Element Plus, Apache ECharts, vue-echarts, vite-plugin-pwa, Vitest, Vue Test Utils

---

## File Structure

### New files

- `frontend/src/types/trend.ts`
  Responsibility: trend request/response types aligned to `TrendAnalysisRequest` and `TrendAnalysisVO`.
- `frontend/src/types/data.ts`
  Responsibility: `VisualDataVO` and `AnalysisHistoryItemVO` front-end types.
- `frontend/src/api/data.ts`
  Responsibility: `getHistory` and `getVisual` API wrappers.
- `frontend/src/composables/useTrendRun.ts`
  Responsibility: single-task trend state machine with stream-first + fallback behavior.
- `frontend/src/components/trend/TrendContextBar.vue`
  Responsibility: platform/category/context/header card.
- `frontend/src/components/trend/TrendSummaryCards.vue`
  Responsibility: summary cards for source snapshot count and comparison summary.
- `frontend/src/components/trend/TrendChartCard.vue`
  Responsibility: reusable ECharts card shell for pie/line/bar charts.
- `frontend/src/components/trend/TrendSnapshotTable.vue`
  Responsibility: latest snapshot table.
- `frontend/src/components/trend/TrendTagCloud.vue`
  Responsibility: CSS tag cloud for theme words.
- `frontend/src/components/trend/TrendComparisonList.vue`
  Responsibility: comparison summary list.
- `frontend/src/views/trend/TrendView.vue`
  Responsibility: trend page orchestration, desktop/mobile responsive layout.
- `frontend/src/components/history/HistoryFilterBar.vue`
  Responsibility: history filter form and submit/reset actions.
- `frontend/src/components/history/HistoryListPanel.vue`
  Responsibility: history list cards/table and item selection.
- `frontend/src/components/history/HistoryDetailPanel.vue`
  Responsibility: history detail markdown/result-json view.
- `frontend/src/views/history/HistoryView.vue`
  Responsibility: history page orchestration and mobile drawer behavior.
- `frontend/src/components/layout/AppBottomNav.vue`
  Responsibility: mobile primary navigation.
- `frontend/src/constants/navigation.ts`
  Responsibility: single source of truth for top-level route navigation.
- `frontend/src/pwa/register-sw.ts`
  Responsibility: service worker registration bootstrap.
- `frontend/src/views/trend/__tests__/TrendView.spec.ts`
  Responsibility: trend page init, stream/fallback, visual rendering tests.
- `frontend/src/views/history/__tests__/HistoryView.spec.ts`
  Responsibility: history page list/filter/detail tests.
- `frontend/src/components/layout/__tests__/AppBottomNav.spec.ts`
  Responsibility: mobile navigation render and route link tests.
- `frontend/src/pwa/__tests__/register-sw.spec.ts`
  Responsibility: service worker registration smoke test.

### Modified files

- `frontend/package.json`
  Responsibility: add chart and PWA dependencies.
- `frontend/vite.config.ts`
  Responsibility: register Vue plugin plus PWA plugin config.
- `frontend/src/main.ts`
  Responsibility: register the service worker bootstrap.
- `frontend/src/api/analysis.ts`
  Responsibility: add `getTrend` and `streamTrend`.
- `frontend/src/lib/analysis-stream.ts`
  Responsibility: make stream runner reusable for trend done payloads if needed.
- `frontend/src/types/analysis.ts`
  Responsibility: extend shared stream metadata only if trend reuse needs it.
- `frontend/src/router/index.ts`
  Responsibility: add `/trend` and `/history`.
- `frontend/src/components/layout/AppSidebar.vue`
  Responsibility: add desktop nav links and clean existing copy.
- `frontend/src/layouts/AppShell.vue`
  Responsibility: mount bottom nav in mobile layout.
- `frontend/src/styles/base.scss`
  Responsibility: add responsive shell spacing if mobile bottom nav needs reserved space.
- `frontend/README.md`
  Responsibility: document Phase 3 pages, charts, and PWA commands/notes.

---

### Task 1: Add trend/data types and API wrappers

**Files:**
- Create: `frontend/src/types/trend.ts`
- Create: `frontend/src/types/data.ts`
- Create: `frontend/src/api/data.ts`
- Modify: `frontend/src/api/analysis.ts`
- Test: `frontend/src/views/trend/__tests__/TrendView.spec.ts`
- Test: `frontend/src/views/history/__tests__/HistoryView.spec.ts`

- [ ] **Step 1: Write failing view tests that expect trend/history API calls**

Add test cases that assert:

- `/trend` calls `analysisApi.streamTrend` with `{ platform: 'fanqie', category: 'male-hot-a' }`
- `/trend` calls `dataApi.getVisual('fanqie')`
- `/history` calls `dataApi.getHistory({ platform: 'fanqie', limit: 20 })`

- [ ] **Step 2: Run the new tests to verify RED**

Run: `npm run test -- TrendView.spec HistoryView.spec`
Expected: FAIL because trend/history types, API wrappers, and views do not exist yet

- [ ] **Step 3: Implement minimal types and API wrappers**

Create:

```ts
// frontend/src/types/trend.ts
export interface TrendRequest {
  platform: 'fanqie';
  category?: string;
}

export interface TrendAnalysisResult {
  analysisType: 'theme';
  platform: 'fanqie';
  category?: string;
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  sourceSnapshotCount: number;
  traceId?: string;
}
```

```ts
// frontend/src/types/data.ts
export interface AnalysisHistoryQuery {
  platform?: 'fanqie';
  bookId?: number;
  analysisType?: 'deconstruct' | 'structure' | 'plot' | 'theme';
  limit?: number;
}
```

Extend `frontend/src/api/analysis.ts`:

```ts
analysisApi.getTrend(payload)
analysisApi.streamTrend(payload, callbacks)
```

Create `frontend/src/api/data.ts`:

```ts
dataApi.getVisual(platform?)
dataApi.getHistory(query?)
```

- [ ] **Step 4: Run the new tests to verify GREEN on API expectations**

Run: `npm run test -- TrendView.spec HistoryView.spec`
Expected: still FAIL on page/UI assertions, but API wrapper imports resolve and request mocks are reachable

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/trend.ts frontend/src/types/data.ts frontend/src/api/data.ts frontend/src/api/analysis.ts
git commit -m "feat: add trend and data api wrappers"
```

### Task 2: Reuse and extend the stream runtime for trend analysis

**Files:**
- Modify: `frontend/src/lib/analysis-stream.ts`
- Modify: `frontend/src/lib/__tests__/analysis-stream.spec.ts`
- Create: `frontend/src/composables/useTrendRun.ts`

- [ ] **Step 1: Write the failing trend stream tests**

Add tests for:

- trend `start -> delta -> done`
- trend blocking fallback before first delta
- trend partial text retained after interrupted stream

Use a `TrendAnalysisResult` payload in `done`.

- [ ] **Step 2: Run the stream tests to verify RED**

Run: `npm run test -- analysis-stream.spec`
Expected: FAIL for trend-specific done payload handling or missing trend composable

- [ ] **Step 3: Implement minimal reusable trend stream support**

Update `frontend/src/lib/analysis-stream.ts` to support a typed done payload for trend results if the current implementation is analysis-result-only.

Create `frontend/src/composables/useTrendRun.ts` with state:

```ts
type TrendRunPhase =
  | 'idle'
  | 'preparing'
  | 'streaming'
  | 'fallback-blocking'
  | 'done'
  | 'error'
  | 'aborted';
```

Expose:

- `runTrend(category?)`
- `stopTrend()`
- `rerunTrend()`
- `copyResult()`

- [ ] **Step 4: Run the stream tests to verify GREEN**

Run: `npm run test -- analysis-stream.spec`
Expected: PASS with trend cases included

- [ ] **Step 5: Add a focused composable test if needed and run it**

Run: `npm run test -- TrendView.spec`
Expected: composable-driven trend state transitions are now possible

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/analysis-stream.ts frontend/src/lib/__tests__/analysis-stream.spec.ts frontend/src/composables/useTrendRun.ts
git commit -m "feat: add reusable trend stream runtime"
```

### Task 3: Build reusable trend visualization components

**Files:**
- Create: `frontend/src/components/trend/TrendContextBar.vue`
- Create: `frontend/src/components/trend/TrendSummaryCards.vue`
- Create: `frontend/src/components/trend/TrendChartCard.vue`
- Create: `frontend/src/components/trend/TrendSnapshotTable.vue`
- Create: `frontend/src/components/trend/TrendTagCloud.vue`
- Create: `frontend/src/components/trend/TrendComparisonList.vue`
- Test: `frontend/src/views/trend/__tests__/TrendView.spec.ts`
- Modify: `frontend/package.json`

- [ ] **Step 1: Add chart dependencies**

Run: `npm install echarts vue-echarts`
Expected: install succeeds without dependency conflicts

- [ ] **Step 2: Write failing trend component/view assertions**

Cover:

- summary cards render `sourceSnapshotCount` and `comparisonSummary`
- chart card containers render three chart areas
- tag cloud renders theme chips
- snapshot table renders latest snapshot rows

- [ ] **Step 3: Run the trend view test to verify RED**

Run: `npm run test -- TrendView.spec`
Expected: FAIL because the trend components do not exist

- [ ] **Step 4: Implement minimal trend components**

Rules:

- `TrendChartCard.vue` is a generic wrapper around `vue-echarts`
- `TrendTagCloud.vue` uses responsive chips sized by `value`; do not add a word-cloud extension package
- `TrendSnapshotTable.vue` uses Element Plus table or card list depending on viewport
- `TrendSummaryCards.vue` highlights summary and snapshot count without duplicating chart data

- [ ] **Step 5: Run the trend view test to verify GREEN for component rendering**

Run: `npm run test -- TrendView.spec`
Expected: component render assertions pass; page orchestration may still fail

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/src/components/trend
git commit -m "feat: add trend visualization components"
```

### Task 4: Build the `/trend` page with mobile-first layout and stream-first behavior

**Files:**
- Create: `frontend/src/views/trend/TrendView.vue`
- Test: `frontend/src/views/trend/__tests__/TrendView.spec.ts`
- Modify: `frontend/src/router/index.ts`

- [ ] **Step 1: Expand the failing trend page test**

Cover:

- initial load calls `getVisual` and starts `streamTrend`
- category switching aborts old stream and reruns
- fallback to blocking trend request
- error state preserves partial text after disconnect
- mobile layout shows result before charts

- [ ] **Step 2: Run the trend page test to verify RED**

Run: `npm run test -- TrendView.spec`
Expected: FAIL because `TrendView.vue` does not exist

- [ ] **Step 3: Implement minimal `TrendView.vue`**

Requirements:

- default category `male-hot-a`
- parallel load of visual data and trend analysis
- use `useTrendRun`
- reuse Phase 2 markdown rendering style for final result
- keep result above charts on narrow screens
- show “offline / can retry” messaging when appropriate

- [ ] **Step 4: Wire the route**

Add:

- `/trend`

in `frontend/src/router/index.ts`

- [ ] **Step 5: Run the trend page test to verify GREEN**

Run: `npm run test -- TrendView.spec`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/trend frontend/src/router/index.ts
git commit -m "feat: add trend analysis page"
```

### Task 5: Build the `/history` page with filter, list, and detail replay

**Files:**
- Create: `frontend/src/components/history/HistoryFilterBar.vue`
- Create: `frontend/src/components/history/HistoryListPanel.vue`
- Create: `frontend/src/components/history/HistoryDetailPanel.vue`
- Create: `frontend/src/views/history/HistoryView.vue`
- Test: `frontend/src/views/history/__tests__/HistoryView.spec.ts`

- [ ] **Step 1: Write the failing history page tests**

Cover:

- page loads with `{ platform: 'fanqie', limit: 20 }`
- clicking a list item updates detail panel
- filtering by `analysisType` reruns the query
- mobile view opens detail in drawer mode or equivalent mobile container

- [ ] **Step 2: Run the history page test to verify RED**

Run: `npm run test -- HistoryView.spec`
Expected: FAIL because history components/page do not exist

- [ ] **Step 3: Implement minimal history components and page**

Rules:

- default-select the first result when data exists
- do not regenerate analysis from history
- detail panel must render markdown safely
- detail metadata must show `createdAt`, `modelName`, `chapterCount`, `analysisType`

- [ ] **Step 4: Add the `/history` route**

Modify `frontend/src/router/index.ts` to add:

- `/history`

- [ ] **Step 5: Run the history page test to verify GREEN**

Run: `npm run test -- HistoryView.spec`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/history frontend/src/views/history frontend/src/router/index.ts
git commit -m "feat: add history replay page"
```

### Task 6: Add desktop/mobile navigation and PWA shell

**Files:**
- Create: `frontend/src/components/layout/AppBottomNav.vue`
- Create: `frontend/src/constants/navigation.ts`
- Create: `frontend/src/pwa/register-sw.ts`
- Create: `frontend/src/pwa/__tests__/register-sw.spec.ts`
- Create: `frontend/src/components/layout/__tests__/AppBottomNav.spec.ts`
- Modify: `frontend/src/components/layout/AppSidebar.vue`
- Modify: `frontend/src/layouts/AppShell.vue`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/styles/base.scss`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/package.json`

- [ ] **Step 1: Add the failing navigation and PWA tests**

Cover:

- desktop sidebar shows `rank / analysis / trend / history`
- mobile bottom nav renders the same primary entries
- service worker registration bootstrap is called

- [ ] **Step 2: Run the tests to verify RED**

Run: `npm run test -- AppBottomNav.spec register-sw.spec`
Expected: FAIL because the new files and PWA bootstrap do not exist

- [ ] **Step 3: Add the PWA dependency**

Run: `npm install -D vite-plugin-pwa`
Expected: install succeeds

- [ ] **Step 4: Implement minimal shared navigation**

Create `frontend/src/constants/navigation.ts`:

```ts
export const PRIMARY_NAV_ITEMS = [
  { to: '/rank', label: '扫榜' },
  { to: '/analysis', label: '单书分析' },
  { to: '/trend', label: '趋势分析' },
  { to: '/history', label: '历史回看' },
];
```

Use it in both `AppSidebar.vue` and `AppBottomNav.vue`.

- [ ] **Step 5: Implement minimal PWA shell**

Requirements:

- register `vite-plugin-pwa` in `frontend/vite.config.ts`
- generate a manifest with app name, theme color, icons, and standalone display mode
- register the service worker from `frontend/src/main.ts`
- do not add runtime caching for trend/history full result payloads
- only cache shell assets and the `GET /api/data/visual` route

- [ ] **Step 6: Run the tests to verify GREEN**

Run: `npm run test -- AppBottomNav.spec register-sw.spec`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/layout frontend/src/constants/navigation.ts frontend/src/pwa frontend/src/main.ts frontend/src/styles/base.scss frontend/vite.config.ts frontend/package.json
git commit -m "feat: add mobile navigation and pwa shell"
```

### Task 7: Update docs and run full verification

**Files:**
- Modify: `frontend/README.md`
- Modify: `docs/前端接口设计-v1.md`

- [ ] **Step 1: Update README and interface docs**

Document:

- `/trend`
- `/history`
- PWA installation/offline shell behavior
- chart dependency note
- secure cache boundary for protected data

- [ ] **Step 2: Run full test suite**

Run: `npm run test -- --run`
Expected: PASS

- [ ] **Step 3: Run type check**

Run: `npm run type-check`
Expected: PASS

- [ ] **Step 4: Run production build**

Run: `npm run build`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/README.md docs/前端接口设计-v1.md
git commit -m "docs: update frontend phase 3 docs"
```

