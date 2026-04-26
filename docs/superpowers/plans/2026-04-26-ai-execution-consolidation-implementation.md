# AI Execution Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate AI execution responsibilities from Java legacy provider calls into the Python langgraph worker while freezing all existing JSON contracts, preserving model/provider/fallback behavior, and keeping Java responsible for orchestration, normalization, persistence, caching, and SSE output.

**Architecture:** Java remains the business orchestration layer and continues to resolve prompt/model/runtime metadata, query domain data, normalize trend results, attach result metadata, persist outputs, and serve SSE to the frontend. Python becomes the single AI execution layer responsible for provider invocation, provider fallback, chunk/merge execution, JSON repair, and final fallback result generation, but it must continue consuming the exact same prompt/model JSON payload fields already sent by Java.

**Tech Stack:** Spring Boot 3, Java 17, MyBatis-Plus, LangChain4j (legacy removal target), FastAPI, LangGraph, httpx, Vue 3, Element Plus, JUnit 5, unittest

---

### Task 1: Lock Contract Invariants With Regression Tests

**Files:**
- Modify: `backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/analysis/service/AnalysisServiceTimeoutTest.java`
- Modify: `langgraph-worker/tests/test_analysis_service.py`
- Modify: `langgraph-worker/tests/test_analysis_api.py`
- Test: `frontend/src/views/trend/__tests__/TrendView.spec.ts`
- Test: `frontend/src/views/analysis/__tests__/AnalysisView.spec.ts`

- [ ] **Step 1: Write the failing backend contract test for prompt payload passthrough**

Add assertions around the worker request payload to verify Java still passes these fields unchanged:

```json
{
  "inputJsonSchema": "...",
  "inputExampleJson": "...",
  "outputJsonSchema": "...",
  "outputExampleJson": "...",
  "parseConfigJson": "...",
  "postProcessType": "json_extract"
}
```

Target an existing integration test that already mocks the LangGraph worker request body.

- [ ] **Step 2: Run the focused backend integration test and verify current behavior**

Run:

```powershell
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest" test
```

Expected: PASS or existing failures unrelated to the new assertions. Fix the test scaffold until it accurately locks the request payload contract.

- [ ] **Step 3: Write the failing single-book result metadata compatibility test**

In `AnalysisServiceTimeoutTest.java`, add assertions that the final `resultJson` seen by Java still contains:

- `analysisMode`
- `segmentCount`
- `requestedChapterCount`
- `actualChapterCount`
- `inputChapterCount`
- `chapterFetchDegraded`
- `promptRuntime`

Do not change production code yet.

- [ ] **Step 4: Run the focused single-book service test**

Run:

```powershell
cd backend
mvn "-Dtest=AnalysisServiceTimeoutTest" test
```

Expected: PASS or expose places where tests need better fixture setup. The goal is to freeze the result shape before migration.

- [ ] **Step 5: Write the failing Python-side structured-output contract test**

Add or extend `langgraph-worker/tests/test_analysis_service.py` to assert:

- theme analysis still includes output schema guidance
- JSON repair still triggers for invalid theme JSON
- default theme result includes the expected structured keys

At minimum verify presence of:

- `boardSummary`
- `trendPreview`
- `historicalWordCloud`
- `themeDistribution`
- `hotBooks`
- `insightCards`
- `snapshotComparisons`

- [ ] **Step 6: Run the focused Python tests**

Run:

```powershell
cd langgraph-worker
python -m unittest tests.test_analysis_service tests.test_analysis_api -v
```

Expected: PASS or fail only on the new assertions that reveal missing coverage.

- [ ] **Step 7: Write the frontend compatibility assertions**

Add or extend tests so that:

- trend page still renders from the existing `resultJson` field names
- analysis page still renders single-book metadata from the existing `resultJson` field names

Do not add any new field names.

- [ ] **Step 8: Run the focused frontend tests**

Run:

```powershell
cd frontend
npm test -- --run src/views/trend/__tests__/TrendView.spec.ts src/views/analysis/__tests__/AnalysisView.spec.ts
```

Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java backend/src/test/java/com/novelanalyzer/modules/analysis/service/AnalysisServiceTimeoutTest.java langgraph-worker/tests/test_analysis_service.py langgraph-worker/tests/test_analysis_api.py frontend/src/views/trend/__tests__/TrendView.spec.ts frontend/src/views/analysis/__tests__/AnalysisView.spec.ts
git commit -m "test: freeze ai json contract invariants before execution migration"
```

### Task 2: Add Python Provider Routing And Final Fallback Result

**Files:**
- Modify: `langgraph-worker/app/services/provider_client.py`
- Modify: `langgraph-worker/app/services/analysis_service.py`
- Create or Modify: `langgraph-worker/app/config.py`
- Test: `langgraph-worker/tests/test_provider_client.py`
- Test: `langgraph-worker/tests/test_analysis_service.py`

- [ ] **Step 1: Write the failing provider fallback tests**

In `test_provider_client.py`, add tests that describe the target Python behavior:

- when primary provider is `dify`, worker tries Dify first then OpenAI-compatible
- when primary provider is not `dify`, worker tries OpenAI-compatible first then Dify
- when both providers fail, the worker returns a final fallback result payload rather than raw failure

Use fakes/mocks instead of real network calls.

- [ ] **Step 2: Run the focused provider tests to verify they fail**

Run:

```powershell
cd langgraph-worker
python -m unittest tests.test_provider_client -v
```

Expected: FAIL because Python currently only implements OpenAI-compatible provider execution.

- [ ] **Step 3: Implement provider routing abstraction in Python**

Refactor `provider_client.py` so it can:

- resolve provider execution order from request/config
- execute OpenAI-compatible calls
- execute Dify blocking calls
- surface provider-specific failures without breaking the request contract

Do not change the `RunRequest` contract coming from Java.

- [ ] **Step 4: Implement final fallback result generation in Python**

Mirror Java’s final fallback idea:

- if all providers fail, return a compact synthetic result
- keep the result shape compatible with current consumers
- for theme analysis, ensure the structured keys still exist

The fallback result must not change top-level field names expected by Java or frontend.

- [ ] **Step 5: Run the focused Python tests and make them pass**

Run:

```powershell
cd langgraph-worker
python -m unittest tests.test_provider_client tests.test_analysis_service -v
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add langgraph-worker/app/services/provider_client.py langgraph-worker/app/services/analysis_service.py langgraph-worker/tests/test_provider_client.py langgraph-worker/tests/test_analysis_service.py langgraph-worker/app/config.py
git commit -m "feat: add python provider fallback and final ai fallback result"
```

### Task 3: Align Python Execution Semantics With Java Legacy Chunk And Merge

**Files:**
- Modify: `langgraph-worker/app/services/analysis_service.py`
- Test: `langgraph-worker/tests/test_analysis_service.py`
- Reference: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`

- [ ] **Step 1: Write the failing chunk/merge parity tests**

Add Python tests that verify:

- long single-book input enters chunk mode
- merged result includes `analysisMode = chunk_merge`
- merged result includes `segmentCount`
- merged token usage includes chunk + merge work

Also verify the merge prompt preserves structured-output constraints when needed.

- [ ] **Step 2: Run the focused Python chunk tests**

Run:

```powershell
cd langgraph-worker
python -m unittest tests.test_analysis_service -v
```

Expected: FAIL on at least one parity gap or missing assertion.

- [ ] **Step 3: Adjust Python chunk/merge semantics without changing contracts**

Bring Python behavior closer to Java legacy where needed:

- chunk splitting thresholds
- merge prompt behavior
- final metadata fields
- runtime metrics accumulation

Do not rename any JSON result fields.

- [ ] **Step 4: Run the focused Python tests again**

Run:

```powershell
cd langgraph-worker
python -m unittest tests.test_analysis_service -v
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add langgraph-worker/app/services/analysis_service.py langgraph-worker/tests/test_analysis_service.py
git commit -m "feat: align python chunk merge behavior with legacy java execution"
```

### Task 4: Route Java Single-Book Legacy Execution Through Python Worker

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/client/LangGraphWorkerClient.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/analysis/client/LangGraphWorkerClientTest.java`

- [ ] **Step 1: Write the failing integration test for legacy-mode single-book Python routing**

Add or extend a backend integration test so that when runtime mode is `legacy-compatible-python` or the transitional legacy path is enabled, single-book analysis still:

- uses the worker request payload
- returns the same API response shape
- preserves `resultJson` metadata fields

If introducing a new transitional mode is too invasive, target the current `legacy` branch and update the expectation to “worker call occurs”.

- [ ] **Step 2: Run the focused backend tests to verify they fail**

Run:

```powershell
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest,LangGraphWorkerClientTest" test
```

Expected: FAIL because legacy single-book execution still uses `AiGatewayService` directly.

- [ ] **Step 3: Change the legacy single-book execution branch to use the worker**

In `AnalysisService.java`:

- keep Java-side prompt/model resolution
- keep Java-side metadata attachment
- keep Java-side persistence/cache/SSE exit
- replace direct provider execution with worker calls for the transitional legacy path

Do not remove Java trend normalization or result metadata logic.

- [ ] **Step 4: Keep the frontend-visible API unchanged**

Ensure:

- blocking analysis endpoints still return the same `AnalysisResultVO`
- streaming endpoints still emit `start/delta/done/error`
- no frontend file changes are required in this task

- [ ] **Step 5: Run the focused backend tests again**

Run:

```powershell
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest,LangGraphWorkerClientTest" test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java backend/src/main/java/com/novelanalyzer/modules/analysis/client/LangGraphWorkerClient.java backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java backend/src/test/java/com/novelanalyzer/modules/analysis/client/LangGraphWorkerClientTest.java
git commit -m "refactor: route legacy single book execution through python worker"
```

### Task 5: Preserve Java Trend Normalization While Unifying Execution Through Python

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java`
- Test: `frontend/src/views/trend/__tests__/TrendView.spec.ts`

- [ ] **Step 1: Write the failing trend regression test**

Add assertions that worker-based trend execution still ends with Java-side normalization and preserves:

- `boardSummary`
- `trendPreview`
- `historicalWordCloud`
- `themeDistribution`
- `hotBooks`
- `insightCards`
- `snapshotComparisons`

- [ ] **Step 2: Run the focused trend tests**

Run:

```powershell
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest" test

cd ..\frontend
npm test -- --run src/views/trend/__tests__/TrendView.spec.ts
```

Expected: PASS or expose missing normalization assertions that must be added before migration continues.

- [ ] **Step 3: Audit and preserve the Java-side normalization boundary**

Make only minimal code changes needed to guarantee:

- worker output still passes through `normalizeTrendResultJson(...)`
- Java remains the final contract stabilizer before persistence and API response

Do not move this normalization logic into Python in this task.

- [ ] **Step 4: Re-run the focused trend tests**

Run:

```powershell
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest" test

cd ..\frontend
npm test -- --run src/views/trend/__tests__/TrendView.spec.ts
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java frontend/src/views/trend/__tests__/TrendView.spec.ts
git commit -m "test: preserve java trend normalization across python execution"
```

### Task 6: Flip The Default Runtime To LangGraph After Capability Parity

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/service/SystemConfigService.java`
- Modify: `backend/src/main/resources/application.yml` only if needed for documentation clarity, not behavior drift
- Test: `backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java`

- [ ] **Step 1: Write the failing default-runtime test**

Add a focused test that expects the default `analysis.runtime.mode` to resolve to `langgraph` once the migration parity tasks are complete.

- [ ] **Step 2: Run the focused config/runtime tests and verify failure**

Run:

```powershell
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest,Phase5BackendIntegrationTest" test
```

Expected: FAIL because the default is still `legacy`.

- [ ] **Step 3: Change the default runtime value**

Update the default config entry for:

- `analysis.runtime.mode`

from `legacy` to `langgraph`.

Do not remove the config key. Keep explicit override capability for rollback.

- [ ] **Step 4: Run the focused backend tests again**

Run:

```powershell
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest,Phase5BackendIntegrationTest" test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/config/service/SystemConfigService.java backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java backend/src/main/resources/application.yml
git commit -m "feat: default analysis runtime to langgraph"
```

### Task 7: Remove Java Direct Provider Execution Code

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/analysis/service/AiGatewayServiceTest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/analysis/service/AnalysisServiceTimeoutTest.java`

- [ ] **Step 1: Write the failing cleanup test**

Add or adapt tests to assert Java no longer uses direct provider execution paths for production analysis flows.

Keep tests for any utility behavior that remains intentionally in Java.

- [ ] **Step 2: Run the focused cleanup tests**

Run:

```powershell
cd backend
mvn "-Dtest=AiGatewayServiceTest,AnalysisServiceTimeoutTest" test
```

Expected: FAIL because the direct provider execution path still exists.

- [ ] **Step 3: Remove or shrink Java direct execution paths**

Delete or neutralize:

- direct OpenAI-compatible execution path
- direct Dify execution path
- direct legacy provider fallback execution path

Retain only Java logic that is still needed for:

- prompt rendering helpers, if still referenced
- token estimation helpers, if still referenced
- result metadata attachment helpers, if still referenced

If the remaining surface is too small, fold helpers into better-owned classes rather than keeping a misleading execution service.

- [ ] **Step 4: Run the focused cleanup tests again**

Run:

```powershell
cd backend
mvn "-Dtest=AiGatewayServiceTest,AnalysisServiceTimeoutTest" test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java backend/src/test/java/com/novelanalyzer/modules/analysis/service/AiGatewayServiceTest.java backend/src/test/java/com/novelanalyzer/modules/analysis/service/AnalysisServiceTimeoutTest.java
git commit -m "refactor: remove java direct ai provider execution paths"
```

### Task 8: Full Regression Across Backend, Worker, And Frontend

**Files:**
- No production changes required unless regression fixes are needed
- Test: `backend/src/test/java/com/novelanalyzer/modules/analysis/...`
- Test: `langgraph-worker/tests/...`
- Test: `frontend/src/views/trend/__tests__/TrendView.spec.ts`
- Test: `frontend/src/views/analysis/__tests__/AnalysisView.spec.ts`

- [ ] **Step 1: Run backend AI regression**

Run:

```powershell
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest,LangGraphWorkerClientTest,AiGatewayServiceTest,AnalysisServiceTimeoutTest,Phase5BackendIntegrationTest" test
```

Expected: PASS

- [ ] **Step 2: Run Python worker regression**

Run:

```powershell
cd ..\langgraph-worker
python -m unittest discover -s tests -v
```

Expected: PASS

- [ ] **Step 3: Run frontend contract regression**

Run:

```powershell
cd ..\frontend
npm test -- --run src/views/trend/__tests__/TrendView.spec.ts src/views/analysis/__tests__/AnalysisView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts
```

Expected: PASS

- [ ] **Step 4: If available, run a manual local smoke flow**

Verify at least:

- single-book deconstruct stream
- single-book structure stream
- trend stream
- history replay

using the local stack or mock-backed equivalent.

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "test: verify ai execution consolidation end to end"
```

## Notes For Implementers

1. Treat every JSON contract field as frozen. If a test makes you want to rename or reshape a field, the plan is wrong or the implementation is taking the wrong shortcut.
2. Do not migrate Java trend normalization into Python. Java remains the contract stabilizer for trend persistence and display compatibility.
3. Do not let Python choose a “better” model than Java resolved. Java owns model selection and fallback ordering at the configuration layer.
4. Preserve SSE event semantics. Frontend should remain unaware of whether Java or Python performed the final model call.
5. Preserve rollback safety. Even after the default runtime flips to `langgraph`, an explicit config override should still be possible until Java direct execution is fully removed.
