# Model Profile TOML Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a safe per-user TOML model-profile editor with a separate API-key field, transient model scanning, and one save-and-test action.

**Architecture:** Java remains the only public business and provider-network boundary. A new protected transient scan route accepts the current editor's endpoint and write-only key without persisting either. Vue parses a strict non-secret TOML projection, sends the separate key only to scan or save, and stores no API key outside the active password input.

**Tech Stack:** Java 21, Spring Boot 3, OpenAPI 3.1, Vue 3, TypeScript, Pinia, Vitest, Vite, `@iarna/toml`.

**Spec:** `docs/superpowers/specs/2026-09-01-model-profile-toml-editor-design.md`

## Global Constraints

- The authoritative public contract is `contracts/openapi/v2/openapi.yaml`, version `2.1.1`; fixtures are under `contracts/fixtures/v2/`.
- `apiKey` is write-only, is never rendered in TOML, and must not enter Pinia state, browser storage, notices, logs, provider diagnostics, or API responses. Every transient scan requires a separately entered non-empty key; an existing profile may omit the key only when saving to retain its stored encrypted key.
- Only Java calls an external provider; it applies existing SSRF, DNS, HTTPS, redirect, and local-test policies to both scan and saved-profile test requests.
- A scan request must not create, update, select, test, or otherwise write any `llm_profiles` record.
- Save uses the existing encrypted `api_key_ciphertext` and `api_key_nonce` columns for the current JWT actor; no schema migration is allowed.
- Provider failures return a safe `200` test response with `available: false`; malformed client input, rejected endpoints, and authentication use the existing error-envelope behavior.
- The worktree was already dirty before this slice. Do not reset, revert, or commit unrelated changes. Record exact test output and changed files instead of creating a mixed commit.

---

### Task 1: Freeze v2 Scan Contract And Fixtures

**Files:**
- Modify: `contracts/openapi/v2/openapi.yaml`
- Modify: `contracts/package.json`
- Modify: `contracts/scripts/validate-contracts.mjs`
- Create: `contracts/fixtures/v2/llm-profile-scan-valid.json`
- Create: `contracts/fixtures/v2/llm-profile-scan-invalid.json`
- Create: `contracts/fixtures/v2/llm-profile-scan-available.json`
- Create: `contracts/fixtures/v2/llm-profile-scan-unavailable.json`
- Modify: `docs/superpowers/specs/2026-09-01-model-profile-toml-editor-design.md`

**Interfaces:**
- Consumes: OpenAPI v2 `LlmProfileTestResponse` and existing `CreateLlmProfileRequest` write-only key convention.
- Produces: `LlmProfileScanRequest` with `endpointUrl: string` and `apiKey: string`, and protected `POST /api/v2/llm-profiles/scan` returning `LlmProfileTestResponse`.

- [x] **Step 1: Add fixtures and contract-validator assertions before adding the schema**

Create the valid request fixture:

```json
{"endpointUrl":"https://api.deepseek.com/v1","apiKey":"fixture-scan-key"}
```

Create the invalid request fixture with an unexpected property:

```json
{"endpointUrl":"https://api.deepseek.com/v1","apiKey":"fixture-scan-key","modelName":"must-not-be-accepted"}
```

Create safe available and unavailable response fixtures, then make the validator load them through `LlmProfileScanRequest` and `LlmProfileTestResponse`, and assert that the new operation has bearer authentication and a write-only request key.

- [x] **Step 2: Run the contract linter to verify the expected red failure**

Run: `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs`

Expected: FAIL because `LlmProfileScanRequest` and the scan operation are not yet defined.

- [x] **Step 3: Define the additive v2.1.0 contract**

In `openapi.yaml`, change `info.version` to `2.1.0`; add the protected scan path; define `LlmProfileScanRequest` with `additionalProperties: false`, required `endpointUrl` and `apiKey`, existing field limits, and `writeOnly: true` on the key. Declare `200`, `400`, `401`, and `422` response behavior. Remove `503` from the saved-profile test route and document its safe `200 + available:false` provider-failure outcome.

- [x] **Step 4: Run the contract linter to verify the green contract baseline**

Run: `corepack pnpm --dir contracts run lint` and `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs`

Expected: PASS and print the valid/expected-invalid scan fixture checks.

- [x] **Step 5: Record the frozen baseline**

Run: `git diff -- contracts/openapi/v2/openapi.yaml contracts/scripts/validate-contracts.mjs contracts/fixtures/v2 docs/superpowers/specs/2026-09-01-model-profile-toml-editor-design.md`

Expected: only the v2.1.0 scan contract, fixtures, and aligned design text are present. Do not commit because the worktree has pre-existing uncommitted changes.

### Task 2: Add Transient Java Model Scanning

**Files:**
- Create: `back/java/src/main/java/com/resumethinking/platform/profiles/ScanLlmProfileCommand.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileController.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileService.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/profiles/LlmProfileServiceTest.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/profiles/LlmProfileBoundaryTest.java`

**Interfaces:**
- Consumes: `POST /api/v2/llm-profiles/scan`, `ScanLlmProfileCommand(endpointUrl, apiKey)`, current JWT actor, and `LlmProfileTestResponse`.
- Produces: `LlmProfileService.scanModels(String owner, ScanLlmProfileCommand command)` with no repository mutation; `LlmProfileController.scan(...)` mapping the protected request to that method.

- [ ] **Step 1: Write failing service tests for transient scanning**

Add a local `HttpServer` test that calls a non-existent `scanModels` with endpoint `http://127.0.0.1:<port>/v1` and an API key. Assert it returns only safe model IDs, filters an ID equal to the submitted key, and leaves `repository.findAllByOwnerId(owner)` empty. Add a provider-unavailable test that expects `available == false`, an empty model list, a safe diagnostic, and no repository rows.

- [ ] **Step 2: Run the focused Java test to verify red**

Run: `mvn -q -Dtest=LlmProfileServiceTest test`

Expected: FAIL at compilation because `ScanLlmProfileCommand` and `scanModels` do not exist.

- [ ] **Step 3: Implement the smallest shared safe provider-listing path**

Add the validated command record with `@NotBlank` and existing size limits. Refactor the request-to-`/models` logic into a private helper that accepts a validated endpoint and temporary key, returns `LlmProfileTestResponse`, filters the key from all model IDs, caps output at the existing limit, and turns provider errors into safe responses. Keep saved-profile `testConnection` metadata updates; make `scanModels` validate and call the helper without reading or writing a profile.

- [ ] **Step 4: Add the protected HTTP-boundary tests**

Add a MockMvc test that verifies `POST /api/v2/llm-profiles/scan` calls the service only with an authenticated actor. Add a rejected-endpoint test that maps `MODEL_ENDPOINT_REJECTED` to the safe `422` envelope. Assert response JSON never has an `apiKey` property.

- [ ] **Step 5: Run Java tests to verify green**

Run: `mvn -q -Dtest=LlmProfileServiceTest,LlmProfileBoundaryTest test`

Expected: PASS, including secret filtering, no-persistence, unavailable-provider, endpoint-rejection, and authorization behavior.

- [ ] **Step 6: Inspect the Java diff for secret and persistence regressions**

Run: `git diff --check` and `git diff -- back/java/src/main/java/com/resumethinking/platform/profiles back/java/src/test/java/com/resumethinking/platform/profiles`

Expected: no whitespace errors; `scanModels` has no `repository.save`, no profile creation/update, and no logging of the submitted key.

### Task 3: Add Strict Frontend TOML Mapping And Scan Client

**Files:**
- Modify: `front/package.json`
- Modify: `front/pnpm-lock.yaml`
- Create: `front/src/features/llmProfiles/modelProfileToml.ts`
- Create: `front/src/features/llmProfiles/modelProfileToml.spec.ts`
- Modify: `front/src/api/contracts.ts`
- Modify: `front/src/stores/llmProfiles.ts`
- Modify: `front/src/stores/llmProfiles.spec.ts` if absent, otherwise create it

**Interfaces:**
- Consumes: TOML text and `LlmProfileScanRequest { endpointUrl: string; apiKey: string }`.
- Produces: `parseModelProfileToml(text): ModelProfileToml`, `serializeModelProfileToml(profile): string`, and `llmProfiles.scanModels(payload): Promise<LlmProfileTestResponse>`.

- [ ] **Step 1: Add failing strict-TOML tests**

Write tests using literal TOML text. Verify that the approved `[profile]` projection maps snake-case TOML keys to `{ displayName, endpointUrl, modelName, selected }`; malformed TOML, a second section, an unknown field, an `api_key` field, and a non-boolean `selected` produce a validation error. Verify serialization never contains `api_key`.

- [ ] **Step 2: Run the focused Vitest file to verify red**

Run: `corepack pnpm --dir front vitest run src/features/llmProfiles/modelProfileToml.spec.ts`

Expected: FAIL because the parser module does not exist.

- [ ] **Step 3: Add the standard TOML dependency and minimal parser**

Add runtime dependency `@iarna/toml`. Implement the parser as a strict whitelist mapper, not with hand-written line splitting. Keep the API key out of the parser input and output type. Add deterministic serialization of the four approved keys.

- [ ] **Step 4: Extend typed API client and store for scan**

Add `LlmProfileScanRequest` to `front/src/api/contracts.ts`. Add `scanModels(payload)` in the store that posts only `{ endpointUrl, apiKey }` to `/api/v2/llm-profiles/scan`; do not add it to Pinia state. Add a focused store test that asserts the scan request has no `modelName`, profile ID, or persistent side effect.

- [ ] **Step 5: Run parser and store tests to verify green**

Run: `corepack pnpm --dir front vitest run src/features/llmProfiles/modelProfileToml.spec.ts src/stores/llmProfiles.spec.ts`

Expected: PASS with all strict parsing and transient scan-client cases green.

### Task 4: Replace the Model Configuration UI

**Files:**
- Modify: `front/src/components/ModelProfileForm.vue`
- Modify: `front/src/components/ModelProfileForm.spec.ts`
- Modify: `front/src/views/ModelProfilesView.vue`
- Modify: `front/src/views/ModelProfilesView.spec.ts`
- Modify: `front/src/style.css`

**Interfaces:**
- Consumes: `parseModelProfileToml`, `serializeModelProfileToml`, `useLlmProfileStore().scanModels`, the existing create/update methods, and saved-profile test method.
- Produces: a dialog-based new/edit experience where `Save and test connection` is the sole persistence command and a profile-row command named `Edit model configuration`.

- [ ] **Step 1: Write failing component and view tests**

Add component tests that assert TOML has no API-key line, every scan requires the separate password field, blank key on an existing profile emits an update without `apiKey`, scanned models can change `model_name`, manual TOML model input remains valid, and a parse failure performs no request. Add view tests that assert the profile-row action text is `编辑模型配置`, there is no separate save action, scan calls the transient store method without creating a profile, save persists first and then tests the saved profile, and a failed scan preserves form values.

- [ ] **Step 2: Run focused UI tests to verify red**

Run: `corepack pnpm --dir front vitest run src/components/ModelProfileForm.spec.ts src/views/ModelProfilesView.spec.ts`

Expected: FAIL because the form still renders the legacy typed fields, extra save action, and no transient scan action.

- [ ] **Step 3: Implement the editor dialog and separate key input**

Render a `textarea` TOML editor initialized from the selected profile or a safe new-profile template. Keep the API key in a neighboring password input only. Add model-selection UI and `扫描模型列表` alongside it; selecting a scanned model rewrites the TOML projection. Disable actions while scanning or saving. On close of dirty content, require discard confirmation. Do not display any previous key or append it to TOML.

- [ ] **Step 4: Implement save-and-test behavior and safe notices**

Remove `保存修改` and `添加配置`. The sole save action parses TOML, combines the mapped fields with the separately entered key only for the request, creates or updates the current user's profile, clears the key after successful persistence, then calls the existing saved-profile test endpoint. Keep a successfully saved profile after a failed test and emit only safe Chinese feedback.

- [ ] **Step 5: Run focused UI tests to verify green**

Run: `corepack pnpm --dir front vitest run src/components/ModelProfileForm.spec.ts src/views/ModelProfilesView.spec.ts`

Expected: PASS for separate-key, strict-TOML, transient-scan, save-before-test, safe-feedback, dialog-close, and action-label behavior.

### Task 5: Integrate, Review, And Verify The Slice

**Files:**
- Modify only files required by any corrections discovered in verification.
- Test: `contracts/scripts/validate-contracts.mjs`, Java profile tests, frontend profile tests, and frontend build output.

**Interfaces:**
- Consumes: the frozen v2.1.0 contract, Java scan endpoint, Vue parser/store/editor.
- Produces: evidence that the page works with a separate API key without leaking it or creating a profile during scan.

- [ ] **Step 1: Run full contract and focused backend checks**

Run: `corepack pnpm --dir contracts run lint`, `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs`, and `mvn -q -Dtest=LlmProfileServiceTest,LlmProfileBoundaryTest test`

Expected: PASS.

- [ ] **Step 2: Run full frontend verification**

Run: `corepack pnpm --dir front test` and `corepack pnpm --dir front build`

Expected: PASS with a production bundle generated successfully.

- [ ] **Step 3: Perform browser verification**

With frontend and Java services running, open `/profiles` at desktop and mobile widths. Verify that a new dialog starts with TOML containing only the four allowed fields; API key is a separate masked input; scan does not add a profile row; a selected scanned model appears in TOML; only `保存并测试连接` persists; and saved profiles expose `编辑模型配置`.

- [ ] **Step 4: Conduct an independent slice review**

Review the final diff against the spec. Check that no API key enters response objects, Pinia state, TOML text, notifications, `repository.save` inside `scanModels`, or browser storage. Check that the contract and Java behavior agree on `200 + available:false` for provider failures.

- [ ] **Step 5: Record exact evidence without a mixed commit**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors. Report the fresh command results, files changed for this slice, intentionally simulated behavior, and any residual risk. Do not create a commit or push because unrelated work was already present.
