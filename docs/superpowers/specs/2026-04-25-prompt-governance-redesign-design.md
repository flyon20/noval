# Prompt Governance Redesign

## Goal

Redesign prompt-template governance so that:

- administrator-maintained prompt templates become globally effective only after an explicit publish action
- every publish creates an auditable global version
- normal users can either follow the current global template or bind to a limited personal copy
- user-visible template scope is restricted to globally published system templates plus that user's own copies
- JSON contract fields remain admin-only and are never editable by normal users
- runtime prompt resolution is deterministic, explainable, and recoverable when user copies become invalid

This redesign must also produce an operator-friendly migration path so the server can rebuild the required schema and migrate existing prompt data safely.

## Current Problems

The current codebase mixes several responsibilities in ways that no longer fit the product rules:

- `prompt_config` is still treated as a global shared template table and does not distinguish admin-owned templates from user-owned copies.
- Runtime selection is primarily model-binding oriented and falls back through `promptBindings -> prompt_name=default -> is_default`, but there is no concept of admin draft vs published version.
- `PromptConfigRepository.saveOrUpdate(...)` marks new prompt rows with `is_default=1`, which pollutes the data model and weakens fallback semantics.
- `PromptConfigController` currently exposes the same prompt write API to both `ADMIN` and `USER`.
- `PromptConfigUpdateRequest` allows JSON contract fields for every caller, while the product now requires those fields to be admin-only.
- There is no audit trail for:
  - which prompt template a user actively chose
  - which prompt template was actually effective after an admin publish
  - what changed between one global publish and the next

The result is that the current system can identify a model, but cannot clearly answer:

- which global prompt set is currently published
- which template a given user is meant to follow
- what their last user-level selection was
- which effective template they actually used after the latest admin rollout

## Product Rules Confirmed

The redesign is based on the following confirmed rules:

1. Admin prompt changes are global in nature, but they should not become effective immediately on every save.
2. Admins edit drafts and then explicitly publish a global version.
3. A publish event makes the newly published global templates the new default baseline for all users.
4. Users may:
   - follow the current global template
   - bind to an existing allowed template
   - copy the current global template into a personal template and bind to that copy
5. Users must never be able to edit JSON contract fields.
6. Users can only see:
   - currently published global system templates
   - their own personal copies
7. Users cannot rename or delete the default global template.
8. If a user-bound personal template becomes invalid, disabled, deleted, or inaccessible, runtime must fall back to the currently published global template; if that also fails, runtime must fall back to the system safety-net default template.
9. User history must support both:
   - user selection history
   - effective-template snapshots after admin publish events

## Recommended Approach

Use a hybrid model:

- keep `prompt_config` as the main template library
- extend it with scope/ownership/source fields
- add explicit publish tables for admin rollout state
- add explicit user-binding and effective-history tables
- move runtime template resolution away from direct `model-registry.promptBindings` ownership and instead into a dedicated prompt-governance resolver

This keeps schema migration incremental while still making the new semantics first-class.

## Data Model

### 1. `prompt_config`

Keep `prompt_config` as the main prompt-template table, but extend it so each row has an explicit ownership scope.

New fields:

- `scope_type`
  - `SYSTEM`
  - `USER_COPY`
- `owner_user_id`
  - `NULL` for system templates
  - required for user copies
- `source_prompt_config_id`
  - the source system template or previous template this copy came from

Interpretation:

- `SYSTEM + prompt_name = default`
  - immutable safety-net system template for that `prompt_type`
- `SYSTEM + prompt_name != default`
  - admin-managed candidate template or draft template
- `USER_COPY`
  - user-private derived template

Notes:

- `is_default` should no longer be the primary business meaning.
- After migration, it should be normalized so only the safety-net system default remains `1`.
- Runtime should stop depending on `is_default` except as a legacy fallback of last resort.

### 2. `prompt_publish_version`

Represents a single admin publish action.

Suggested columns:

- `id`
- `version_no`
- `published_by`
- `publish_note`
- `created_at`

Semantics:

- admin draft saves do not touch this table
- explicit publish inserts one new row
- this row defines the currently active global prompt version

### 3. `prompt_publish_item`

Defines, for one publish version, which system template is effective for each `prompt_type`.

Suggested columns:

- `id`
- `publish_version_id`
- `prompt_type`
- `prompt_config_id`
- `created_at`

Constraints:

- unique on `(publish_version_id, prompt_type)`

Semantics:

- one publish version should contain one effective template for each of:
  - `deconstruct`
  - `structure`
  - `plot`
  - `theme`

### 4. `user_prompt_binding`

Stores the user's current binding intent for each `prompt_type`.

Suggested columns:

- `id`
- `user_id`
- `prompt_type`
- `binding_mode`
  - `GLOBAL`
  - `USER_COPY`
- `bound_prompt_config_id`
- `last_selected_prompt_config_id`
- `updated_at`

Constraints:

- unique on `(user_id, prompt_type)`

Semantics:

- `GLOBAL`
  - the user explicitly follows the current published global template
- `USER_COPY`
  - the user wants to use their personal copy
- `last_selected_prompt_config_id`
  - keeps the user's last non-global or last explicit choice so it can be shown in UI/history

### 5. `user_prompt_effective_history`

Records what template was actually effective for a user after a publish or binding recalculation.

Suggested columns:

- `id`
- `user_id`
- `prompt_type`
- `publish_version_id`
- `effective_prompt_config_id`
- `effective_source`
  - `GLOBAL_PUBLISHED`
  - `USER_COPY`
  - `USER_COPY_FALLBACK_TO_GLOBAL`
  - `SYSTEM_DEFAULT_FALLBACK`
- `previous_effective_prompt_config_id`
- `created_at`

Semantics:

- this is the audit table that answers:
  - what was actually used after the latest publish
  - what it replaced
  - whether the system had to force a fallback

## Runtime Resolution Rules

Introduce a dedicated prompt-governance resolver in the backend. It becomes the only supported way for analysis runtime to obtain a prompt template.

Input:

- `userId`
- `promptType`

Output:

- effective `PromptConfigEntity`
- effective source
- active publish version
- metadata for trace/logging

Resolution order:

1. Load current published global version.
2. Resolve the currently published global template for the requested `promptType`.
3. Load the user's current binding for that `promptType`.
4. If no binding exists, use the published global template.
5. If binding mode is `GLOBAL`, use the published global template.
6. If binding mode is `USER_COPY`, verify that the bound copy:
   - exists
   - belongs to the current user
   - is active
   - is not deleted
7. If the user copy is valid, use it.
8. If the user copy is invalid, use the published global template and record a fallback effective-history row.
9. If the published global template is missing or invalid, fall back to `SYSTEM + prompt_name=default`.
10. If even that is missing, throw a controlled server-side configuration error.

Important: model selection and prompt selection become separate concerns.

- model selection still comes from:
  - user preferred model
  - registry default model
  - other existing runtime rules
- prompt selection comes from:
  - prompt governance resolver

The selected model can still influence generation parameters, but it no longer owns the primary prompt-governance lifecycle.

## Admin Flow

### Draft Editing

Admins edit `SYSTEM` templates directly as drafts.

Allowed operations:

- create non-default system template
- update any system template
- delete non-default system template
- edit JSON contracts

Forbidden:

- renaming or deleting `SYSTEM + prompt_name = default`

### Publish

Publishing is an explicit action.

Publish flow:

1. Admin chooses, for each `promptType`, which `SYSTEM` template should become globally effective.
2. Backend validates:
   - all four prompt types are covered
   - all selected templates are `SYSTEM`
   - all selected templates are active and not deleted
3. Backend creates one `prompt_publish_version`.
4. Backend inserts four `prompt_publish_item` rows.
5. Backend recalculates user effective-template state lazily or eagerly:
   - eagerly for history rows if acceptable
   - or lazily on first runtime/config access after publish, with publish-version awareness

Recommended initial implementation:

- create publish rows synchronously
- create effective-history rows lazily on next user access or analysis run

This avoids a potentially expensive full-user fan-out during publish.

## User Flow

### What Users Can See

Users can only access:

- currently published `SYSTEM` templates
- their own `USER_COPY` templates

They must not see:

- unpublished admin drafts
- other users' copies

### What Users Can Do

Users may:

- view published system templates
- copy a published system template into a personal template
- edit their personal copy's non-contract fields
- bind a `promptType` to:
  - `GLOBAL`
  - one of their own `USER_COPY` templates

Users may not:

- edit `SYSTEM` templates
- edit JSON contract fields
- rename or delete the effective global `default`
- view or bind to another user's private template

### Copy Semantics

When a user copies a global template:

- create a `USER_COPY`
- set `owner_user_id = current user`
- set `source_prompt_config_id = source SYSTEM template`
- clone editable runtime fields and current prompt content
- clone JSON contract fields as read-only inherited data for runtime use

Even though contract fields are cloned for runtime completeness, they remain non-editable in user APIs and UI.

## API Design

### Admin APIs

Recommended new endpoints:

- `GET /api/config/prompt/system/templates?promptType=...`
- `GET /api/config/prompt/system/template?promptType=...&promptName=...`
- `PUT /api/config/prompt/system`
- `DELETE /api/config/prompt/system`
- `POST /api/config/prompt/system/publish`
- `GET /api/config/prompt/system/publish/current`
- `GET /api/config/prompt/system/publish/history`

### User APIs

Recommended new endpoints:

- `GET /api/config/prompt/user/templates?promptType=...`
- `POST /api/config/prompt/user/copy-from-global`
- `PUT /api/config/prompt/user/template`
- `DELETE /api/config/prompt/user/template`
- `GET /api/config/prompt/user/binding?promptType=...`
- `PUT /api/config/prompt/user/binding`
- `GET /api/config/prompt/user/effective-history?promptType=...`

### DTO Separation

Split DTOs by role instead of sharing the current admin-capable payload with every caller.

Recommended:

- `AdminPromptConfigUpdateRequest`
- `UserPromptCopyUpdateRequest`
- `UserPromptBindingUpdateRequest`
- `PromptPublishRequest`

This prevents field-level permission logic from becoming brittle.

## Frontend Design

### Admin UI

The current prompt config page evolves into an admin prompt-governance workspace.

Capabilities:

- manage system templates by `promptType`
- view draft templates
- edit prompt content and JSON contracts
- preview currently published template set
- publish the next global version
- review publish history

### User UI

Provide a user-focused page or mode that is clearly different from admin governance.

Capabilities:

- inspect current global template for each `promptType`
- see personal copies
- create personal copy from the current published global template
- edit only allowed runtime fields
- switch binding between `GLOBAL` and one of their copies
- review their last selected and last effective template

### UX Rules

- user-facing default template appears as the current global default and is read-only
- JSON contract boxes never appear editable for non-admins
- when a user copy becomes invalid and the system falls back, UI should show a clear message such as:
  - "当前个人模板已失效，已自动回退到最新全局模板"

## Migration Strategy

### Existing Data Normalization

1. Normalize old default template names:
   - `default-deconstruct` -> `default`
   - `default-structure` -> `default`
   - `default-plot` -> `default`
   - `default-theme` -> `default`

2. Normalize `is_default`:
   - exactly one `SYSTEM + prompt_name=default` per `promptType` should keep `is_default=1`
   - all other templates should be set to `0`

3. Add scope fields to all existing rows:
   - existing prompt rows become `scope_type = SYSTEM`
   - `owner_user_id = NULL`
   - `source_prompt_config_id = NULL`

4. Create an initial publish version using the current effective global templates.

### Compatibility With Existing Model Registry

`ai.model-registry.json.promptBindings` may remain in storage for compatibility during migration, but it should no longer be treated as the primary source of prompt-governance truth.

Recommended staged behavior:

- phase 1:
  - keep reading `promptBindings` only as optional metadata or migration aid
- phase 2:
  - runtime prompt selection uses publish tables + user bindings only
- phase 3:
  - admin UI may still show registry bindings for model-specific parameter display, but not as ownership of prompt lifecycle

## Observability

Every analysis run should log or carry metadata for:

- `userId`
- `promptType`
- selected model key
- effective prompt config id
- effective prompt source
- active publish version id
- whether fallback occurred

This metadata should also be eligible for inclusion in analysis result JSON meta fields where appropriate.

## Testing Strategy

### Backend

Add tests for:

- admin cannot delete or rename system `default`
- user cannot edit contract fields
- user sees only published system templates plus own copies
- publish creates one version plus one item per prompt type
- runtime resolver uses:
  - global published template by default
  - user copy when valid
  - fallback to global when user copy invalid
  - fallback to system `default` when published template missing
- effective history is recorded correctly across publish transitions

### Frontend

Add tests for:

- admin prompt page shows draft vs publish actions
- user prompt page hides contract editing
- user can copy global template and bind it
- fallback warning is shown when personal template becomes invalid

### Migration

Add schema/data tests verifying:

- old default names are normalized
- legacy multi-`is_default=1` data is cleaned
- initial publish version is created successfully

## Risks

### 1. Scope Creep

This redesign touches:

- schema
- runtime resolution
- prompt config APIs
- admin UI
- user UI
- history/audit semantics

The implementation must be staged carefully.

### 2. Performance

If every admin publish eagerly recomputes effective rows for all users, publish may become expensive.

Mitigation:

- publish synchronously creates only publish tables
- effective user history can be created lazily on first access after publish

### 3. Legacy Data Ambiguity

Current databases may already contain:

- multiple `is_default=1`
- old `default-*` names
- model-specific templates with unclear intended ownership

Mitigation:

- ship explicit migration SQL
- include post-migration verification queries

## Recommendation

Proceed with this redesign using:

- `prompt_config` as the shared template library with explicit scope fields
- publish/version tables for admin global rollout
- separate user binding and effective-history tables
- a dedicated runtime resolver that replaces the current fallback maze

This gives the clearest path to your desired product behavior without forcing a full table-family rewrite at once.
