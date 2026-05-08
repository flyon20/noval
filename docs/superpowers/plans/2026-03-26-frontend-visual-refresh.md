# Frontend Visual Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh the frontend visual system into a younger glassmorphism style with dark mode, mobile floating navigation, improved drawers, and cleaner trend visuals without changing APIs or business structure.

**Architecture:** Keep all backend contracts and page flows intact, but move the frontend to a token-driven dual-theme system (`light` / `dark`) and progressively restyle the shell, navigation, cards, drawers, markdown renderer, and trend visualization components. Mobile-specific behavior is handled at the layout/component level with responsive branches instead of route-level divergence.

**Tech Stack:** Vue 3, Vite, TypeScript, SCSS tokens, Element Plus, vue-echarts, vitest, vue-tsc

---

## File Structure

### Global Theme Layer

- Modify: `D:/Git/agent/noval/frontend/src/styles/tokens.scss`
  - Replace the current earthy palette with a younger light theme and a readable dark theme token set.
- Modify: `D:/Git/agent/noval/frontend/src/styles/base.scss`
  - Wire global background, body text, and theme-level markdown/container defaults.
- Modify: `D:/Git/agent/noval/frontend/src/App.vue`
  - Add app-level theme attribute wiring and a simple theme bootstrap.

### Shell / Navigation Layer

- Modify: `D:/Git/agent/noval/frontend/src/components/layout/AppHeader.vue`
  - Make mobile header fixed and visually lighter; remove low-value subtitles.
- Modify: `D:/Git/agent/noval/frontend/src/components/layout/AppBottomNav.vue`
  - Restyle bottom nav into liquid glass floating navigation.
- Modify: `D:/Git/agent/noval/frontend/src/layouts/AppShell.vue`
  - Add top safe spacing and bottom floating-nav spacing so content scrolls under the nav correctly.
- Test: `D:/Git/agent/noval/frontend/src/layouts/__tests__/AppShell.spec.ts`

### Content / Result Layer

- Modify: `D:/Git/agent/noval/frontend/src/lib/markdown.ts`
  - Ensure rendered markdown can inherit theme-aware container classes.
- Modify: `D:/Git/agent/noval/frontend/src/components/analysis/AnalysisResultCard.vue`
  - Restyle markdown/result containers for both light and dark themes.
- Modify: `D:/Git/agent/noval/frontend/src/components/rank/BookDetailDrawer.vue`
  - Keep only meaningful content and apply unified glass drawer style.
- Modify: `D:/Git/agent/noval/frontend/src/components/rank/ChapterPreviewDrawer.vue`
  - Keep the improved action order, desktop side drawer behavior, and glass styling.
- Test:
  - `D:/Git/agent/noval/frontend/src/components/analysis/__tests__/AnalysisResultCard.spec.ts`
  - `D:/Git/agent/noval/frontend/src/components/rank/__tests__/BookDetailDrawer.spec.ts`
  - `D:/Git/agent/noval/frontend/src/components/rank/__tests__/ChapterPreviewDrawer.spec.ts`

### Page-Level Refresh

- Modify: `D:/Git/agent/noval/frontend/src/views/login/LoginView.vue`
  - Refresh the login page colors/surfaces to match the new theme system.
- Modify: `D:/Git/agent/noval/frontend/src/views/rank/RankView.vue`
  - Simplify copy, match new card system, and keep the mobile refresh-flow pagination visually consistent.
- Modify: `D:/Git/agent/noval/frontend/src/views/analysis/AnalysisView.vue`
  - Apply refreshed shell/card spacing and dark-mode-safe reading surfaces.
- Modify: `D:/Git/agent/noval/frontend/src/views/trend/TrendView.vue`
  - Refresh toolbar, result-support panels, mobile theme cards, and chart/table layout.
- Modify: `D:/Git/agent/noval/frontend/src/views/history/HistoryView.vue` (if needed during consistency pass)
  - Only light-touch token-driven style alignment; no flow changes.
- Test:
  - `D:/Git/agent/noval/frontend/src/views/login/__tests__/LoginView.spec.ts`
  - `D:/Git/agent/noval/frontend/src/views/rank/__tests__/RankView.spec.ts`
  - `D:/Git/agent/noval/frontend/src/views/analysis/__tests__/AnalysisView.spec.ts`
  - `D:/Git/agent/noval/frontend/src/views/trend/__tests__/TrendView.spec.ts`

### Trend-Specific Visual Layer

- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendContextBar.vue`
  - Tighten mobile layout and remove verbose helper copy.
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendResultPreview.vue`
  - Keep a readable themed preview and a stable detail drawer.
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendSummaryCards.vue`
  - Shorten helper text and align visual tone.
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendComparisonList.vue`
  - Reduce explanatory copy and keep structure readable in both themes.
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendSnapshotTable.vue`
  - Keep desktop table + mobile cards with refreshed style.
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendTagCloud.vue`
  - Implement a more cloud-like Chinese word cloud layout with younger “ins” colors and stronger weight separation.
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendChartCard.vue`
  - Tighten chart subtitles and keep clean chart shells.
- Test:
  - `D:/Git/agent/noval/frontend/src/components/trend/__tests__/TrendSnapshotTable.spec.ts`
  - `D:/Git/agent/noval/frontend/src/components/trend/__tests__/TrendTagCloud.spec.ts`

---

### Task 1: Add Theme Tokens And App-Level Theme State

**Files:**
- Modify: `D:/Git/agent/noval/frontend/src/styles/tokens.scss`
- Modify: `D:/Git/agent/noval/frontend/src/styles/base.scss`
- Modify: `D:/Git/agent/noval/frontend/src/App.vue`
- Test: `D:/Git/agent/noval/frontend/src/layouts/__tests__/AppShell.spec.ts`

- [ ] **Step 1: Write the failing shell/theme test**

Add a test in `D:/Git/agent/noval/frontend/src/layouts/__tests__/AppShell.spec.ts` that asserts the shell still renders page content correctly after theme-related shell changes.

- [ ] **Step 2: Run the shell test to verify current baseline**

Run: `npm run test -- --run src/layouts/__tests__/AppShell.spec.ts`
Expected: PASS on baseline, then keep this as guard while changing shell/theme internals.

- [ ] **Step 3: Rewrite token system for light/dark themes**

Update `D:/Git/agent/noval/frontend/src/styles/tokens.scss` to:

```scss
:root,
:root[data-theme='light'] {
  --color-bg: ...;
  --color-surface: ...;
  --color-glass: ...;
  --color-text: ...;
  --color-accent: ...;
}

:root[data-theme='dark'] {
  --color-bg: ...;
  --color-surface: ...;
  --color-glass: ...;
  --color-text: ...;
  --color-accent: ...;
}
```

- [ ] **Step 4: Add base theme plumbing**

Update `D:/Git/agent/noval/frontend/src/styles/base.scss` and `D:/Git/agent/noval/frontend/src/App.vue` so the app:

- applies `data-theme`
- defaults safely
- supports future one-click toggle without changing page components again

- [ ] **Step 5: Run shell test and type-check**

Run:

```bash
npm run test -- --run src/layouts/__tests__/AppShell.spec.ts
npm run type-check
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/styles/tokens.scss frontend/src/styles/base.scss frontend/src/App.vue frontend/src/layouts/__tests__/AppShell.spec.ts
git commit -m "feat: add global visual theme system"
```

---

### Task 2: Restyle Mobile Header And Floating Bottom Navigation

**Files:**
- Modify: `D:/Git/agent/noval/frontend/src/components/layout/AppHeader.vue`
- Modify: `D:/Git/agent/noval/frontend/src/components/layout/AppBottomNav.vue`
- Modify: `D:/Git/agent/noval/frontend/src/layouts/AppShell.vue`
- Test: `D:/Git/agent/noval/frontend/src/layouts/__tests__/AppShell.spec.ts`

- [ ] **Step 1: Extend the shell test to cover floating nav/header safety**

Add assertions that page content still renders and the shell still mounts correctly after nav/header refactor.

- [ ] **Step 2: Run the shell test**

Run: `npm run test -- --run src/layouts/__tests__/AppShell.spec.ts`
Expected: PASS before visual refactor

- [ ] **Step 3: Make the mobile header fixed and simplified**

Update `AppHeader.vue`:

- keep only page title
- keep logout always reachable on mobile
- remove subtitle copy
- make mobile header fixed/floating

- [ ] **Step 4: Turn bottom nav into liquid glass**

Update `AppBottomNav.vue` to:

- float above page content
- use blur + transparency + highlight edge
- preserve current navigation targets and active behavior

- [ ] **Step 5: Reserve safe content spacing**

Update `AppShell.vue` so:

- mobile content gets top padding for fixed header
- bottom padding matches floating nav height
- content visibly scrolls underneath the floating nav

- [ ] **Step 6: Run shell test and type-check**

Run:

```bash
npm run test -- --run src/layouts/__tests__/AppShell.spec.ts
npm run type-check
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/layout/AppHeader.vue frontend/src/components/layout/AppBottomNav.vue frontend/src/layouts/AppShell.vue frontend/src/layouts/__tests__/AppShell.spec.ts
git commit -m "feat: refresh mobile shell navigation"
```

---

### Task 3: Make Markdown And Result Cards Theme-Aware

**Files:**
- Modify: `D:/Git/agent/noval/frontend/src/lib/markdown.ts`
- Modify: `D:/Git/agent/noval/frontend/src/components/analysis/AnalysisResultCard.vue`
- Test: `D:/Git/agent/noval/frontend/src/components/analysis/__tests__/AnalysisResultCard.spec.ts`

- [ ] **Step 1: Add/adjust a result-card regression test**

Extend the existing result-card test coverage to ensure markdown/result rendering still works after the theme-aware restyle.

- [ ] **Step 2: Run the result-card test**

Run: `npm run test -- --run src/components/analysis/__tests__/AnalysisResultCard.spec.ts`
Expected: PASS on baseline

- [ ] **Step 3: Add theme-friendly markdown container output**

Update `markdown.ts` so rendered markdown can live inside a consistent themed wrapper class.

- [ ] **Step 4: Restyle the result card**

Update `AnalysisResultCard.vue` so:

- light mode stays readable
- dark mode inverts cleanly
- markdown preview area follows the current theme
- code blocks / lists / headings remain readable

- [ ] **Step 5: Run the result-card test and type-check**

Run:

```bash
npm run test -- --run src/components/analysis/__tests__/AnalysisResultCard.spec.ts
npm run type-check
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/markdown.ts frontend/src/components/analysis/AnalysisResultCard.vue frontend/src/components/analysis/__tests__/AnalysisResultCard.spec.ts
git commit -m "feat: theme markdown and analysis result cards"
```

---

### Task 4: Refresh Rank Drawers And Rank Surface Styling

**Files:**
- Modify: `D:/Git/agent/noval/frontend/src/components/rank/BookDetailDrawer.vue`
- Modify: `D:/Git/agent/noval/frontend/src/components/rank/ChapterPreviewDrawer.vue`
- Modify: `D:/Git/agent/noval/frontend/src/views/rank/RankView.vue`
- Test:
  - `D:/Git/agent/noval/frontend/src/components/rank/__tests__/BookDetailDrawer.spec.ts`
  - `D:/Git/agent/noval/frontend/src/components/rank/__tests__/ChapterPreviewDrawer.spec.ts`
  - `D:/Git/agent/noval/frontend/src/views/rank/__tests__/RankView.spec.ts`

- [ ] **Step 1: Run the existing rank drawer / rank view tests**

Run:

```bash
npm run test -- --run src/components/rank/__tests__/BookDetailDrawer.spec.ts src/components/rank/__tests__/ChapterPreviewDrawer.spec.ts src/views/rank/__tests__/RankView.spec.ts
```

Expected: PASS on baseline

- [ ] **Step 2: Restyle and simplify the book detail drawer**

Update `BookDetailDrawer.vue`:

- remove low-value labels
- keep title / author / intro / link
- apply unified glass visual treatment

- [ ] **Step 3: Refine the chapter drawer presentation**

Update `ChapterPreviewDrawer.vue`:

- preserve desktop side-drawer behavior
- keep action order `重新抓取章节 / 进入分析页 / 关闭`
- use sticky top action area
- improve PC readability and mobile fit

- [ ] **Step 4: Refresh rank page surface styling**

Update `RankView.vue`:

- simplify top copy
- align cards, controls, and mobile refresh flow with the new glass theme
- do not change rank-page data logic

- [ ] **Step 5: Run the rank tests and type-check**

Run:

```bash
npm run test -- --run src/components/rank/__tests__/BookDetailDrawer.spec.ts src/components/rank/__tests__/ChapterPreviewDrawer.spec.ts src/views/rank/__tests__/RankView.spec.ts
npm run type-check
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/rank/BookDetailDrawer.vue frontend/src/components/rank/ChapterPreviewDrawer.vue frontend/src/views/rank/RankView.vue frontend/src/components/rank/__tests__/BookDetailDrawer.spec.ts frontend/src/components/rank/__tests__/ChapterPreviewDrawer.spec.ts frontend/src/views/rank/__tests__/RankView.spec.ts
git commit -m "feat: refresh rank surfaces and drawers"
```

---

### Task 5: Refresh Analysis Page Visuals Without Changing Flow

**Files:**
- Modify: `D:/Git/agent/noval/frontend/src/views/analysis/AnalysisView.vue`
- Modify: `D:/Git/agent/noval/frontend/src/composables/useAnalysisRun.ts` (only if styling hooks require minor state exposure; avoid logic changes unless necessary)
- Test:
  - `D:/Git/agent/noval/frontend/src/views/analysis/__tests__/AnalysisView.spec.ts`
  - `D:/Git/agent/noval/frontend/src/composables/__tests__/useAnalysisRun.spec.ts`

- [ ] **Step 1: Run the analysis page tests**

Run:

```bash
npm run test -- --run src/views/analysis/__tests__/AnalysisView.spec.ts src/composables/__tests__/useAnalysisRun.spec.ts
```

Expected: PASS on baseline

- [ ] **Step 2: Restyle analysis view shell**

Update `AnalysisView.vue`:

- align hero/context presentation with the new theme
- keep current persistence/restore behavior intact
- keep markdown result area readable in both themes

- [ ] **Step 3: Only add minor state hooks if absolutely needed**

If styling requires tiny state exposure from `useAnalysisRun`, make the smallest possible change and no behavior changes.

- [ ] **Step 4: Run the analysis tests and type-check**

Run:

```bash
npm run test -- --run src/views/analysis/__tests__/AnalysisView.spec.ts src/composables/__tests__/useAnalysisRun.spec.ts
npm run type-check
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/analysis/AnalysisView.vue frontend/src/composables/useAnalysisRun.ts frontend/src/views/analysis/__tests__/AnalysisView.spec.ts frontend/src/composables/__tests__/useAnalysisRun.spec.ts
git commit -m "feat: refresh analysis page visuals"
```

---

### Task 6: Refresh Trend Surface, Charts, Tables, And Word Cloud

**Files:**
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendContextBar.vue`
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendResultPreview.vue`
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendSummaryCards.vue`
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendComparisonList.vue`
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendSnapshotTable.vue`
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendTagCloud.vue`
- Modify: `D:/Git/agent/noval/frontend/src/components/trend/TrendChartCard.vue`
- Modify: `D:/Git/agent/noval/frontend/src/views/trend/TrendView.vue`
- Test:
  - `D:/Git/agent/noval/frontend/src/components/trend/__tests__/TrendSnapshotTable.spec.ts`
  - `D:/Git/agent/noval/frontend/src/components/trend/__tests__/TrendTagCloud.spec.ts`
  - `D:/Git/agent/noval/frontend/src/views/trend/__tests__/TrendView.spec.ts`

- [ ] **Step 1: Run the trend-related tests**

Run:

```bash
npm run test -- --run src/components/trend/__tests__/TrendSnapshotTable.spec.ts src/components/trend/__tests__/TrendTagCloud.spec.ts src/views/trend/__tests__/TrendView.spec.ts
```

Expected: PASS on baseline

- [ ] **Step 2: Tighten context/result copy**

Update:

- `TrendContextBar.vue`
- `TrendResultPreview.vue`
- `TrendSummaryCards.vue`
- `TrendComparisonList.vue`

to remove verbose explanatory text and keep only functional, readable labels.

- [ ] **Step 3: Clean chart shells and mobile table behavior**

Update:

- `TrendChartCard.vue`
- `TrendSnapshotTable.vue`
- `TrendView.vue`

so:

- pie chart labels no longer crowd the chart body
- trend snapshot mobile cards stay clean
- theme-table mobile cards / desktop table stay split clearly

- [ ] **Step 4: Implement the improved Chinese word cloud**

Update `TrendTagCloud.vue` to:

- render a more natural cloud layout
- keep bigger terms visibly larger
- use younger “ins” colors
- avoid dense center clumping

- [ ] **Step 5: Run trend tests and type-check**

Run:

```bash
npm run test -- --run src/components/trend/__tests__/TrendSnapshotTable.spec.ts src/components/trend/__tests__/TrendTagCloud.spec.ts src/views/trend/__tests__/TrendView.spec.ts
npm run type-check
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/trend/TrendContextBar.vue frontend/src/components/trend/TrendResultPreview.vue frontend/src/components/trend/TrendSummaryCards.vue frontend/src/components/trend/TrendComparisonList.vue frontend/src/components/trend/TrendSnapshotTable.vue frontend/src/components/trend/TrendTagCloud.vue frontend/src/components/trend/TrendChartCard.vue frontend/src/views/trend/TrendView.vue frontend/src/components/trend/__tests__/TrendSnapshotTable.spec.ts frontend/src/components/trend/__tests__/TrendTagCloud.spec.ts frontend/src/views/trend/__tests__/TrendView.spec.ts
git commit -m "feat: refresh trend visuals and word cloud"
```

---

### Task 7: Refresh Login / Config / History Surfaces For Consistency

**Files:**
- Modify:
  - `D:/Git/agent/noval/frontend/src/views/login/LoginView.vue`
  - `D:/Git/agent/noval/frontend/src/views/history/HistoryView.vue` (if needed)
  - `D:/Git/agent/noval/frontend/src/views/config/prompt/PromptConfigView.vue`
  - `D:/Git/agent/noval/frontend/src/views/config/system/SystemConfigView.vue`
- Test:
  - `D:/Git/agent/noval/frontend/src/views/login/__tests__/LoginView.spec.ts`
  - `D:/Git/agent/noval/frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts`
  - `D:/Git/agent/noval/frontend/src/views/config/system/__tests__/SystemConfigView.spec.ts`

- [ ] **Step 1: Run the login/config tests**

Run:

```bash
npm run test -- --run src/views/login/__tests__/LoginView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts
```

Expected: PASS on baseline

- [ ] **Step 2: Apply theme consistency pass**

Update login/config/history surfaces to use the new glass theme tokens and dark-mode-compatible surfaces without changing behavior.

- [ ] **Step 3: Run the login/config tests and type-check**

Run:

```bash
npm run test -- --run src/views/login/__tests__/LoginView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts
npm run type-check
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/login/LoginView.vue frontend/src/views/history/HistoryView.vue frontend/src/views/config/prompt/PromptConfigView.vue frontend/src/views/config/system/SystemConfigView.vue frontend/src/views/login/__tests__/LoginView.spec.ts frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts frontend/src/views/config/system/__tests__/SystemConfigView.spec.ts
git commit -m "feat: align supporting pages with refreshed theme"
```

---

### Task 8: Final Frontend Verification Sweep

**Files:**
- No new product files
- Verify affected frontend surface holistically

- [ ] **Step 1: Run the focused frontend regression suite**

Run:

```bash
npm run test -- --run src/layouts/__tests__/AppShell.spec.ts src/components/analysis/__tests__/AnalysisResultCard.spec.ts src/components/rank/__tests__/BookDetailDrawer.spec.ts src/components/rank/__tests__/ChapterPreviewDrawer.spec.ts src/components/trend/__tests__/TrendSnapshotTable.spec.ts src/components/trend/__tests__/TrendTagCloud.spec.ts src/views/analysis/__tests__/AnalysisView.spec.ts src/views/rank/__tests__/RankView.spec.ts src/views/trend/__tests__/TrendView.spec.ts src/views/login/__tests__/LoginView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts
```

Expected: PASS

- [ ] **Step 2: Run final type-check**

Run: `npm run type-check`
Expected: PASS

- [ ] **Step 3: Manual browser checklist**

Verify manually:

- mobile fixed header remains visible
- liquid glass bottom nav floats above content
- light/dark toggle works
- markdown preview darkens correctly
- trend word cloud looks cloud-like and readable
- mobile trend cards do not overflow horizontally

- [ ] **Step 4: Commit the final sweep**

```bash
git add frontend
git commit -m "chore: finalize frontend visual refresh"
```

---

## Execution Notes

- Do not touch backend APIs or DTO contracts in this plan.
- Use the existing frontend tests as guardrails; add only targeted tests where behavior changes.
- Keep each commit scoped to one visual/theme area so regressions are easy to isolate.
- If a visual change breaks readability, revert the visual flourish and keep the readable version.
