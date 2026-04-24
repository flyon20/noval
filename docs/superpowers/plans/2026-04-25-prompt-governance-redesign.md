# Prompt Governance Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement admin draft/publish prompt governance, user-owned prompt copies and bindings, and runtime resolution/history so global prompt rollout and user fallback behavior match the approved spec.

**Architecture:** Extend the existing `prompt_config` library with scope and ownership, add explicit publish/version and user binding/history tables, then centralize runtime prompt resolution behind a dedicated governance resolver. Split admin and user prompt APIs/DTOs so contract fields stay admin-only and user operations remain constrained and auditable.

**Tech Stack:** Spring Boot 3.2, MyBatis-Plus, MySQL 8, Vue 3, Vite, Vitest, JUnit

---

## File Map

### Database

- Modify: `backend/sql/mysql/phase4-schema.sql`
- Modify: `backend/sql/mysql/phase5-schema.sql`
- Modify: `backend/sql/mysql/phase5-seed.sql`
- Modify: `backend/src/test/resources/sql/phase4-schema-h2.sql`
- Modify: `backend/src/test/resources/sql/phase5-data-h2.sql`

### Backend config / prompt governance

- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptConfigEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptPublishVersionEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptPublishItemEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/model/UserPromptBindingEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/model/UserPromptEffectiveHistoryEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/mapper/PromptPublishVersionMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/mapper/PromptPublishItemMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/mapper/UserPromptBindingMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/mapper/UserPromptEffectiveHistoryMapper.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/repository/PromptConfigRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/repository/PromptPublishRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/repository/UserPromptBindingRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/repository/UserPromptEffectiveHistoryRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptGovernanceService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/controller/PromptConfigController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`

### Backend DTO / VO

- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/dto/PromptConfigUpdateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/AdminPromptConfigUpdateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/UserPromptCopyUpdateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/UserPromptCopyCreateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/UserPromptBindingUpdateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/PromptPublishRequest.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/vo/PromptConfigVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/vo/PromptPublishVersionVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/vo/UserPromptBindingVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/vo/UserPromptEffectiveHistoryVO.java`

### Frontend

- Modify: `frontend/src/types/config.ts`
- Modify: `frontend/src/api/config.ts`
- Modify: `frontend/src/views/config/prompt/PromptConfigView.vue`
- Create: `frontend/src/views/config/prompt/UserPromptConfigView.vue`
- Create: `frontend/src/views/config/prompt/__tests__/UserPromptConfigView.spec.ts`
- Modify: `frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts`
- Modify: `frontend/src/router/index.ts`

### Tests

- Create: `backend/src/test/java/com/novelanalyzer/sql/MySqlPromptGovernanceSchemaTest.java`
- Create: `backend/src/test/java/com/novelanalyzer/modules/config/PromptGovernanceRepositoryTest.java`
- Create: `backend/src/test/java/com/novelanalyzer/modules/config/PromptGovernanceServiceTest.java`
- Create: `backend/src/test/java/com/novelanalyzer/modules/config/PromptPublishIntegrationTest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/config/PromptConfigServiceTest.java`

## Task 1: Add schema for scoped prompts, publish versions, and user binding/history

**Files:**
- Modify: `backend/sql/mysql/phase4-schema.sql`
- Modify: `backend/sql/mysql/phase5-schema.sql`
- Modify: `backend/src/test/resources/sql/phase4-schema-h2.sql`
- Create: `backend/src/test/java/com/novelanalyzer/sql/MySqlPromptGovernanceSchemaTest.java`

- [ ] **Step 1: Write the failing schema tests**

Add assertions in schema tests for:
- new `prompt_config` columns:
  - `scope_type`
  - `owner_user_id`
  - `source_prompt_config_id`
- new tables:
  - `prompt_publish_version`
  - `prompt_publish_item`
  - `user_prompt_binding`
  - `user_prompt_effective_history`

- [ ] **Step 2: Run schema tests to verify they fail**

Run:
```bash
cd backend
mvn "-Dtest=MySqlPromptGovernanceSchemaTest" test
```

Expected:
- FAIL because required columns/tables do not exist yet

- [ ] **Step 3: Implement minimal schema changes**

Update MySQL and H2 schema scripts with:
- additive columns on `prompt_config`
- new publish/binding/history tables
- unique/index constraints needed by runtime resolution

- [ ] **Step 4: Run schema tests to verify they pass**

Run:
```bash
cd backend
mvn "-Dtest=MySqlPromptGovernanceSchemaTest" test
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add backend/sql/mysql/phase4-schema.sql backend/sql/mysql/phase5-schema.sql backend/src/test/resources/sql/phase4-schema-h2.sql backend/src/test/java/com/novelanalyzer/sql/MySqlPromptGovernanceSchemaTest.java
git commit -m "feat: add prompt governance schema"
```

## Task 2: Model scoped prompt entities, mappers, and repositories

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptConfigEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptPublishVersionEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptPublishItemEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/model/UserPromptBindingEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/model/UserPromptEffectiveHistoryEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/mapper/PromptPublishVersionMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/mapper/PromptPublishItemMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/mapper/UserPromptBindingMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/mapper/UserPromptEffectiveHistoryMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/repository/PromptPublishRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/repository/UserPromptBindingRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/repository/UserPromptEffectiveHistoryRepository.java`
- Create: `backend/src/test/java/com/novelanalyzer/modules/config/PromptGovernanceRepositoryTest.java`

- [ ] **Step 1: Write repository-level failing tests for new records**

Add tests that prove repositories can:
- save and read publish versions/items
- save and read user bindings
- save and read effective history rows

- [ ] **Step 2: Run repository tests to verify they fail**

Run:
```bash
cd backend
mvn "-Dtest=PromptGovernanceRepositoryTest" test
```

Expected:
- FAIL because repositories/entities do not exist yet

- [ ] **Step 3: Implement entities, mappers, and repositories**

Use existing MyBatis-Plus patterns from config/auth modules.

- [ ] **Step 4: Run repository tests to verify they pass**

Run:
```bash
cd backend
mvn "-Dtest=PromptGovernanceRepositoryTest" test
```

Expected:
- PASS for repository setup scenarios

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/config/model backend/src/main/java/com/novelanalyzer/modules/config/mapper backend/src/main/java/com/novelanalyzer/modules/config/repository backend/src/test/java/com/novelanalyzer/modules/config/PromptGovernanceRepositoryTest.java
git commit -m "feat: add prompt governance repositories"
```

## Task 3: Implement admin draft and publish workflow

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptGovernanceService.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/AdminPromptConfigUpdateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/PromptPublishRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/vo/PromptPublishVersionVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/controller/PromptConfigController.java`

- [ ] **Step 1: Write failing service/controller tests**

Cover:
- admin can save system draft
- admin cannot rename/delete `SYSTEM + default`
- publish requires all four prompt types
- publish creates one version and one item per type
- normal users cannot access admin draft/publish endpoints
- normal users cannot modify admin-only fields through admin payloads

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd backend
mvn "-Dtest=PromptPublishIntegrationTest" test
```

Expected:
- FAIL because publish flow is not implemented

- [ ] **Step 3: Implement minimal admin governance flow**

Implement:
- system-template save APIs
- publish API
- current publish lookup
- publish history lookup

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd backend
mvn "-Dtest=PromptPublishIntegrationTest" test
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/config/service backend/src/main/java/com/novelanalyzer/modules/config/controller backend/src/main/java/com/novelanalyzer/modules/config/dto backend/src/main/java/com/novelanalyzer/modules/config/vo backend/src/test/java/com/novelanalyzer/modules/config/PromptPublishIntegrationTest.java
git commit -m "feat: add admin prompt publish workflow"
```

## Task 4: Implement user copy, binding, fallback-aware runtime resolution, and effective-history metadata

**Files:**
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/UserPromptCopyUpdateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/UserPromptCopyCreateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/dto/UserPromptBindingUpdateRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/vo/UserPromptBindingVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/config/vo/UserPromptEffectiveHistoryVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptGovernanceService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/controller/PromptConfigController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/dto/PromptConfigUpdateRequest.java`

- [ ] **Step 1: Write failing runtime and permission tests**

Cover:
- user can copy published system template
- user cannot edit JSON contract fields
- user only sees published system templates + own copies
- runtime uses user copy when valid
- runtime falls back to published global when copy invalid
- runtime falls back to system `default` if published global missing
- runtime throws a controlled configuration failure when both published global and system `default` are unavailable
- effective history rows are written across publish transitions
- runtime metadata carries:
  - `userId`
  - `promptType`
  - selected model key
  - effective prompt config id
  - effective source
  - active publish version id
  - fallback marker when applicable
- binding changes persist `last_selected_prompt_config_id`

- [ ] **Step 2: Run targeted tests to verify they fail**

Run:
```bash
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest,PromptGovernanceServiceTest" test
```

Expected:
- FAIL because user prompt governance and resolver logic do not exist yet

- [ ] **Step 3: Implement minimal user copy/binding flow and runtime resolver**

Notes:
- remove admin-only JSON contract fields from user write path
- centralize effective prompt resolution in one service
- have `AnalysisService` call the new resolver instead of current direct prompt selection logic
- make sure effective-history writes are triggered when:
  - a user binding changes
  - a published version changes and the user next resolves a prompt

- [ ] **Step 4: Run targeted tests to verify they pass**

Run:
```bash
cd backend
mvn "-Dtest=Phase4AnalysisIntegrationTest,PromptGovernanceServiceTest" test
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/config backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java backend/src/test/java/com/novelanalyzer/modules/config/PromptGovernanceServiceTest.java backend/src/test/java/com/novelanalyzer/modules/analysis/Phase4AnalysisIntegrationTest.java
git commit -m "feat: add user prompt binding and runtime fallback"
```

## Task 5: Backfill live data semantics and normalize legacy defaults

**Files:**
- Modify: `backend/sql/mysql/phase5-seed.sql`
- Modify: `backend/src/test/resources/sql/phase5-data-h2.sql`
- Modify: `docs/server-migration-runbook.md`

- [ ] **Step 1: Write failing migration/data tests**

Cover:
- `default-*` names normalize to `default`
- one default system template per prompt type
- initial publish version exists in seed/migration fixture
- migration/backfill logic can derive the initial publish version from existing effective templates

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd backend
mvn "-Dtest=Phase5BackendIntegrationTest" test
```

Expected:
- FAIL because old seed data still uses `default-*` and lacks publish rows

- [ ] **Step 3: Update seed/migration fixtures**

Include:
- normalized system template names
- cleaned `is_default`
- initial publish version/items
- documented live-server backfill SQL / commands for existing databases
- verification SQL that checks:
  - one `default` system template per `prompt_type`
  - one active publish version head
  - four publish items under the active version

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd backend
mvn "-Dtest=Phase5BackendIntegrationTest" test
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add backend/sql/mysql/phase5-seed.sql backend/src/test/resources/sql/phase5-data-h2.sql backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java docs/server-migration-runbook.md
git commit -m "feat: backfill prompt governance defaults"
```

## Task 6: Add admin and user frontend prompt workflows

**Files:**
- Modify: `frontend/src/types/config.ts`
- Modify: `frontend/src/api/config.ts`
- Modify: `frontend/src/views/config/prompt/PromptConfigView.vue`
- Create: `frontend/src/views/config/prompt/UserPromptConfigView.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts`
- Create: `frontend/src/views/config/prompt/__tests__/UserPromptConfigView.spec.ts`

- [ ] **Step 1: Write failing frontend tests**

Cover:
- admin page exposes draft + publish workflow
- user page cannot edit JSON contract fields
- user can copy global template and bind it
- invalid personal template fallback warning is shown

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd frontend
npm run test -- --run src/views/config/prompt/__tests__/PromptConfigView.spec.ts src/views/config/prompt/__tests__/UserPromptConfigView.spec.ts
```

Expected:
- FAIL because new API/UI behavior does not exist yet

- [ ] **Step 3: Implement minimal frontend changes**

Notes:
- preserve existing visual language
- keep admin and user concerns clearly separated
- do not expose contract editing controls to normal users

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd frontend
npm run test -- --run src/views/config/prompt/__tests__/PromptConfigView.spec.ts src/views/config/prompt/__tests__/UserPromptConfigView.spec.ts
npm run type-check
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/config.ts frontend/src/api/config.ts frontend/src/views/config/prompt frontend/src/router/index.ts
git commit -m "feat: add prompt governance frontend flows"
```

## Task 7: Full regression and rollout docs

**Files:**
- Modify: `docs/superpowers/specs/2026-04-25-prompt-governance-redesign-design.md`
- Create: `docs/server-migration-runbook.md` (or extend if present)

- [ ] **Step 1: Write rollout verification checklist**

Include:
- schema migration command
- seed/data normalization checks
- publish/version verification queries
- fallback verification queries
- effective-history verification queries
- runtime metadata spot-check instructions

- [ ] **Step 2: Run backend verification**

Run:
```bash
cd backend
mvn test
```

Expected:
- PASS

- [ ] **Step 3: Run frontend verification**

Run:
```bash
cd frontend
npm run test
npm run type-check
```

Expected:
- PASS

- [ ] **Step 4: Summarize server execution commands in docs**

Document exact commands the operator can run on the server to:
- alter tables
- verify migrated data
- restart services if needed

- [ ] **Step 5: Commit**

```bash
git add docs backend frontend
git commit -m "docs: add prompt governance rollout runbook"
```
