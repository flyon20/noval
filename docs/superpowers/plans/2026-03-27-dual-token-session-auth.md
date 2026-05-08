# Dual Token Session Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the current single-access-token auth flow to a short-lived JWT access token plus 7-day sliding opaque refresh session backed by Redis hot state and MySQL persistence, with true logout and a 3-device limit.

**Architecture:** Keep JWT for high-frequency API authorization, but move long-lived login state into a persisted session model. Redis becomes the online source of truth for active sessions and refresh lookups, while MySQL persists `sys_user_session` for recovery, audit, and device lifecycle management. The frontend stops storing long-lived credentials in `localStorage`; instead it restores access by calling a cookie-based refresh endpoint during app bootstrap and on one-shot 401 retries.

**Tech Stack:** Spring Boot 3, JdbcTemplate, MySQL, Redis, JWT (jjwt), Vue 3, Pinia, Vue Router, Axios, Vitest, JUnit 5, MockMvc, H2

---

## File Structure

### New files

- `backend/src/main/java/com/novelanalyzer/modules/auth/model/AuthSessionEntity.java`
  Responsibility: typed in-memory representation of rows from `sys_user_session`.
- `backend/src/main/java/com/novelanalyzer/modules/auth/model/AuthSessionStatus.java`
  Responsibility: centralize `ACTIVE / REVOKED / EXPIRED / KICKED` integer status values.
- `backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthSessionRepository.java`
  Responsibility: insert/update/query `sys_user_session` rows and oldest-active-session lookups.
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/RefreshTokenService.java`
  Responsibility: generate opaque refresh tokens, hash them, and compute cookie-safe token identifiers.
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionService.java`
  Responsibility: orchestrate Redis hot state, MySQL persistence, Redis fallback rehydration, and device eviction.
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionFlushScheduler.java`
  Responsibility: flush dirty `last_active_time` session updates from Redis into MySQL on a schedule.
- `frontend/src/lib/auth-bootstrap.ts`
  Responsibility: bootstrap login state by calling `/api/auth/refresh` with credentials on app startup when memory is empty.
- `frontend/src/lib/__tests__/auth-bootstrap.spec.ts`
  Responsibility: verify silent refresh bootstrap behavior and failure cleanup.

### Modified files

- `backend/src/main/resources/application.yml`
  Responsibility: add access token TTL, refresh token TTL, max-device, cookie path, and cookie security defaults.
- `backend/src/main/java/com/novelanalyzer/config/AuthProperties.java`
  Responsibility: expose new auth/session/cookie config knobs.
- `backend/src/main/java/com/novelanalyzer/common/utils/JwtUtils.java`
  Responsibility: issue access tokens with `sid` claim helper support if needed.
- `backend/src/main/java/com/novelanalyzer/modules/auth/dto/LoginRequest.java`
  Responsibility: accept optional `deviceLabel`.
- `backend/src/main/java/com/novelanalyzer/modules/auth/dto/RefreshTokenRequest.java`
  Responsibility: make request-body token optional or deprecate body semantics safely.
- `backend/src/main/java/com/novelanalyzer/modules/auth/vo/TokenResponse.java`
  Responsibility: continue returning only access token metadata; no refresh token in JSON.
- `backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthRepository.java`
  Responsibility: keep user/role lookup and possibly expose helper methods needed by session orchestration.
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java`
  Responsibility: login, refresh, logout, and access token issuance now orchestrate session creation/rotation/revocation.
- `backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java`
  Responsibility: set/clear refresh cookie, accept cookie-based refresh, and stop trusting body-carried refresh tokens.
- `backend/src/main/java/com/novelanalyzer/modules/security/filter/AuthTokenFilter.java`
  Responsibility: after JWT verification, enforce `sid` session validity through Redis/MySQL-backed session checks.
- `backend/src/test/resources/sql/phase2-schema-h2.sql`
  Responsibility: add `sys_user_session`; this fixture is shared by many integration tests.
- `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`
  Responsibility: dual-token login/refresh/logout/invalidation/device-limit behavior.
- `backend/src/test/java/com/novelanalyzer/modules/security/Phase2SecurityIntegrationTest.java`
  Responsibility: protected request access now also depends on live session state.
- `backend/src/test/java/com/novelanalyzer/modules/security/LoginRateLimitIntegrationTest.java`
  Responsibility: ensure auth whitelist endpoints still enforce rate limits after cookie changes.
- `frontend/src/types/auth.ts`
  Responsibility: align login request and access-token-only response types with the new contract.
- `frontend/src/lib/auth-session.ts`
  Responsibility: move from persistent access token snapshots to in-memory-first session handling.
- `frontend/src/utils/storage.ts`
  Responsibility: stop persisting long-lived access credentials to `localStorage`.
- `frontend/src/lib/http.ts`
  Responsibility: send `withCredentials` for auth endpoints, refresh via cookie, and replay one failed request after refresh.
- `frontend/src/api/auth.ts`
  Responsibility: login/logout/refresh wrappers using cookie-based refresh.
- `frontend/src/stores/auth.ts`
  Responsibility: initialize auth bootstrap, expose logout, and hold in-memory session only.
- `frontend/src/main.ts`
  Responsibility: run auth bootstrap before mounting protected navigation behavior.
- `frontend/src/router/index.ts`
  Responsibility: stop restoring auth solely from `localStorage`; integrate async bootstrap-aware route gating.
- `frontend/src/router/guards.ts`
  Responsibility: support a transient "auth restoring" state so protected routes wait for bootstrap.
- `frontend/src/router/__tests__/guards.spec.ts`
  Responsibility: route guard behavior with restoring, authenticated, and logged-out states.
- `frontend/src/views/login/LoginView.vue`
  Responsibility: submit optional `deviceLabel`, keep login/register UX compatible with cookie auth.
- `frontend/src/views/login/__tests__/LoginView.spec.ts`
  Responsibility: login/register still navigate correctly with access-token-only JSON responses.
- `frontend/src/lib/__tests__/http.spec.ts`
  Responsibility: verify cookie-based refresh, one-shot replay, and logout semantics.
- `frontend/src/stores/__tests__/auth.spec.ts`
  Responsibility: bootstrap, session clear, and logout behavior without `localStorage` persistence.

---

### Task 1: Add persisted session schema and auth config knobs

**Files:**
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/model/AuthSessionEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/model/AuthSessionStatus.java`
- Modify: `backend/src/test/resources/sql/phase2-schema-h2.sql`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/novelanalyzer/config/AuthProperties.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/LoginRequest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`

- [ ] **Step 1: Write the failing auth integration assertions for dual-token login response and session persistence**

Add a test in `AuthControllerTest` that prepares the new schema-backed auth scaffolding and asserts `sys_user_session` can be queried as part of the auth test fixture setup. Keep cookie issuance out of Task 1; that belongs to Task 3 when login actually issues refresh cookies.

- [ ] **Step 2: Run the auth controller test to verify RED**

Run: `mvn "-Dtest=AuthControllerTest" test`
Expected: FAIL because `sys_user_session` does not exist yet.

- [ ] **Step 3: Add the shared schema fixture changes**

Update `phase2-schema-h2.sql` to create `sys_user_session` with:

```sql
CREATE TABLE sys_user_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    refresh_token_hash VARCHAR(128) NOT NULL,
    status TINYINT DEFAULT 1,
    device_label VARCHAR(100),
    user_agent VARCHAR(255),
    login_ip VARCHAR(50),
    last_active_time TIMESTAMP,
    last_refresh_time TIMESTAMP,
    refresh_expire_time TIMESTAMP NOT NULL,
    revoke_reason VARCHAR(200),
    revoked_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    version INT DEFAULT 0
);
```

Also add the unique/index definitions from the spec.

- [ ] **Step 4: Add the new auth config properties**

Extend `AuthProperties` and `application.yml` with at least:

```yaml
app:
  auth:
    access-token-expire-seconds: 900
    refresh-token-expire-seconds: 604800
    session-max-devices: 3
    refresh-cookie-name: refresh_token
    refresh-cookie-path: /api/auth
    refresh-cookie-secure: true
    refresh-cookie-same-site: Strict
```

- [ ] **Step 5: Add the minimal session model types**

Create `AuthSessionEntity` fields matching the table and `AuthSessionStatus` constants for `ACTIVE`, `REVOKED`, `EXPIRED`, and `KICKED`. If `AuthControllerTest` has unrelated local edits, add the Task 1 red/green assertions in a dedicated auth test file instead of mixing scopes.

- [ ] **Step 6: Extend login DTO for device labeling**

Add optional `deviceLabel` to `LoginRequest` without making it required.

- [ ] **Step 7: Run backend test compile and the auth controller test to verify GREEN**

Run:
- `mvn -DskipTests test-compile`
- `mvn "-Dtest=AuthControllerTest" test`

Expected: schema compiles and the new login/session fixture assertions pass.

- [ ] **Step 8: Commit**

```bash
git add backend/src/test/resources/sql/phase2-schema-h2.sql backend/src/main/resources/application.yml backend/src/main/java/com/novelanalyzer/config/AuthProperties.java backend/src/main/java/com/novelanalyzer/modules/auth/model/AuthSessionEntity.java backend/src/main/java/com/novelanalyzer/modules/auth/model/AuthSessionStatus.java backend/src/main/java/com/novelanalyzer/modules/auth/dto/LoginRequest.java backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java
git commit -m "feat: add persisted auth session schema"
```

### Task 2: Build session repository and Redis-backed session service foundation

**Files:**
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthSessionRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/service/RefreshTokenService.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthRepository.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`

- [ ] **Step 1: Write failing tests for session creation and oldest-device lookup**

Add assertions that a login stores:
- one `session_id`
- one `refresh_token_hash`
- `status = ACTIVE`
- non-null `refresh_expire_time`

Also add a test scaffold that prepares 3 active sessions and expects the oldest one to be selected for eviction on the next login.

- [ ] **Step 2: Run the auth controller test to verify RED**

Run: `mvn "-Dtest=AuthControllerTest" test`
Expected: FAIL because repository/service logic does not exist yet.

- [ ] **Step 3: Implement `RefreshTokenService`**

Add helpers for:

```java
String generateOpaqueRefreshToken();
String hashRefreshToken(String token);
String generateSessionId();
```

Use a cryptographically secure random token generator and SHA-256 or stronger hashing.

- [ ] **Step 4: Implement `AuthSessionRepository`**

Add JdbcTemplate methods for:
- insert session
- find active sessions by user
- find active session by `session_id`
- find active session by `refresh_token_hash`
- update session on refresh
- revoke session with reason
- find oldest active session for a user
- update `last_active_time`

- [ ] **Step 5: Implement `AuthSessionService` hot-state foundations**

Add Redis key management for:
- `auth:session:{sid}`
- `auth:refresh:{refreshHash}`
- `auth:user:sessions:{userId}`
- `auth:session:dirty`

Include methods to create a session, revoke a session, update activity, and rehydrate from MySQL on cache miss.

- [ ] **Step 6: Run the auth controller test to verify GREEN**

Run: `mvn "-Dtest=AuthControllerTest" test`
Expected: PASS for session persistence and eviction-selection behavior.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthSessionRepository.java backend/src/main/java/com/novelanalyzer/modules/auth/service/RefreshTokenService.java backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionService.java backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthRepository.java backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java
git commit -m "feat: add auth session repository and redis state"
```

### Task 3: Implement dual-token login with 3-device limit and kick-oldest behavior

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/vo/TokenResponse.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/security/Phase2SecurityIntegrationTest.java`

- [ ] **Step 1: Write the failing device-limit login test**

Add a test that logs in 4 times for the same user with different `deviceLabel` values and asserts:
- only 3 `ACTIVE` sessions remain
- the oldest active one is marked `KICKED`

- [ ] **Step 2: Write the failing cookie issuance test**

Assert that login sends a `Set-Cookie` header with `refresh_token`, `HttpOnly`, `Secure`, and `/api/auth` path.

- [ ] **Step 3: Run auth/security tests to verify RED**

Run: `mvn "-Dtest=AuthControllerTest,Phase2SecurityIntegrationTest" test`
Expected: FAIL because login still only issues access tokens and never evicts devices.

- [ ] **Step 4: Update `AuthService.login(...)`**

Implement:
- active-session count lookup
- oldest-session eviction when count hits 3
- session creation via `AuthSessionService`
- access token issuance with `sid` claim

- [ ] **Step 5: Update `AuthController.login(...)`**

Set the refresh cookie in the response while returning only the access-token JSON body.

- [ ] **Step 6: Keep `TokenResponse` access-token-only**

Do not add refresh token to JSON. Only keep:

```java
private String accessToken;
private String tokenType;
private Long expiresIn;
```

- [ ] **Step 7: Run auth/security tests to verify GREEN**

Run: `mvn "-Dtest=AuthControllerTest,Phase2SecurityIntegrationTest" test`
Expected: PASS for login cookie issuance and device eviction behavior.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java backend/src/main/java/com/novelanalyzer/modules/auth/vo/TokenResponse.java backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java backend/src/test/java/com/novelanalyzer/modules/security/Phase2SecurityIntegrationTest.java
git commit -m "feat: issue dual tokens and enforce device limit"
```

### Task 4: Implement refresh rotation, true logout, and access-token session enforcement

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/security/filter/AuthTokenFilter.java`
- Modify: `backend/src/main/java/com/novelanalyzer/common/utils/JwtUtils.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/security/Phase2SecurityIntegrationTest.java`

- [ ] **Step 1: Write failing refresh-rotation and logout-invalidates-session tests**

Cover:
- refresh consumes the cookie, rotates refresh token, and returns a new access token
- old refresh token cannot be replayed
- logout revokes the current session
- a revoked or kicked `sid` can no longer pass `AuthTokenFilter`

- [ ] **Step 2: Run auth/security tests to verify RED**

Run: `mvn "-Dtest=AuthControllerTest,Phase2SecurityIntegrationTest" test`
Expected: FAIL because refresh still trusts old token flow and filter does not check `sid` state.

- [ ] **Step 3: Implement cookie-based refresh**

In `AuthController.refresh(...)` and `AuthService.refresh(...)`:
- read refresh cookie
- hash lookup via Redis, fallback to MySQL
- verify `ACTIVE`
- rotate to new refresh token and update `refresh_expire_time = now + 7 days`
- set a new cookie

- [ ] **Step 4: Implement logout by session id**

Logout must:
- derive `sid` from the current access token
- revoke the session in MySQL
- delete Redis session keys
- clear the refresh cookie

- [ ] **Step 5: Enforce session validity in `AuthTokenFilter`**

After JWT verification, read `sid` and reject when:
- session missing from Redis and MySQL
- session status is not `ACTIVE`
- refresh expiry has passed
- user id mismatch occurs

- [ ] **Step 6: Add `sid` claim issuance helper if needed**

Update access token creation so every JWT includes the `sid` claim used by the filter.

- [ ] **Step 7: Run auth/security tests to verify GREEN**

Run: `mvn "-Dtest=AuthControllerTest,Phase2SecurityIntegrationTest" test`
Expected: PASS for refresh rotation, replay prevention, and true logout.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java backend/src/main/java/com/novelanalyzer/modules/security/filter/AuthTokenFilter.java backend/src/main/java/com/novelanalyzer/common/utils/JwtUtils.java backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java backend/src/test/java/com/novelanalyzer/modules/security/Phase2SecurityIntegrationTest.java
git commit -m "feat: rotate refresh sessions and enforce sid validation"
```

### Task 5: Add dirty-session flushing and Redis-to-MySQL rehydration safeguards

**Files:**
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionFlushScheduler.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionService.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/novelanalyzer/modules/security/Phase2SecurityIntegrationTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`

- [ ] **Step 1: Write failing tests for Redis-miss rehydration**

Add a test that removes Redis session state after login but leaves MySQL active, then asserts the next protected request succeeds and rehydrates Redis.

- [ ] **Step 2: Write failing tests for dirty active-time persistence**

Add a test that exercises a protected request, triggers the flush path, and asserts `last_active_time` eventually updates in MySQL.

- [ ] **Step 3: Run the targeted backend tests to verify RED**

Run: `mvn "-Dtest=AuthControllerTest,Phase2SecurityIntegrationTest" test`
Expected: FAIL because rehydration and dirty flush do not exist yet.

- [ ] **Step 4: Implement dirty-set tracking**

Whenever a protected request passes session validation, update Redis `lastActiveTime`, update the user-session ZSET score, and add `sid` to `auth:session:dirty`.

- [ ] **Step 5: Implement the scheduler**

Create a scheduled job that:
- scans `auth:session:dirty`
- reads current Redis activity timestamps
- writes `last_active_time` back to MySQL
- removes flushed ids from the dirty set

- [ ] **Step 6: Implement MySQL fallback rehydration**

On Redis miss, load the active session from MySQL and repopulate:
- `auth:session:{sid}`
- `auth:user:sessions:{userId}`
- optional refresh hash mapping when the refresh path is involved

- [ ] **Step 7: Run the targeted backend tests to verify GREEN**

Run: `mvn "-Dtest=AuthControllerTest,Phase2SecurityIntegrationTest" test`
Expected: PASS for Redis rehydration and activity flush behavior.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionService.java backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionFlushScheduler.java backend/src/main/resources/application.yml backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java backend/src/test/java/com/novelanalyzer/modules/security/Phase2SecurityIntegrationTest.java
git commit -m "feat: flush auth session activity and rehydrate from mysql"
```

### Task 6: Replace frontend persistent token snapshots with cookie-based bootstrap recovery

**Files:**
- Create: `frontend/src/lib/auth-bootstrap.ts`
- Create: `frontend/src/lib/__tests__/auth-bootstrap.spec.ts`
- Modify: `frontend/src/lib/auth-session.ts`
- Modify: `frontend/src/utils/storage.ts`
- Modify: `frontend/src/types/auth.ts`
- Modify: `frontend/src/stores/auth.ts`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/router/guards.ts`
- Modify: `frontend/src/router/__tests__/guards.spec.ts`

- [ ] **Step 1: Write failing bootstrap and guard tests**

Cover:
- app startup with no in-memory token calls refresh once
- successful bootstrap stores only the new access token in memory
- failed bootstrap leaves the app logged out
- route guards wait for auth restore before redirecting protected routes

- [ ] **Step 2: Run frontend bootstrap/guard tests to verify RED**

Run:
- `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/router/__tests__/guards.spec.ts`
- `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/stores/__tests__/auth.spec.ts`

Expected: FAIL because startup still depends on `localStorage` snapshots.

- [ ] **Step 3: Implement auth bootstrap helper**

Create `auth-bootstrap.ts` with a function like:

```ts
export async function bootstrapAuthSession() {
  // if no current in-memory session, call authApi.refresh()
  // on success apply access token response
  // on failure clear session and continue logged out
}
```

- [ ] **Step 4: Stop persisting long-lived tokens to `localStorage`**

Refactor `auth-session.ts` and `storage.ts` so they no longer treat localStorage as the primary login source. Keep only minimal transient support if needed for migration, then remove it.

- [ ] **Step 5: Wire bootstrap into app startup and route guards**

Ensure `main.ts` and `router/index.ts` wait for the bootstrap promise before protected navigation decisions are finalized.

- [ ] **Step 6: Run frontend bootstrap/guard tests to verify GREEN**

Run the same Vitest commands from Step 2.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/lib/auth-bootstrap.ts frontend/src/lib/__tests__/auth-bootstrap.spec.ts frontend/src/lib/auth-session.ts frontend/src/utils/storage.ts frontend/src/types/auth.ts frontend/src/stores/auth.ts frontend/src/main.ts frontend/src/router/index.ts frontend/src/router/guards.ts frontend/src/router/__tests__/guards.spec.ts
git commit -m "feat: bootstrap auth from refresh cookie"
```

### Task 7: Update frontend HTTP/auth clients and login/logout UX for cookie-based refresh

**Files:**
- Modify: `frontend/src/lib/http.ts`
- Modify: `frontend/src/api/auth.ts`
- Modify: `frontend/src/views/login/LoginView.vue`
- Modify: `frontend/src/views/login/__tests__/LoginView.spec.ts`
- Modify: `frontend/src/lib/__tests__/http.spec.ts`
- Modify: `frontend/src/stores/__tests__/auth.spec.ts`

- [ ] **Step 1: Write failing HTTP client tests for cookie refresh and one-shot replay**

Cover:
- `/api/auth/refresh` sends `withCredentials: true`
- 401 causes exactly one refresh attempt and request replay
- logout clears session and calls the backend without sending body tokens

- [ ] **Step 2: Run frontend HTTP/auth tests to verify RED**

Run:
- `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/lib/__tests__/http.spec.ts`
- `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/views/login/__tests__/LoginView.spec.ts`

Expected: FAIL because refresh/logout still assume body tokens or persistent storage.

- [ ] **Step 3: Update HTTP client refresh behavior**

Modify `http.ts` so refresh calls:
- use `withCredentials: true`
- do not put the refresh token in the request body
- still replay one failed request after successful refresh

- [ ] **Step 4: Update auth API wrappers**

Make `authApi.login`, `authApi.refresh`, and `authApi.logout` match the cookie-based contract. Login may send optional `deviceLabel`; logout should rely on the current session rather than a token body.

- [ ] **Step 5: Update login view and store flows**

Login view should:
- optionally send a device label (browser + platform summary is fine)
- apply only the access-token JSON response
- rely on the cookie for future refresh

- [ ] **Step 6: Run frontend HTTP/login tests to verify GREEN**

Run the same Vitest commands from Step 2, then also:
- `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/stores/__tests__/auth.spec.ts`

- [ ] **Step 7: Commit**

```bash
git add frontend/src/lib/http.ts frontend/src/api/auth.ts frontend/src/views/login/LoginView.vue frontend/src/views/login/__tests__/LoginView.spec.ts frontend/src/lib/__tests__/http.spec.ts frontend/src/stores/__tests__/auth.spec.ts
git commit -m "feat: switch frontend auth to cookie refresh"
```

### Task 8: Run shared regressions, clean migration leftovers, and document rollout checks

**Files:**
- Modify: `backend/src/test/resources/sql/phase2-data-h2.sql` (only if fixtures need explicit session-aware rows)
- Modify: `frontend/src/router/index.ts` (final cleanup if temporary migration support remains)
- Modify: `README.md` or auth deployment docs if the repo already documents login setup

- [ ] **Step 1: Remove temporary compatibility shims**

Delete any stopgap code that still reads long-lived access tokens from `localStorage` or accepts body refresh tokens only for migration.

- [ ] **Step 2: Run the full targeted backend regression set**

Run:
- `mvn "-Dtest=AuthControllerTest,Phase2SecurityIntegrationTest,LoginRateLimitIntegrationTest" test`
- `mvn -DskipTests test-compile`

Expected: PASS with the shared `phase2-schema-h2.sql` fixture still working across auth, security, crawler, config, and analysis test suites.

- [ ] **Step 3: Run the full targeted frontend regression set**

Run:
- `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/lib/__tests__/http.spec.ts src/lib/__tests__/auth-bootstrap.spec.ts src/router/__tests__/guards.spec.ts src/stores/__tests__/auth.spec.ts src/views/login/__tests__/LoginView.spec.ts`
- `npm run type-check`

Expected: PASS.

- [ ] **Step 4: Document deployment and migration checks**

Capture the required env vars and rollout checks:
- refresh cookie host/path
- same-site/secure expectations
- Redis availability assumptions
- max-device behavior
- rolling restart behavior with MySQL rehydration

- [ ] **Step 5: Commit**

```bash
git add README.md backend/src/test/resources/sql/phase2-data-h2.sql frontend/src/router/index.ts
git commit -m "chore: finalize dual-token auth rollout notes"
```
