# Frontend Phase 2 Analysis Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the real `/analysis` workbench with three analysis modes, SSE-first rendering, blocking fallback, and secure markdown result display.

**Architecture:** Extend the existing Vue 3 frontend with a dedicated analysis module made of typed API clients, a stream parser/fallback runtime, and a page-level composable that manages state transitions. Keep the UI modular: context bar, mode tabs, result card, and toolbar remain separate so later phases can reuse the same result experience for trend/history pages.

**Tech Stack:** Vue 3, TypeScript, Vite, Pinia, Vue Router, Axios, Element Plus, Vitest, Vue Test Utils, DOMPurify, Marked

---

## File Structure

### New files

- `frontend/src/types/analysis.ts`
  Responsibility: analysis request/response/SSE types aligned to backend `AnalysisRequest` and `AnalysisResultVO`.
- `frontend/src/api/analysis.ts`
  Responsibility: blocking analysis APIs and streaming API entrypoints.
- `frontend/src/lib/analysis-stream.ts`
  Responsibility: `fetch + ReadableStream` SSE parsing, abort control, `401` refresh retry, and blocking fallback handoff.
- `frontend/src/lib/markdown.ts`
  Responsibility: convert analysis markdown to sanitized HTML using `marked` + `dompurify`.
- `frontend/src/composables/useAnalysisRun.ts`
  Responsibility: page state machine, mode switching, result cache, rerun, stop, and fallback status management.
- `frontend/src/components/analysis/AnalysisContextBar.vue`
  Responsibility: book metadata, current mode, and analysis context display.
- `frontend/src/components/analysis/AnalysisModeTabs.vue`
  Responsibility: `deconstruct / structure / plot` switching UI.
- `frontend/src/components/analysis/AnalysisToolbar.vue`
  Responsibility: stop, rerun, copy controls.
- `frontend/src/components/analysis/AnalysisResultCard.vue`
  Responsibility: loading, streaming, done, error, and metadata display.
- `frontend/src/components/analysis/AnalysisEmptyState.vue`
  Responsibility: missing query/invalid entry empty state.
- `frontend/src/views/analysis/AnalysisView.vue`
  Responsibility: real analysis page that composes all analysis components.
- `frontend/src/lib/__tests__/analysis-stream.spec.ts`
  Responsibility: stream parsing and fallback tests.
- `frontend/src/lib/__tests__/markdown.spec.ts`
  Responsibility: markdown sanitization tests.
- `frontend/src/composables/__tests__/useAnalysisRun.spec.ts`
  Responsibility: state machine and rerun/mode switch tests.
- `frontend/src/views/analysis/__tests__/AnalysisView.spec.ts`
  Responsibility: page entry, auto-run, and UI behavior tests.

### Modified files

- `frontend/package.json`
  Responsibility: add markdown rendering and sanitization dependencies if not already present.
- `frontend/src/router/index.ts`
  Responsibility: replace placeholder analysis route component with `AnalysisView`.
- `frontend/src/views/rank/RankView.vue`
  Responsibility: preserve and pass stable analysis context to `/analysis`.
- `frontend/src/types/api.ts`
  Responsibility: extend shared types only if needed for stream/fallback error payloads.
- `frontend/src/lib/http.ts`
  Responsibility: expose refresh helper or token utilities required by stream requests without duplicating auth logic.
- `frontend/README.md`
  Responsibility: document phase 2 capability and any new dependency/command notes.

---

### Task 1: Add analysis types and secure markdown utilities

**Files:**
- Create: `frontend/src/types/analysis.ts`
- Create: `frontend/src/lib/markdown.ts`
- Test: `frontend/src/lib/__tests__/markdown.spec.ts`
- Modify: `frontend/package.json`

- [ ] **Step 1: Install markdown dependencies**

Run: `npm install marked dompurify`
Expected: dependencies install without peer dependency conflicts

- [ ] **Step 2: Write the failing markdown sanitization test**

Create `frontend/src/lib/__tests__/markdown.spec.ts`:

```ts
import { renderAnalysisMarkdown } from '@/lib/markdown';

test('sanitizes unsafe html from analysis markdown', () => {
  const html = renderAnalysisMarkdown('# Title\n<script>alert(1)</script><img src=x onerror=alert(2) />');

  expect(html).toContain('<h1>Title</h1>');
  expect(html).not.toContain('<script>');
  expect(html).not.toContain('onerror');
});
```

- [ ] **Step 3: Run the markdown test to verify RED**

Run: `npm run test -- markdown.spec`
Expected: FAIL because the markdown helper does not exist

- [ ] **Step 4: Implement minimal analysis types and markdown renderer**

Include at least:

```ts
export type AnalysisType = 'deconstruct' | 'structure' | 'plot';

export interface AnalysisRequest {
  platform: 'fanqie';
  bookId: number;
  chapterCount: number;
  forceReanalyze?: boolean;
}

export interface AnalysisResult {
  id: number;
  bookId: number;
  analysisType: AnalysisType;
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  tokenUsed: number;
}

export type StreamEventType = 'start' | 'delta' | 'done' | 'error';
```

Use `marked.parse` plus `DOMPurify.sanitize` in `renderAnalysisMarkdown`.

- [ ] **Step 5: Run the markdown test to verify GREEN**

Run: `npm run test -- markdown.spec`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/src/types/analysis.ts frontend/src/lib/markdown.ts frontend/src/lib/__tests__/markdown.spec.ts
git commit -m "feat: add analysis types and safe markdown rendering"
```

### Task 2: Build the SSE parser and blocking fallback runtime

**Files:**
- Create: `frontend/src/lib/analysis-stream.ts`
- Create: `frontend/src/api/analysis.ts`
- Test: `frontend/src/lib/__tests__/analysis-stream.spec.ts`
- Modify: `frontend/src/lib/http.ts`

- [ ] **Step 1: Write the failing stream runtime tests**

Cover:

- `start -> delta -> done` sequence parsing
- `error` event surfacing
- `404/405/501` fallback trigger before any delta
- `401` refresh and reopen exactly once
- abort stops further callbacks

Example test scaffold:

```ts
test('parses start delta done events from sse stream', async () => {
  const events: string[] = [];

  const task = createAnalysisStreamRunner(/* mocked fetch deps */).run({
    onStart: () => events.push('start'),
    onDelta: () => events.push('delta'),
    onDone: () => events.push('done'),
    onError: () => events.push('error'),
  });

  await task.result;

  expect(events).toEqual(['start', 'delta', 'done']);
});
```

- [ ] **Step 2: Run the stream tests to verify RED**

Run: `npm run test -- analysis-stream.spec`
Expected: FAIL because the stream runtime does not exist

- [ ] **Step 3: Implement the minimal stream runtime**

Implement:

- `parseSseFrames(buffer: string): { events; rest }`
- `createAnalysisStreamRunner(...)`
- `abort()`
- refresh-once handling for stream requests
- pre-delta fallback to blocking API

Expose typed API methods:

```ts
analysisApi.runDeconstruct(payload)
analysisApi.runStructure(payload)
analysisApi.runPlot(payload)
analysisApi.streamDeconstruct(payload, callbacks)
analysisApi.streamStructure(payload, callbacks)
analysisApi.streamPlot(payload, callbacks)
```

- [ ] **Step 4: Run the stream tests to verify GREEN**

Run: `npm run test -- analysis-stream.spec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/analysis-stream.ts frontend/src/api/analysis.ts frontend/src/lib/http.ts frontend/src/lib/__tests__/analysis-stream.spec.ts
git commit -m "feat: add analysis stream runtime and blocking fallback"
```

### Task 3: Build the page state machine composable

**Files:**
- Create: `frontend/src/composables/useAnalysisRun.ts`
- Test: `frontend/src/composables/__tests__/useAnalysisRun.spec.ts`

- [ ] **Step 1: Write the failing composable tests**

Cover:

- auto-run default mode
- switch mode aborts previous task and starts new one
- rerun sends `forceReanalyze=true`
- partial text remains after mid-stream interruption
- completed results are cached by mode in-session

Example assertion:

```ts
expect(state.phase).toBe('streaming');
expect(state.streamingText).toContain('第一段');
```

- [ ] **Step 2: Run the composable tests to verify RED**

Run: `npm run test -- useAnalysisRun.spec`
Expected: FAIL because the composable does not exist

- [ ] **Step 3: Implement the minimal composable**

State should include:

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

Expose:

- `runAnalysis(mode, options?)`
- `switchMode(mode)`
- `stopAnalysis()`
- `rerunAnalysis()`
- `copyResult()`

- [ ] **Step 4: Run the composable tests to verify GREEN**

Run: `npm run test -- useAnalysisRun.spec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/composables/useAnalysisRun.ts frontend/src/composables/__tests__/useAnalysisRun.spec.ts
git commit -m "feat: add analysis page state machine"
```

### Task 4: Build reusable analysis UI components

**Files:**
- Create: `frontend/src/components/analysis/AnalysisContextBar.vue`
- Create: `frontend/src/components/analysis/AnalysisModeTabs.vue`
- Create: `frontend/src/components/analysis/AnalysisToolbar.vue`
- Create: `frontend/src/components/analysis/AnalysisResultCard.vue`
- Create: `frontend/src/components/analysis/AnalysisEmptyState.vue`
- Test: `frontend/src/components/analysis/__tests__/AnalysisResultCard.spec.ts`

- [ ] **Step 1: Write the failing component smoke test**

Cover:

- loading skeleton render
- streaming cursor render
- done state metadata render
- sanitized HTML container render
- error state render

- [ ] **Step 2: Run the component test to verify RED**

Run: `npm run test -- AnalysisResultCard`
Expected: FAIL because the analysis components do not exist

- [ ] **Step 3: Implement the minimal analysis components**

Key UI rules:

- `AnalysisContextBar` shows book metadata and chapter count
- `AnalysisModeTabs` uses clear active state and disabled state during blocking transitions only when needed
- `AnalysisToolbar` exposes stop/rerun/copy
- `AnalysisResultCard` handles skeleton, streaming text, cursor, final sanitized markdown, error message, and `traceId/modelName/tokenUsed`
- `AnalysisEmptyState` links back to `/rank`

- [ ] **Step 4: Run the component test to verify GREEN**

Run: `npm run test -- AnalysisResultCard`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/analysis frontend/src/components/analysis/__tests__/AnalysisResultCard.spec.ts
git commit -m "feat: add reusable analysis page components"
```

### Task 5: Build the real analysis page and route integration

**Files:**
- Create: `frontend/src/views/analysis/AnalysisView.vue`
- Test: `frontend/src/views/analysis/__tests__/AnalysisView.spec.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/rank/RankView.vue`
- Delete: `frontend/src/views/analysis/AnalysisPlaceholderView.vue`

- [ ] **Step 1: Write the failing analysis page tests**

Cover:

- query missing shows empty state
- valid query loads book detail and auto-runs `deconstruct`
- mode switch triggers the right API path
- rerun uses `forceReanalyze=true`
- completed card shows metadata

Example:

```ts
expect(wrapper.text()).toContain('trace-analysis-001');
expect(wrapper.text()).toContain('modelName');
```

- [ ] **Step 2: Run the analysis page tests to verify RED**

Run: `npm run test -- AnalysisView`
Expected: FAIL because the page does not exist yet

- [ ] **Step 3: Implement the analysis page**

Implement:

- query parsing and validation
- `crawlerApi.getBookDetail` bootstrap
- default mode autorun
- integration with `useAnalysisRun`
- route swap from placeholder to real page

Keep `RankView.vue` route push format stable:

```ts
{
  path: '/analysis',
  query: {
    bookId: String(bookId),
    platform: 'fanqie',
    chapterCount: String(chapterCount),
  },
}
```

- [ ] **Step 4: Run the analysis page tests to verify GREEN**

Run: `npm run test -- AnalysisView`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/analysis frontend/src/router/index.ts frontend/src/views/rank/RankView.vue
git commit -m "feat: build analysis workbench page"
```

### Task 6: Update docs and developer notes

**Files:**
- Modify: `frontend/README.md`
- Modify: `docs/前端接口设计-v1.md`

- [ ] **Step 1: Update frontend README**

Document:

- `/analysis` now exists
- stream-first behavior
- blocking fallback behavior
- any new runtime dependency note

- [ ] **Step 2: Update frontend API design doc**

Adjust the wording from “planned streaming” to “streaming landed for single-book analysis” where applicable, while keeping trend/history phase boundaries clear.

- [ ] **Step 3: Verify docs**

Run: `Get-Content frontend\\README.md`
Run: `Get-Content docs\\前端接口设计-v1.md`
Expected: docs reflect current reality without claiming trend/history are done

- [ ] **Step 4: Commit**

```bash
git add frontend/README.md docs/前端接口设计-v1.md
git commit -m "docs: update frontend analysis phase documentation"
```

### Task 7: Final verification

**Files:**
- Verify only

- [ ] **Step 1: Run focused unit tests**

Run: `npm run test -- analysis-stream.spec useAnalysisRun.spec AnalysisView`
Expected: PASS

- [ ] **Step 2: Run full test suite**

Run: `npm run test -- --run`
Expected: PASS

- [ ] **Step 3: Run type check**

Run: `npm run type-check`
Expected: PASS

- [ ] **Step 4: Run production build**

Run: `npm run build`
Expected: PASS

- [ ] **Step 5: Smoke run dev server**

Run: `npm run dev -- --host 127.0.0.1 --port 4173`
Expected: `/analysis` route loads from a query URL and renders the analysis workbench
