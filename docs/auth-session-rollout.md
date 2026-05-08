# Auth Session Rollout

## Current Auth Model

- Access token: short-lived JWT
- Refresh token: HttpOnly cookie
- Session source of truth:
  - Redis for hot online state
  - MySQL `sys_user_session` for persistence and recovery

## Security Properties

- Access token expiry defaults to 900 seconds
- Refresh token expiry defaults to 7 days
- Max active devices per user defaults to 3
- Refresh cookie defaults:
  - name: `refresh_token`
  - path: `/api/auth`
  - secure: `true`
  - same-site: `Strict`

## Operational Notes

- Login and register both issue:
  - access token in JSON
  - refresh token in HttpOnly cookie
- Refresh rotates the refresh token and invalidates the old one
- Logout revokes the current session and clears the refresh cookie
- If Redis hot state is missing, session state is rehydrated from MySQL
- Dirty session activity is flushed from Redis back to MySQL by the scheduler

## Recommended Verification

- Backend auth/security:
  - `mvn "-Dtest=AuthSessionServiceTest,AuthControllerTest,Phase2SecurityIntegrationTest" "-Dsurefire.forkCount=0" test`
- Frontend auth/bootstrap:
  - `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/lib/__tests__/auth-bootstrap.spec.ts src/router/__tests__/guards.spec.ts src/stores/__tests__/auth.spec.ts src/utils/__tests__/storage.spec.ts src/views/login/__tests__/LoginView.spec.ts`
- Frontend auth/http:
  - `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/lib/__tests__/http.spec.ts src/views/login/__tests__/LoginView.spec.ts src/stores/__tests__/auth.spec.ts`

## Rollout Checklist

- Set `JWT_SECRET` to a production secret of at least 32 characters
- Ensure Redis is available before enabling auth session enforcement in production
- Verify browser and API are deployed same-site so refresh cookies are sent correctly
- Verify HTTPS is enabled, otherwise `Secure` cookies will not be sent
- Confirm old refresh cookies are cleared on refresh failure and logout
