# Model Profile Form And Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore normal model-profile form configuration while retaining an optional TOML projection and showing safe Chinese operation notifications.

**Architecture:** Java adds an additive response diagnostic code without changing stored profile data. Vue restores normal form controls as the source of save/scan requests, uses TOML only as a non-secret advanced import/export projection, and translates stable result codes through the existing notification boundary.

**Tech Stack:** Java 21, Spring Boot 3, OpenAPI 3.1, Vue 3, TypeScript, Pinia, Element Plus, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-01-model-profile-form-feedback-design.md`

## Global Constraints

- Contract source: `contracts/openapi/v2/openapi.yaml` version `2.1.2`; fixtures: `contracts/fixtures/v2/`.
- `apiKey` is write-only and must not enter TOML, top notifications, Pinia state, browser storage, API responses, logs, or provider diagnostics.
- The normal form is the only source of create, update, and scan payloads; TOML is a strict four-field non-secret projection and is never persisted raw.
- The provider preset has exactly `OpenAI` and `Custom`, is UI-only, and selecting OpenAI uses `https://api.openai.com/v1`.
- External provider failures remain safe `200` responses with `available: false`; platform authorization failures keep existing API-envelope behavior and must not log out an authenticated user because a provider rejected a key.
- The worktree is dirty. Do not reset, revert, commit, push, or edit unrelated files.

---

### Task 1: Add Stable Provider Failure Codes

**Files:**
- Modify: `contracts/openapi/v2/openapi.yaml`
- Modify: `contracts/fixtures/v2/llm-profile-scan-unavailable.json`
- Modify: `contracts/scripts/validate-contracts.mjs`
- Modify: `back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileTestResponse.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileDiagnosticCode.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileService.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/profiles/LlmProfileServiceTest.java`

**Interfaces:**
- Produces: `LlmProfileTestResponse(..., String diagnostic, LlmProfileDiagnosticCode diagnosticCode)` while retaining the four-argument constructor for existing callers.
- Consumes: provider HTTP status and the existing `/models` parsing path.

- [ ] **Step 1: Write failing contract and Java tests**

Add a fixture response with `"diagnosticCode":"PROVIDER_UNAVAILABLE"` and
make the contract validator assert that `diagnosticCode` exists and is nullable.
Add focused local-provider tests for a 401 response and malformed JSON:

```java
assertThat(response.available()).isFalse();
assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.INVALID_API_KEY);
```

```java
assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
```

- [ ] **Step 2: Run the failing checks**

Run: `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs`

Run: `mvn -q -Dtest=LlmProfileServiceTest test`

Expected: the fixture/schema and Java accessor fail before the new response
field and mappings exist.

- [ ] **Step 3: Add minimal compatible mappings**

Add the enum values from the spec. Map only HTTP 401, 403, 404, 429,
malformed response JSON, and all remaining provider/transport failures. Keep
the pre-existing sanitized diagnostic field; do not use response bodies,
request headers, or exception text in either field. Use the legacy
four-argument constructor for success and unchanged callers.

- [ ] **Step 4: Run green boundary checks**

Run: `corepack pnpm --dir contracts run lint`

Run: `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs`

Run: `mvn -q -Dtest=LlmProfileServiceTest,LlmProfileBoundaryTest test`

Expected: the contract accepts only the documented diagnostic codes; Java
returns safe codes without a submitted key or provider body.

### Task 2: Restore Form Controls And Top Notifications

**Files:**
- Modify: `front/src/components/ModelProfileForm.vue`
- Modify: `front/src/components/ModelProfileForm.spec.ts`
- Modify: `front/src/views/ModelProfilesView.vue`
- Modify: `front/src/views/ModelProfilesView.spec.ts`
- Modify: `front/src/api/contracts.ts`
- Modify: `front/src/style.css`

**Interfaces:**
- Consumes: optional `LlmProfileTestResponse.diagnosticCode`, existing store
  create/update/scan/test methods, and `showTopNotification(message, type)`.
- Produces: normal form save/scan payloads and a safe notification wrapper
  that never throws into operation control flow.

- [ ] **Step 1: Write failing UI tests**

Add form tests for the OpenAI/Custom selector, normal editable endpoint/model
fields, a separate password key, a collapsed TOML section with an apply action,
and a scanned-model selection that does not disable manual model input. Assert
the save payload has only normal fields plus optional `apiKey` and that the
TOML text has no key.

Mock `../ui/notifications` in view tests. Assert that an unavailable scan with
`diagnosticCode: 'INVALID_API_KEY'` calls it with an error message mentioning
`API 密钥`, a successful scan calls success, a persisted but unavailable test
calls error while retaining the editor, and a successful save-and-test calls
success and closes it. Make the mock throw once and assert a successful store
operation still completes.

- [ ] **Step 2: Run focused UI tests and verify red**

Run: `corepack pnpm --dir front vitest run src/components/ModelProfileForm.spec.ts src/views/ModelProfilesView.spec.ts`

Expected: tests fail because the current UI requires TOML as the primary
editor and does not call the top-notification boundary.

- [ ] **Step 3: Implement the smallest form-first editor**

Use a reactive form object. Infer `OpenAI` only for the canonical OpenAI URL;
all other endpoints, including DeepSeek, use `Custom`. Selecting Custom must
not clear the endpoint. Keep `parseModelProfileToml` and
`serializeModelProfileToml`, but place their textarea in a collapsed advanced
section; applying it updates the normal form. Keep the API key in its existing
local password ref and exclude it from all TOML paths.

Use a `report(message, type)` helper that catches notification-rendering
errors. Translate stable diagnostic codes without rendering `diagnostic`.
Use it for list, scan, save, saved-test, and success outcomes.

- [ ] **Step 4: Run UI and build verification**

Run: `corepack pnpm --dir front vitest run src/components/ModelProfileForm.spec.ts src/views/ModelProfilesView.spec.ts`

Run: `corepack pnpm --dir front test`

Run: `corepack pnpm --dir front build`

Expected: form payloads, TOML import behavior, error/success notifications,
and existing frontend regressions pass with a production build.

### Task 3: Browser Regression And Cross-Layer Verification

**Files:**
- Test: `front/e2e/login-profiles-rendering.spec.ts`

**Interfaces:**
- Consumes: completed contract and frontend behavior.
- Produces: a login-to-profiles regression that detects a runtime failure and
  confirms normal configuration controls are visible.

- [ ] **Step 1: Write the browser expectation**

After the existing login-to-profiles navigation, assert that `新建模型配置`
opens an editor containing the service-provider selector, endpoint input,
model input, API-key password field, and collapsed `高级 TOML 配置` section.

- [ ] **Step 2: Run focused browser regression**

Run: `corepack pnpm --dir front test:browser -- login-profiles-rendering.spec.ts`

Expected: it detects a missing normal field or a post-login runtime error.

- [ ] **Step 3: Run cross-layer final evidence**

Run: `corepack pnpm --dir contracts run lint`

Run: `mvn -q -Dtest=LlmProfileServiceTest,LlmProfileBoundaryTest test`

Run: `corepack pnpm --dir front test`

Run: `corepack pnpm --dir front build`

Run: `corepack pnpm --dir front test:browser -- login-profiles-rendering.spec.ts`

Expected: all commands exit successfully; no test or rendered content exposes
an API key or provider diagnostic.
