# Model-Bound Prompt Template Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support prompt template selection per `modelKey × analysisType`, with fallback order `current model binding -> deepseek-chat binding -> default`.

**Architecture:** Keep `prompt_config` as the template library keyed by `prompt_type + prompt_name`, and extend `ai.model-registry.json` so each model can bind one template name per analysis type. Resolve the effective template at runtime before analysis, inherit JSON contract fields from `default` when model-specific templates omit them, and include both `modelKey` and resolved `prompt_name` in analysis cache keys.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, Vue 3, Element Plus, MySQL JSON config, Vitest, JUnit 5

---

### Task 1: Extend Model Registry Types For Prompt Bindings

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/vo/AiModelRegistryModelVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/dto/AiModelRegistryModelRequest.java`
- Modify: `frontend/src/types/config.ts`
- Test: `backend/src/test/java/com/novelanalyzer/modules/config/SystemConfigServiceTest.java` or add focused registry serialization tests if needed

- [ ] **Step 1: Write the failing test**

Add/extend a backend test that serializes/deserializes model registry JSON with:

```json
{
  "promptBindings": {
    "deconstruct": "kimi-k2.5",
    "structure": "default"
  }
}
```

and asserts the binding map survives round-trip parsing.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=SystemConfigServiceTest" test`
Expected: FAIL because `promptBindings` is not yet present on registry DTO/VO types.

- [ ] **Step 3: Write minimal implementation**

Add `Map<String, String> promptBindings` to:
- `AiModelRegistryModelVO`
- `AiModelRegistryModelRequest`
- frontend `AiModelRegistryModel` and `AiModelRegistryUpdateRequest`

Ensure `SystemConfigService` copies the field in:
- parse/normalize flows
- sanitize flows
- merge update flows

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=SystemConfigServiceTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/config/vo/AiModelRegistryModelVO.java backend/src/main/java/com/novelanalyzer/modules/config/dto/AiModelRegistryModelRequest.java frontend/src/types/config.ts
git commit -m "feat: add model prompt binding metadata"
```

### Task 2: Add Prompt Template Library Queries And List API

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/repository/PromptConfigRepository.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/controller/PromptConfigController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/vo/PromptConfigVO.java`
- Modify: `frontend/src/api/config.ts`
- Modify: `frontend/src/types/config.ts`
- Test: `backend/src/test/java/com/novelanalyzer/modules/config/PromptConfigServiceTest.java` (create if missing)

- [ ] **Step 1: Write the failing test**

Add a backend service/controller test that requests all active templates for a `promptType` and expects:
- multiple template names returned
- `default`, `deepseek-chat`, `kimi-k2.5` style names supported

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=PromptConfigServiceTest" test`
Expected: FAIL because there is no list/query API.

- [ ] **Step 3: Write minimal implementation**

Add repository methods:
- find active template by `promptType + promptName`
- list active templates by `promptType`
- list active template names by `promptType`

Add service/controller API:
- `GET /api/config/prompt/templates?promptType=...`

Return enough metadata for dropdown use:
- `id`
- `promptType`
- `promptName`
- `modelName`
- `isDefault`

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=PromptConfigServiceTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/config/repository/PromptConfigRepository.java backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java backend/src/main/java/com/novelanalyzer/modules/config/controller/PromptConfigController.java frontend/src/api/config.ts frontend/src/types/config.ts
git commit -m "feat: add prompt template library queries"
```

### Task 3: Resolve Effective Prompt Template At Runtime

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/analysis/service/AnalysisServiceTimeoutTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/analysis/service/AiGatewayServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add/extend tests to cover:
- when `user preferred model = kimi-k2.5`, analysis resolves `prompt_name = kimi-k2.5`
- if Kimi binding missing, runtime falls back to `deepseek-chat`
- if both bindings missing, runtime falls back to `default`
- fallback result metadata uses actual runtime model key

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=AiGatewayServiceTest,AnalysisServiceTimeoutTest" test`
Expected: FAIL because runtime still picks prompt only by `prompt_type`.

- [ ] **Step 3: Write minimal implementation**

Introduce a template resolution method that:
- reads current user preferred model
- loads model registry bindings
- resolves prompt name in this order:
  - current model binding
  - `deepseek-chat` binding
  - `default`
- fetches prompt template by `promptType + promptName`

Keep JSON contract inheritance behavior:
- if selected model-specific template lacks contract fields, merge from `default`

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=AiGatewayServiceTest,AnalysisServiceTimeoutTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java backend/src/test/java/com/novelanalyzer/modules/analysis/service/AiGatewayServiceTest.java backend/src/test/java/com/novelanalyzer/modules/analysis/service/AnalysisServiceTimeoutTest.java
git commit -m "feat: resolve prompts by model binding with deepseek fallback"
```

### Task 4: Fix Cache Keys For Model/Template Switching

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/analysis/service/AnalysisServiceTimeoutTest.java`

- [ ] **Step 1: Write the failing test**

Add a test asserting that `buildAnalysisCacheKey(...)` changes when:
- `ai.preferred-model` changes
- resolved `prompt_name` changes

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=AnalysisServiceTimeoutTest" test`
Expected: FAIL if cache key still ignores resolved prompt name or user model.

- [ ] **Step 3: Write minimal implementation**

Update analysis/trend cache keys to include:
- effective `modelKey`
- effective `prompt_name`
- existing prompt signature

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=AnalysisServiceTimeoutTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java backend/src/test/java/com/novelanalyzer/modules/analysis/service/AnalysisServiceTimeoutTest.java
git commit -m "fix: partition analysis cache by model and template"
```

### Task 5: Add Prompt Template Dropdown To Prompt Config UI

**Files:**
- Modify: `frontend/src/views/config/prompt/PromptConfigView.vue`
- Modify: `frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts`
- Modify: `frontend/src/api/config.ts`
- Modify: `frontend/src/types/config.ts`

- [ ] **Step 1: Write the failing test**

Add a frontend test verifying:
- selecting `promptType` loads template list
- template dropdown shows multiple names
- switching template name loads the corresponding template data

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --run src/views/config/prompt/__tests__/PromptConfigView.spec.ts`
Expected: FAIL because there is no template dropdown/list API usage.

- [ ] **Step 3: Write minimal implementation**

In `PromptConfigView.vue`:
- fetch template list on type change
- show template-name dropdown
- allow creating/saving new template names
- keep contract section, but label it as inherited/shared by default

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- --run src/views/config/prompt/__tests__/PromptConfigView.spec.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/config/prompt/PromptConfigView.vue frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts frontend/src/api/config.ts frontend/src/types/config.ts
git commit -m "feat: add prompt template selector to prompt config UI"
```

### Task 6: Add Model Prompt Binding Dropdowns To System Config UI

**Files:**
- Modify: `frontend/src/views/config/system/SystemConfigView.vue`
- Modify: `frontend/src/views/config/system/__tests__/SystemConfigView.spec.ts`
- Modify: `frontend/src/types/config.ts`

- [ ] **Step 1: Write the failing test**

Add a frontend test verifying that each model card renders four template-binding dropdowns and includes selected values in the save payload.

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --run src/views/config/system/__tests__/SystemConfigView.spec.ts`
Expected: FAIL because the UI does not expose prompt bindings yet.

- [ ] **Step 3: Write minimal implementation**

In `SystemConfigView.vue`:
- load prompt template names grouped by `promptType`
- render dropdowns for `deconstruct/structure/plot/theme`
- persist `promptBindings` through `updateModelRegistry`

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- --run src/views/config/system/__tests__/SystemConfigView.spec.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/config/system/SystemConfigView.vue frontend/src/views/config/system/__tests__/SystemConfigView.spec.ts frontend/src/types/config.ts
git commit -m "feat: bind prompt templates per model in system config UI"
```

### Task 7: Seed And Migration Compatibility

**Files:**
- Modify: `backend/sql/mysql/phase4-seed.sql`
- Modify: `backend/sql/mysql/phase5-seed.sql`
- Modify: `backend/src/test/resources/sql/phase4-data-h2.sql`
- Modify: `backend/src/test/resources/sql/phase5-data-h2.sql`
- Test: relevant schema/config integration tests

- [ ] **Step 1: Write the failing test**

Add/adjust seed compatibility tests so they expect `default` prompt templates and model registry prompt bindings.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=PromptConfigSchemaCompatibilityIntegrationTest,Phase5BackendIntegrationTest" test`
Expected: FAIL until seeds and compatibility assumptions match new design.

- [ ] **Step 3: Write minimal implementation**

Update seed data so:
- existing templates are normalized to `prompt_name = default`
- model registry includes DeepSeek bindings
- optional Kimi example bindings are present where useful

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=PromptConfigSchemaCompatibilityIntegrationTest,Phase5BackendIntegrationTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/sql/mysql/phase4-seed.sql backend/sql/mysql/phase5-seed.sql backend/src/test/resources/sql/phase4-data-h2.sql backend/src/test/resources/sql/phase5-data-h2.sql
git commit -m "chore: seed default prompt templates and model bindings"
```
