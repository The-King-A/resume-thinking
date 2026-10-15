# Effective Resume Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a resume effective only after validated evidence matching, allow safe re-match/edit revisions, show the latest report, and enforce reusable per-owner effective titles without breaking existing v2 behavior.

**Architecture:** A logical resume owns immutable encrypted revisions. A v3 match submission creates or selects a revision and an asynchronous task; Java publishes the revision only after it validates the Python callback and the evidence/result transaction succeeds. The Vue client uses v3 only for the new lifecycle while v2 auth, profile, recovery, and existing task-result routes remain intact.

**Tech Stack:** Java 21, Spring Boot 3, Flyway/MySQL 8, Redis, OpenAPI 3.1, FastAPI/Pydantic, Vue 3, TypeScript, Element Plus, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-02-resume-effective-lifecycle-design.md`

## Global Constraints

- Product source: `E:/resume_thinking/ai-resume-job-matching-project.md.docx`; strict public contract: `contracts/openapi/v3/openapi.yaml`; shared v3 fixtures: `contracts/fixtures/v3/`.
- Preserve the documented v2 public behavior and paths. The existing `/api/v2/auth/*`, `/api/v2/llm-profiles/*`, recovery routes, `/api/v2/match-tasks/{taskId}`, and `/api/v2/match-tasks/{taskId}/result` must continue to work for existing frontend and direct callers.
- Java remains the only public authorization, persistence, publication, deletion, cache, and external-model dispatch boundary. Python accepts only authenticated internal jobs and never writes MySQL/Redis or authorizes a user.
- Resume contents, API keys, callback tokens, encrypted bytes/nonces, raw provider responses, and provider diagnostics never enter API errors, browser state, notifications, logs, fixtures, or test failure text.
- Only `JAVA_BACKEND` is accepted. TXT and DOCX stay supported; PDF remains rejected.
- An effective title is unique per `owner_id`, normalized by trim and lowercase. The database unique index is authoritative. Soft deletion and cache archive release a title; restore/publication collisions return `409 DUPLICATE_RESOURCE` plus `detailCode: DUPLICATE_RESUME_TITLE`.
- User retention remains seven days and administrator retention remains thirty days. Redis remains derived and is written/evicted only after the durable transaction commits.
- A delete, archive, supersede, stale, or duplicate callback must not recreate an effective resume or Redis view. Keep the existing lock order: logical resume first, task second.
- The worktree is already dirty. Never reset, revert, stash, or stage unrelated changes. Before editing a dirty file, record its baseline hash in the task report; stage only the task-owned hunks and include the exact staged-file list in the report.

---

### Task 1: Freeze the v3 Contract and Compatibility Fixtures

**Files:**
- Create: `contracts/openapi/v3/openapi.yaml`
- Create: `contracts/internal/v3/analysis-job.schema.json`
- Create: `contracts/internal/v3/analysis-callback.schema.json`
- Create: `contracts/fixtures/v3/match-submission-initial-valid.json`
- Create: `contracts/fixtures/v3/match-submission-rematch-valid.json`
- Create: `contracts/fixtures/v3/match-submission-invalid.json`
- Create: `contracts/fixtures/v3/resume-effective.json`
- Create: `contracts/fixtures/v3/resume-pending.json`
- Create: `contracts/fixtures/v3/match-context-valid.json`
- Create: `contracts/fixtures/v3/callback-valid.json`
- Create: `contracts/fixtures/v3/callback-duplicate.json`
- Create: `contracts/fixtures/v3/callback-superseded.json`
- Create: `contracts/fixtures/v3/callback-after-soft-delete.json`
- Create: `contracts/fixtures/v3/task-publication-rejected-duplicate-title.json`
- Create: `contracts/fixtures/v3/error-duplicate-resume-title.json`
- Modify: `contracts/package.json`
- Modify: `contracts/scripts/validate-contracts.mjs`
- Modify: `contracts/README.md`

**Interfaces:**
- Produces public `POST /api/v3/match-submissions`, `POST /api/v3/resumes/{resumeId}/match-submissions`, `GET /api/v3/resumes`, `GET /api/v3/resumes/{resumeId}/match-context`, and `DELETE /api/v3/resumes/{resumeId}`.
- Produces `PublicationState = NOT_REQUESTED | PENDING | PUBLISHED | REJECTED_DUPLICATE_TITLE`, a v3 `MatchTask.revisionId`, and a v3 `Resume.effectiveRevisionId`/`latestSuccessfulTaskId`.
- Produces `revisionId` in the v3 Java-to-Python job and Python-to-Java callback schemas. The v3 callback requires it; v2 schemas remain unmodified.

- [ ] **Step 1: Write the failing v3 contract validation assertions**

Extend `validate-contracts.mjs` to bundle `v3` in addition to `v1` and `v2`.
Make it validate each new fixture and assert the following exact facts:

```js
if (v3Resume.properties.effectiveRevisionId?.pattern !== '^revision[0-9]{3,}$') {
  throw new Error('v3 effective revision ID is required')
}
if (v3Task.properties.publicationState?.$ref !== '#/components/schemas/PublicationState') {
  throw new Error('v3 task publication state is required')
}
if (v3Duplicate.code !== 'DUPLICATE_RESOURCE' || v3Duplicate.detailCode !== 'DUPLICATE_RESUME_TITLE') {
  throw new Error('v3 title duplicate must be stable and field-specific')
}
```

Also assert that all v2 fixture validations and v2 login/profile compatibility
assertions continue to run.

- [ ] **Step 2: Run the contract checks and verify red**

Run: `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs`

Expected: failure because v3 is not bundled, v3 schemas do not exist, and the
new lifecycle fixtures have no validator.

- [ ] **Step 3: Add the minimal versioned schemas and fixtures**

Copy the v2 OpenAPI common error/auth components into v3 without changing v2.
Define the exact new multipart fields:

```yaml
file: { type: string, format: binary }
title: { type: string, minLength: 1, maxLength: 200 }
llmProfileId: { type: string, pattern: '^profile[0-9]{3,}$' }
jobFamily: { const: JAVA_BACKEND }
jobDescriptionText: { type: string, minLength: 20, maxLength: 20000 }
idempotencyKey: { type: string, minLength: 16, maxLength: 128 }
```

The initial request requires `file`; the re-match request requires
`expectedEffectiveRevisionId`, allows `file` and `title` to be omitted, and
always requires profile, job description, family, and idempotency key. Use
`revision[0-9]{3,}` for revision IDs. Define `ApiError.detailCode` as nullable
and only permit `DUPLICATE_RESUME_TITLE` where supplied. Copy v2 internal
schemas to v3, add required `revisionId`, and keep the same strict unknown-key
rejection, callback token, redaction, and provider-secret rules.

- [ ] **Step 4: Run green contract and legacy checks**

Run: `corepack pnpm --dir contracts run lint`

Run: `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs`

Expected: v1, v2, and v3 OpenAPI documents lint; valid/invalid v3 fixtures
are classified correctly; all legacy v2 fixture and privacy assertions remain
green.

- [ ] **Step 5: Commit only the contract task files**

Run `git diff --cached --name-only` before committing and confirm it contains
only the files listed in this task. Commit with:

```text
feat(contract): add v3 effective resume lifecycle
```

### Task 2: Add Immutable Revision Persistence and the Safe Migration

**Files:**
- Create: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeRevision.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeRevisionRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeRevisionJpaRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/resumes/JpaResumeRevisionRepository.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/resumes/Resume.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeRepository.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/MatchTask.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/AnalysisResult.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/AnalysisResultEntity.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/AnalysisResultRepository.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/AnalysisResultJpaRepository.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/JpaAnalysisResultRepository.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/ids/BusinessIdType.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/ids/ReadableIdGenerator.java`
- Create: `back/java/src/main/resources/db/migration/V10__effective_resume_revisions.sql`
- Modify: `database/resume_thinking_schema.sql`
- Create: `back/java/src/test/java/com/resumethinking/platform/resumes/ResumeRevisionSchemaTest.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/resumes/ResumeSchemaTest.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/ids/ReadableIdGeneratorTest.java`

**Interfaces:**
- Produces immutable `ResumeRevision(id, resumeId, revisionNo, title, titleKey, sourceType, parserVersion, ciphertext, nonce, state)`.
- Produces `Resume.effectiveRevisionId`, `Resume.pendingRevisionId`, and nullable generated `effectiveTitleKey`.
- Produces non-null `revision_id` bindings in newly written `analysis_tasks` and `analysis_results` rows, while the old scalar `resume_version` stays readable for v2 compatibility.

- [ ] **Step 1: Write failing schema and domain tests**

Add tests that inspect V10 and the empty-database snapshot. They must require
Chinese comments for every new column, the revision ID sequence, immutable
unique key, task/result revision foreign keys, result history index, and the
nullable per-owner effective-title unique index.

```java
assertThat(snapshot).contains("CREATE TABLE resume_revisions");
assertThat(snapshot).contains("UNIQUE KEY uq_resumes_owner_effective_title");
assertThat(migration).contains("ADD COLUMN effective_revision_id");
assertThat(migration).contains("ADD COLUMN revision_id");
```

Add unit tests for a revision that cannot change its source bytes/title after
construction and for `ReadableIdGenerator.validate(BusinessIdType.REVISION,
"revision001")`.

- [ ] **Step 2: Run focused tests and verify red**

Run from `back/java`: `mvn -q -Dtest=ResumeRevisionSchemaTest,ResumeSchemaTest,ReadableIdGeneratorTest test`

Expected: compilation or assertions fail because revision persistence, the
new ID type, and V10 do not yet exist.

- [ ] **Step 3: Implement the durable model and V10 migration**

Create `resume_revisions` with encrypted fields copied from the old resume,
`UNIQUE(resume_id, revision_no)`, and no mutator for source/title metadata.
Make the logical resume own only pointers/lifecycle state and synchronize its
display metadata from the effective revision during publication.

V10 must create all revision records before adding non-null task/result
revision constraints. Backfill each existing task/result to the revision that
represents its historical resume content. Set an effective pointer only for
the newest row per `(owner_id, normalized title)` that has a succeeded task,
persisted result, and persisted evidence. Mark every other legacy active row
as non-effective without deleting data. Add the generated nullable
`effective_title_key` only after resolving that deterministic winner, then
create `UNIQUE(owner_id, effective_title_key)`.

Do not edit V1-V9. Recreate `v_resumes_readable` with readable revision and
effective/publication metadata but without plaintext content, ciphertext, or
secrets.

- [ ] **Step 4: Run green persistence checks**

Run from `back/java`: `mvn -q -Dtest=ResumeRevisionSchemaTest,ResumeSchemaTest,ReadableIdGeneratorTest,StringIdMigrationSchemaTest test`

Expected: V10/snapshot structure, Chinese maintenance comments, readable IDs,
and historical V8/V9 checksum protections all pass.

- [ ] **Step 5: Commit only the persistence task files**

Commit with:

```text
feat(resume): add immutable revision persistence
```

### Task 3: Preserve Internal Python Safety While Adding v3 Revision Identity

**Files:**
- Modify: `back/python/app/models.py`
- Modify: `back/python/app/main.py`
- Modify: `back/python/app/analysis_service.py`
- Modify: `back/python/tests/test_contract_fixtures.py`
- Modify: `back/python/tests/test_internal_security.py`
- Modify: `back/python/tests/test_callback_client.py`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/PythonAnalysisClient.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/matching/PythonAnalysisClientTest.java`

**Interfaces:**
- Consumes the frozen `internal/v3` schemas.
- Produces a v3 `/internal/v3/analysis-jobs` handler and a v3 Java dispatch to
  that path, with `revisionId` echoed unchanged in every callback.
- Retains the existing `/internal/v2/analysis-jobs` handler and its callback
  behavior for v2 callers.

- [ ] **Step 1: Write failing Python and Java boundary tests**

Add a v3 job fixture containing `revisionId: "revision001"`; assert the
Pydantic job model rejects a missing or unknown revision ID and that the
callback emitted by `analyze_job` contains the exact input revision ID.
Add a FastAPI test for `/internal/v3/analysis-jobs` with the internal token.
Add a Java client test that captures a POST to `/internal/v3/analysis-jobs`
and asserts the serialized body contains the task, callback, and revision IDs
without plaintext API-key logging.

```python
assert callback["revisionId"] == "revision001"
assert client.post("/internal/v3/analysis-jobs", headers=auth, json=job).status_code == 202
```

- [ ] **Step 2: Run focused tests and verify red**

Run from `back/python`: `py -3.11 -m pytest tests/test_contract_fixtures.py tests/test_internal_security.py tests/test_callback_client.py -q`

Run from `back/java`: `mvn -q -Dtest=PythonAnalysisClientTest test`

Expected: the tests fail because the v3 route/model/serializer does not yet
exist.

- [ ] **Step 3: Implement additive v3 internal transport**

Add `revision_id` with the exact `^revision[0-9]{3,}$` constraint to the
Python job and callback models. Refactor the FastAPI handler into one private
submission function so v2 and v3 routes share the same token, callback URL,
redaction, and background-task checks. The v3 route must not accept unknown
fields, and the v2 model must remain source-compatible.

Update Java's v3 job record and dispatch URI. Keep the existing v2 client
construction path available for legacy task creation; never include revision
content in logs or exception messages.

- [ ] **Step 4: Run green Python/Java boundary checks**

Run from `back/python`: `py -3.11 -m pytest tests/test_contract_fixtures.py tests/test_internal_security.py tests/test_callback_client.py -q`

Run from `back/java`: `mvn -q -Dtest=PythonAnalysisClientTest,InternalAnalysisCallbackControllerTest test`

Expected: v3 identity round-trips, v2 remains accepted, invalid service-token
and callback-URL paths remain fail-closed, and no secret appears in a failure.

- [ ] **Step 5: Commit only the internal-transport task files**

Commit with:

```text
feat(matching): add v3 revision-aware internal jobs
```

### Task 4: Implement Java Publication, Re-Match, and Authorization Flow

**Files:**
- Create: `back/java/src/main/java/com/resumethinking/platform/resumes/MatchSubmissionService.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/resumes/MatchSubmissionController.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeMatchContext.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeLifecycleService.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeCache.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeController.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/MatchTaskService.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/MatchTaskController.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/InternalAnalysisCallbackController.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/MatchTaskRepository.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/MatchTaskJpaRepository.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/matching/JpaMatchTaskRepository.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/auth/ApiExceptionHandler.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/resumes/ResumeLifecycleServiceTest.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/resumes/ResumeControllerTest.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/resumes/MatchSubmissionServiceTest.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/resumes/MatchSubmissionHttpBoundaryTest.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/matching/MatchTaskServiceTest.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/matching/MatchTaskHttpBoundaryTest.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/matching/InternalAnalysisCallbackControllerTest.java`

**Interfaces:**
- Produces v3 multipart initial and re-match responses as revision-aware
  `MatchTask` values.
- Produces owner-scoped `ResumeMatchContext(title, effectiveRevisionId,
  pendingRevisionId, latestSuccessfulTaskId, llmProfileId, jobDescriptionText)`.
- Consumes the revision repository, transaction locks, existing AES-GCM
  encryption, selected user profile, and v3 Python client.

- [ ] **Step 1: Write failing lifecycle, callback, and HTTP tests**

Add these independent behavior tests before changing production code:

```java
assertThat(service.listActive(userId, UserRole.USER, page).getContent()).isEmpty();
acceptValidCallback(initialTask);
assertThat(service.listActive(userId, UserRole.USER, page).getContent())
    .extracting(Resume::getId).containsExactly(resumeId);
```

```java
var oldEffective = lifecycle.getActive(resumeId, owner, UserRole.USER);
var replacement = submissions.rematch(owner, replacementFile, "new-title", command);
acceptFailedCallback(replacement.task());
assertThat(lifecycle.getActive(resumeId, owner, UserRole.USER).getEffectiveRevisionId())
    .isEqualTo(oldEffective.getEffectiveRevisionId());
```

Cover publication title races, same-owner soft-delete title reuse, restore
conflict, owner/admin isolation, pending retry/deletion context, v2 task/result
reads, and callbacks after deletion/archive/supersession. HTTP tests must prove v3
multipart accepts only TXT/DOCX and error responses use safe codes/messages.

- [ ] **Step 2: Run the focused Java suite and verify red**

Run from `back/java`: `mvn -q -Dtest=MatchSubmissionServiceTest,MatchSubmissionHttpBoundaryTest,ResumeLifecycleServiceTest,ResumeControllerTest,MatchTaskServiceTest,MatchTaskHttpBoundaryTest,InternalAnalysisCallbackControllerTest test`

Expected: new tests fail because pending publication, re-match context, v3
routes, revision identity, and duplicate-title handling are absent.

- [ ] **Step 3: Implement the smallest transactionally safe lifecycle**

`MatchSubmissionService` creates an encrypted initial pending revision plus
task in one transaction. Re-match with unchanged file/title creates a task for
the effective revision and `NOT_REQUESTED`; a changed file/title creates a
pending replacement, blocks prior candidate tasks under the logical-resume
lock, and dispatches v3.

In the successful v3 callback transaction, validate evidence against the
task's revision, persist result/evidence, mark the task succeeded, and then
publish only if the candidate remains current. Switch effective pointer/title
metadata and update cache after commit. On the MySQL title index conflict,
retain the validated result, set publication state
`REJECTED_DUPLICATE_TITLE`, and return the stable `DUPLICATE_RESOURCE` detail
through normal task retrieval; do not publish or cache the candidate.

Change result/task ownership checks to authorize the active logical resume and
the task's stored revision, rather than requiring the logical JPA optimistic
lock version to still equal an old task. Soft deletion/archive continues to
hide tasks/results and block in-flight callbacks. Keep v2 controller methods
and response fields compatible; add a v3 callback mapping that requires the
revision ID.

- [ ] **Step 4: Run green focused and compatibility tests**

Run from `back/java`: `mvn -q -Dtest=MatchSubmissionServiceTest,MatchSubmissionHttpBoundaryTest,ResumeLifecycleServiceTest,ResumeControllerTest,MatchTaskServiceTest,MatchTaskHttpBoundaryTest,InternalAnalysisCallbackControllerTest test`

Run from `back/java`: `mvn -q -Dtest=AuthenticationHttpBoundaryTest,SecurityBoundaryTest,AuthLoginRegressionTest,LlmProfileBoundaryTest test`

Expected: v3 lifecycle rules pass while login, authorization envelopes,
model-profile APIs, v2 routes, delete/recovery, and report reads remain green.

- [ ] **Step 5: Commit only the Java lifecycle task files**

Commit with:

```text
feat(resume): publish evidence-validated revisions
```

### Task 5: Move the Vue Workflow to v3 and Add Re-Match/Report Controls

**Files:**
- Modify: `front/src/api/contracts.ts`
- Modify: `front/src/api/lifecycle.ts`
- Modify: `front/src/i18n/messages.ts`
- Modify: `front/src/views/UploadMatchView.vue`
- Modify: `front/src/views/UploadMatchView.spec.ts`
- Modify: `front/src/views/ResumeListView.vue`
- Modify: `front/src/views/ResumeListView.spec.ts`
- Create: `front/src/views/ResumeRematchView.vue`
- Create: `front/src/views/ResumeRematchView.spec.ts`
- Modify: `front/src/views/MatchResultView.vue`
- Modify: `front/src/views/MatchResultView.spec.ts`
- Modify: `front/src/router/index.ts`
- Modify: `front/src/router.spec.ts`
- Modify: `front/src/style.css`

**Interfaces:**
- Consumes v3 `submitInitialMatch`, `submitResumeRematch`, `listEffectiveResumes`, `getResumeMatchContext`, and `deleteV3Resume` API methods.
- Produces `/resumes/:resumeId/rematch`, Chinese form/error states, row-level report navigation, and top notifications for completed/failed operations.
- Keeps existing login, profile, recovery, `/matches/:taskId`, and delete confirmation controls unchanged.

- [ ] **Step 1: Write failing Vitest specifications**

Replace the upload test's two v2 calls with one v3 initial submission
expectation:

```ts
expect(lifecycleApi.submitInitialMatch).toHaveBeenCalledWith(expect.objectContaining({
  file, llmProfileId: 'profile001', jobFamily: 'JAVA_BACKEND',
  jobDescriptionText: expect.any(String), idempotencyKey: expect.stringMatching(/^match-/),
}))
```

Add list tests that render `重新匹配`, conditionally render `查看匹配报告` only
when `latestSuccessfulTaskId` exists, and preserve the existing delete route
by role. Add re-match tests for prefilled context, an optional replacement
file, a title-only candidate edit, unchanged-file re-match, duplicate-title
notification, and task-result navigation. Add result-page tests for
`REJECTED_DUPLICATE_TITLE` explaining that the report is valid but the resume
was not made effective, plus a failed pending candidate's confirmed deletion
and safe `TASK_GONE` result. Add router tests for authenticated loading of the new
route without changing existing routes.

- [ ] **Step 2: Run focused frontend tests and verify red**

Run: `corepack pnpm --dir front vitest run src/views/UploadMatchView.spec.ts src/views/ResumeListView.spec.ts src/views/ResumeRematchView.spec.ts src/views/MatchResultView.spec.ts src/router.spec.ts`

Expected: tests fail because the v3 API methods, re-match view, list actions,
and publication-state copy do not exist.

- [ ] **Step 3: Implement the v3 client and Chinese operational UI**

Add explicit v3 request/response types. Generate a unique idempotency key per
submission. Make `UploadMatchView` call one atomic v3 submission and poll the
existing task route. Add `ResumeRematchView` with title input, optional file
input, profile selector, Java-backend description, expected effective revision
ID, and safe stale/conflict handling.

Use the existing `showTopNotification` boundary for submission success,
failure, duplicate title, and result/report transitions; keep local field
validation inline. `ResumeListView` uses v3 effective results and routes to
`/matches/:taskId` for latest reports. Do not alter authentication interceptor
behavior, LLM profile storage, delete confirmation wording, or recovery views.

- [ ] **Step 4: Run green frontend regressions and build**

Run: `corepack pnpm --dir front vitest run src/views/UploadMatchView.spec.ts src/views/ResumeListView.spec.ts src/views/ResumeRematchView.spec.ts src/views/MatchResultView.spec.ts src/router.spec.ts`

Run: `corepack pnpm --dir front test`

Run: `corepack pnpm --dir front build`

Expected: the new flow and existing login/profile/recovery/delete components
all compile and pass their tests; no alert exposes an API key, raw provider
message, callback token, or encrypted resume field.

- [ ] **Step 5: Commit only the frontend lifecycle task files**

Commit with:

```text
feat(front): add effective resume rematch workflow
```

### Task 6: Prove Cross-Layer Compatibility and the Full User Journey

**Files:**
- Modify: `front/e2e/resume-lifecycle.spec.ts`
- Modify: `front/e2e/login-profiles-rendering.spec.ts`
- Modify: `tests/integration/assert_mvp_flow.py`
- Modify: `tests/integration/run_mvp_flow.ps1`
- Modify: `docs/verification/mvp-evidence.md`

**Interfaces:**
- Consumes the completed v3 UI/API lifecycle and existing v2 login/profile
and recovery flows.
- Produces reproducible evidence that a successful new submission is visible,
a failed replacement preserves the old effective report, title reuse works
after soft deletion, and existing post-login/profile rendering is unaffected.

- [ ] **Step 1: Write failing end-to-end and integration expectations**

Extend the live lifecycle test to use the UI's v3 submission path, wait for
publication, open the report from the effective list, submit a re-match with a
different title/file, and verify the previous row remains when the replacement
is forced to fail. Then soft-delete the effective resume, submit a new
same-title resume, and verify publication succeeds; a restore attempt for the
old record must display the duplicate-title error.

Add an explicit browser assertion that the existing login-to-profiles path
still reaches `/profiles` without `pageerror` or console errors.

- [ ] **Step 2: Run focused checks and verify red**

Run: `corepack pnpm --dir front test:browser -- resume-lifecycle.spec.ts login-profiles-rendering.spec.ts`

Run from repository root: `py -3.11 tests/integration/assert_mvp_flow.py`

Expected: new v3 lifecycle assertions fail until the user journey and test
harness know the new endpoints and publication state.

- [ ] **Step 3: Implement only the test-harness and evidence updates**

Use environment-provided local Java/Python/provider settings exactly as the
existing live test does. Do not write a real API key into a fixture, source
file, report, screenshot, browser storage assertion, or log. Record command,
date, fixture name, exit code, and the observed state transitions in
`docs/verification/mvp-evidence.md`.

- [ ] **Step 4: Run final fresh verification**

Run: `corepack pnpm --dir contracts run lint`

Run: `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs`

Run from `back/java`: `mvn -q test`

Run from `back/python`: `py -3.11 -m pytest tests -q`

Run: `corepack pnpm --dir front test`

Run: `corepack pnpm --dir front build`

Run: `corepack pnpm --dir front test:browser -- login-profiles-rendering.spec.ts`

Run the live lifecycle browser test only after its Java API, Python worker,
Redis, MySQL, and test provider prerequisites are started; otherwise record
that it is intentionally skipped by its existing environment guard rather
than treating a skipped test as proof.

- [ ] **Step 5: Commit only the cross-layer evidence files**

Commit with:

```text
test: cover effective resume lifecycle
```
