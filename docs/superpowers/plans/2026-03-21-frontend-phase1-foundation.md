# Frontend Phase 1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first working frontend slice in `frontend/` with login/auth/session recovery, app shell, and the rank workflow aligned to the current backend.

**Architecture:** Create a Vue 3 + Vite + TypeScript app with Element Plus, Vue Router, Pinia, and a centralized axios client. Keep the first phase intentionally narrow: `/login` and `/rank` only, with JWT decoding, auto-refresh-once, and crawler API integration as the reusable base for later analysis pages.

**Tech Stack:** Vue 3, TypeScript, Vite, Pinia, Vue Router, Element Plus, Axios, SCSS, Vitest, Vue Test Utils

---

### Task 1: Scaffold the frontend workspace

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/.nvmrc`
- Create: `frontend/vite.config.ts`
- Create: `frontend/index.html`
- Create: `frontend/tsconfig.json`
- Create: `frontend/tsconfig.app.json`
- Create: `frontend/tsconfig.node.json`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`

- [ ] **Step 1: Create the Vite Vue TypeScript baseline**

Run: `npm create vite@latest frontend -- --template vue-ts`
Expected: scaffolding succeeds and creates the base project structure

- [ ] **Step 2: Add Node version pinning**

Write `.nvmrc` with:

```text
20
```

- [ ] **Step 3: Install runtime dependencies**

Run: `npm install`
Run: `npm install axios pinia vue-router element-plus @element-plus/icons-vue`
Expected: dependencies install without peer dependency errors

- [ ] **Step 4: Install test and style dependencies**

Run: `npm install -D sass vitest @vitest/coverage-v8 @vue/test-utils jsdom axios-mock-adapter @vitejs/plugin-vue`
Expected: test stack is ready

- [ ] **Step 5: Verify the scaffold**

Run: `npm run build`
Expected: initial build passes

### Task 2: Establish app shell and visual foundation

**Files:**
- Modify: `frontend/src/App.vue`
- Create: `frontend/src/styles/tokens.scss`
- Create: `frontend/src/styles/base.scss`
- Create: `frontend/src/styles/element.scss`
- Create: `frontend/src/layouts/AppShell.vue`
- Create: `frontend/src/components/layout/AppSidebar.vue`
- Create: `frontend/src/components/layout/AppHeader.vue`

- [ ] **Step 1: Write the failing layout smoke test**

Create `frontend/src/layouts/__tests__/AppShell.spec.ts`:

```ts
import { mount } from '@vue/test-utils';
import AppShell from '../AppShell.vue';

test('renders app shell slots and top actions', () => {
  const wrapper = mount(AppShell, {
    props: {
      username: 'demo',
      roles: ['USER'],
    },
    slots: {
      default: '<div>page body</div>',
    },
  });

  expect(wrapper.text()).toContain('demo');
  expect(wrapper.text()).toContain('page body');
});
```

- [ ] **Step 2: Run the layout test to verify RED**

Run: `npm run test -- AppShell`
Expected: FAIL because the shell component does not exist yet

- [ ] **Step 3: Implement shell and global styles**

Build:

- a warm editorial dashboard background
- a left navigation with only `扫榜台`
- a top header with username, roles, and logout action slot
- global tokens for background, surface, text, accent, danger, border, shadow

- [ ] **Step 4: Run the layout test to verify GREEN**

Run: `npm run test -- AppShell`
Expected: PASS

### Task 3: Build auth types, JWT helpers, and storage utilities

**Files:**
- Create: `frontend/src/types/api.ts`
- Create: `frontend/src/types/auth.ts`
- Create: `frontend/src/constants/auth.ts`
- Create: `frontend/src/utils/jwt.ts`
- Create: `frontend/src/utils/storage.ts`
- Test: `frontend/src/utils/__tests__/jwt.spec.ts`
- Test: `frontend/src/utils/__tests__/storage.spec.ts`

- [ ] **Step 1: Write failing JWT parsing tests**

Add tests for:

- decoding JWT payload
- splitting `roles` from comma-separated string
- building `AuthSession` from `TokenResponse`

- [ ] **Step 2: Run the JWT tests to verify RED**

Run: `npm run test -- jwt`
Expected: FAIL because helper modules are missing

- [ ] **Step 3: Implement minimal auth helpers**

Implement:

- `decodeJwtClaims`
- `buildAuthSession`
- `readTokenSnapshot`
- `persistTokenSnapshot`
- `clearTokenSnapshot`

- [ ] **Step 4: Run JWT and storage tests to verify GREEN**

Run: `npm run test -- src/utils`
Expected: PASS

### Task 4: Build the auth store and route guard

**Files:**
- Create: `frontend/src/stores/auth.ts`
- Create: `frontend/src/router/index.ts`
- Create: `frontend/src/router/guards.ts`
- Create: `frontend/src/views/login/LoginView.vue`
- Create: `frontend/src/views/rank/RankView.vue`
- Test: `frontend/src/stores/__tests__/auth.spec.ts`
- Test: `frontend/src/router/__tests__/guards.spec.ts`

- [ ] **Step 1: Write failing store and guard tests**

Cover:

- restore session from LocalStorage
- unauthenticated user hitting `/rank` redirects to `/login`
- authenticated user hitting `/login` redirects to `/rank`

- [ ] **Step 2: Run tests to verify RED**

Run: `npm run test -- auth.spec guards.spec`
Expected: FAIL because the store and guard logic are not implemented yet

- [ ] **Step 3: Implement store and router**

Implement:

- `loginSuccess`
- `restoreSession`
- `logout`
- `hasRole`
- router meta for public/protected routes
- guard wiring in router creation

- [ ] **Step 4: Run store and guard tests to verify GREEN**

Run: `npm run test -- auth.spec guards.spec`
Expected: PASS

### Task 5: Build the axios client with refresh-once behavior

**Files:**
- Create: `frontend/src/lib/http.ts`
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/api/crawler.ts`
- Test: `frontend/src/lib/__tests__/http.spec.ts`

- [ ] **Step 1: Write failing HTTP client tests**

Cover:

- attaches `Authorization: Bearer <token>`
- on `401`, posts to `/api/auth/refresh` with `{ token }`
- retries the original request once after refresh
- clears session when refresh fails

- [ ] **Step 2: Run the HTTP tests to verify RED**

Run: `npm run test -- http.spec`
Expected: FAIL because the client is not implemented

- [ ] **Step 3: Implement minimal API base layer**

Implement:

- axios instance with timeout and base URL from `import.meta.env`
- request interceptor for auth header
- response interceptor for refresh-once logic
- typed `authApi` and `crawlerApi`

- [ ] **Step 4: Run HTTP tests to verify GREEN**

Run: `npm run test -- http.spec`
Expected: PASS

### Task 6: Build the login page

**Files:**
- Modify: `frontend/src/views/login/LoginView.vue`
- Create: `frontend/src/views/login/__tests__/LoginView.spec.ts`

- [ ] **Step 1: Write failing login page tests**

Cover:

- submit button disabled while request is pending
- successful login stores session and navigates to `/rank`
- failed login displays backend message

- [ ] **Step 2: Run login view tests to verify RED**

Run: `npm run test -- LoginView`
Expected: FAIL because the page behavior is incomplete

- [ ] **Step 3: Implement the login UI**

Implement:

- two-column desktop layout and single-column mobile layout
- username/password form
- loading state
- inline/global error display with `traceId` when available

- [ ] **Step 4: Run login view tests to verify GREEN**

Run: `npm run test -- LoginView`
Expected: PASS

### Task 7: Build the rank page workflow

**Files:**
- Create: `frontend/src/types/crawler.ts`
- Modify: `frontend/src/views/rank/RankView.vue`
- Create: `frontend/src/components/rank/RankToolbar.vue`
- Create: `frontend/src/components/rank/RankTable.vue`
- Create: `frontend/src/components/rank/BookDetailDrawer.vue`
- Create: `frontend/src/components/rank/ChapterPreviewDrawer.vue`
- Create: `frontend/src/views/rank/__tests__/RankView.spec.ts`

- [ ] **Step 1: Write failing rank page tests**

Cover:

- loads rank list with default category `male-hot-a`
- opens detail drawer after clicking detail
- opens chapter preview after clicking fetch chapters
- exposes “进入分析页” action with `bookId/platform/chapterCount`

- [ ] **Step 2: Run rank page tests to verify RED**

Run: `npm run test -- RankView`
Expected: FAIL because rank components and workflow are not implemented yet

- [ ] **Step 3: Implement rank workflow**

Implement:

- filter toolbar with category and chapter count
- rank table
- detail drawer
- chapter preview drawer
- analysis context handoff via router query or temporary store

- [ ] **Step 4: Run rank page tests to verify GREEN**

Run: `npm run test -- RankView`
Expected: PASS

### Task 8: Wire env config and developer ergonomics

**Files:**
- Create: `frontend/.env.example`
- Modify: `frontend/README.md`

- [ ] **Step 1: Add environment template**

Include:

```env
VITE_API_BASE_URL=http://localhost:8080
```

- [ ] **Step 2: Update frontend README**

Document:

- Node 20 via `nvm`
- install/run/build/test commands
- backend dependency and API base URL

- [ ] **Step 3: Verify docs match implementation**

Run: `Get-Content README.md`
Expected: command list and env variables are correct

### Task 9: Run final verification

**Files:**
- Verify only

- [ ] **Step 1: Run unit tests**

Run: `npm run test -- --run`
Expected: PASS

- [ ] **Step 2: Run production build**

Run: `npm run build`
Expected: PASS

- [ ] **Step 3: Run lint-equivalent type check**

Run: `npm run type-check`
Expected: PASS

- [ ] **Step 4: Smoke run dev server**

Run: `npm run dev -- --host 127.0.0.1 --port 4173`
Expected: app starts and `/login` loads
