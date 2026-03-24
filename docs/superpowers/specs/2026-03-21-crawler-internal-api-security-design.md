# Crawler Internal API Security Design

## Background
- The Python crawler service currently exposes `5000` to the host in `docker-compose.yml`.
- Internal crawler routes under `/internal/*` do not perform any service-to-service authentication.
- The crawler is intended only for backend-to-backend communication and should not be directly reachable by external callers after deployment.

## Goals
- Prevent direct external access to crawler internal APIs in the default deployment topology.
- Add an application-layer authentication control so future accidental port exposure does not immediately expose the crawler APIs.
- Keep the implementation small and consistent with the current Java Spring Boot + Python FastAPI architecture.

## Non-Goals
- No mTLS or service mesh rollout in this phase.
- No public API changes for Java business endpoints.
- No change to crawler data extraction behavior.

## Risks In Current State
1. `crawler` is published to the host with `5000:5000`, which makes reconnaissance and direct invocation possible on exposed environments.
2. `/internal/rank`, `/internal/book`, and `/internal/chapters` trust all callers once they can connect to the service.
3. If a future deployment mistakenly republishes the crawler port, there is no application-level backstop.

## Chosen Approach
Use defense in depth with two controls:

1. Network isolation:
   - Remove the host port mapping for the `crawler` service from the default `docker-compose.yml`.
   - Keep Java-to-Python communication on the internal compose network by service name.

2. Internal service authentication:
   - Introduce a shared secret provided via `CRAWLER_INTERNAL_API_KEY`.
   - Java includes the secret in an internal request header for crawler calls.
   - Python validates that header on `/internal/*`.
   - Missing or invalid secret returns HTTP `401`.

## Detailed Design

### Python Service
- Add `internal_api_key` to crawler settings, sourced from `CRAWLER_INTERNAL_API_KEY`.
- Add a reusable FastAPI dependency or helper that validates `X-Internal-Service-Token`.
- Apply that validation to all `/internal/*` endpoints.
- Keep `/health` unauthenticated, but only return minimal health metadata.
- Add fail-fast startup validation so production-like startup does not run without the internal API key configured.

### Java Backend
- Extend `app.crawler` properties with `internal-api-key`.
- Add fail-fast validation for crawler client configuration, similar to the existing auth config validation style.
- Update `PythonCrawlerClient` so each request carries `X-Internal-Service-Token`.
- Preserve existing fallback behavior when crawler calls fail, but keep authentication failures visible in logs for diagnosis.

### Deployment
- Remove `crawler` host port exposure from the default compose file.
- Inject the same `CRAWLER_INTERNAL_API_KEY` into both `backend` and `crawler`.
- Keep `CRAWLER_BASE_URL=http://crawler:5000` for internal service discovery.

## Error Handling
- Python returns `401` for missing or invalid internal tokens.
- Java keeps its current local fallback behavior if the crawler call fails, avoiding business endpoint hard failure during crawler issues.
- Startup validation should stop either side from launching with a blank internal key in normal deployments.

## Testing Strategy

### Python
- Add API tests for:
  - unauthenticated `/internal/*` request returns `401`
  - wrong token returns `401`
  - correct token reaches the route and returns `200`
  - `/health` remains available without token

### Java
- Add client tests proving:
  - crawler requests include the internal auth header
  - config validation rejects missing crawler internal key

### End-to-End Validation
- Run crawler Python unit tests.
- Run targeted backend tests for crawler client/config.
- Run backend `mvn test` for final regression evidence.

## Security Outcome
- The crawler is no longer externally published in the default deployment.
- Even if network exposure is reintroduced later, `/internal/*` still requires a shared secret.
- The control surface remains small, explicit, and compatible with the current codebase.
