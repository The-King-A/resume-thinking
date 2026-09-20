# Interview Practice Phase One Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an evidence-bound Java-backend interview-preparation and one-answer feedback workflow without changing released v2/v3 matching behavior.

**Architecture:** Add a versioned v4 interview contract and a separate `interviews` Java module. Java authorizes all public requests, persists the session/question/answer/feedback lifecycle, encrypts answer text, and applies callback/idempotency checks; Python receives only redacted, allow-listed interview context and returns strict structured question or feedback callbacks. Vue consumes only the Java v4 API through new interview routes and never treats feedback as a resume fact.

**Tech Stack:** Java 21, Spring Boot 3.4, JPA, Flyway/MySQL 8, Redis-derived cache, FastAPI, Pydantic 2, `rfc8785`, Vue 3, TypeScript, Vue Router, Vitest, Playwright, OpenAPI 3.1, Ajv/Redocly.

**Spec:** `docs/superpowers/specs/2026-09-20-interview-practice-design.md`

## Global Constraints

- Product source: `C:/Users/theking.guo/Documents/Codex/2026-08-24/plugin-browser-openai-bundled-x20/outputs/ai-resume-job-matching-project.md`; accepted feature scope: `E:/final_lecture/6023032108-郭航-求职画像驱动的岗位适配与面试推演平台的设计与实现-报告.docx` 第 8 章。
- The authoritative new interface artifacts are `contracts/openapi/v4/openapi.yaml`, `contracts/internal/v4/interview-job.schema.json`, `contracts/internal/v4/interview-callback.schema.json`, and `contracts/fixtures/v4/interview/`.
- Preserve every v2/v3 route, schema, fixture, matching score, resume lifecycle rule, and frontend behavior. New v4 code must not mutate an existing v2/v3 response shape or field meaning.
- The only supported role family is `JAVA_BACKEND`; PDF, other job families, multi-round follow-up, resume export, profile write-back, fairness experiments, vector persistence, Nginx, and full Docker deployment remain out of scope.
- Java is the only public API, authorization, task-state, database-write, cache-write, and callback-acceptance authority. Python has no login, no direct MySQL/Redis access, and no public interview route.
- A v4 create request uses only `matchTaskId` plus an idempotency key; it does not require `resumeId`, `revisionId`, `llmProfileId`, `jobFamily`, or `matchResultId`. Java resolves those values from the owner-scoped successful task/result, preventing forged route/query identifiers.
- Service-to-service authentication uses `X-Internal-Service-Token`. Per-task `callbackToken` is inside the protected Java-to-Python v4 job only, must not appear in public responses/logs/fixtures, and must be returned only in the Python-to-Java callback body.
- Resume text, answer text, API keys, callback tokens, encrypted bytes/nonces, provider diagnostics, and raw provider output must never appear in public errors, browser notifications, test failure text, fixtures, or logs.
- The source resume is locked before an interview session or callback locks the session. Deleting or archiving a resume marks linked interview sessions `DELETED`, removes child content, evicts derived cache, and rejects a late callback without recreating content.
- `SUPPORTED_FACT` and `WORDING_ONLY_REWRITE` feedback must reference allow-listed evidence. New numbers, titles, responsibilities, dates, credentials, outcomes, or experience use `NEEDS_USER_CONFIRMATION` and never update a resume or durable profile in this slice.
- This worktree is dirty. Before changing a pre-existing file, record `Get-FileHash` and the focused `git diff` for that file in the task report. Never reset, restore, stash, stage, commit, or format unrelated work. Stage only named task files and only the intended hunks.

---

## File Structure

| Area | Files and responsibility |
| --- | --- |
| Contract | v4 public OpenAPI, Java-to-Python job schema, Python-to-Java callback schema, valid/invalid/timeout/duplicate/delete fixtures, and the shared validator. |
| Java domain | `interviews/` owns session state, questions, encrypted answers, feedback, confirmation records, repositories, v4 HTTP controllers, callback validation, and resume-lifecycle blocking. |
| Java persistence | Flyway V16 and the fresh-schema snapshot define `interview_sessions`, `interview_questions`, `interview_answers`, `interview_feedback`, `interview_confirmations`, and callback receipt storage. |
| Python worker | Strict v4 Pydantic models, bounded interview prompt/response parsing, redaction, callback hashing, FastAPI internal route, and callback retry stop codes. |
| Vue | v4 interview types/client, protected preparation/session/feedback views, a report-page entry point, route guards, safe error labels, responsive styles, and unit/browser tests. |
| Evidence | Contract validation, Java/Python boundary tests, offline integration assertions, a controlled local Java-Python scenario, and an updated verification record that distinguishes simulated model behavior from real-provider evidence. |

## Slice Brief

**User-visible outcome:** A user with a successful evidence-backed Java-backend match can open interview preparation, receive one question in each of the four documented categories, answer one selected question, read one structured feedback result, confirm or reject an explicitly flagged claim, and end the session.

**Affected data and deletion:** Session/question metadata, encrypted answer text, redacted feedback, claim confirmations, callback receipts, and optional short-lived Redis keys are new. Ending a session or hiding its source resume removes all readable child content and derived cache; the session row remains as a `DELETED` audit marker. Database backups and an external provider's retention policy are not represented as physical deletion.

**Authorization:** A `USER` may only create/read/answer/confirm/delete sessions whose effective resume and matching task belong to that user. An `ADMIN` can only use v4 if the existing role policy explicitly grants cross-owner access; phase one deliberately uses the stricter owner-only rule for every interview route, including administrators, because interview answers are new sensitive content and the product documents do not require administrative reading of them.

**Failure behavior:** Invalid/missing matching evidence, stale revisions, cross-owner references, malformed structured output, timeout, duplicate callback conflict, duplicate answer conflict, deleted session, and hidden resume are explicit errors or terminal session states. No fallback question or feedback is invented after a model failure.

**First-red commands:**

```powershell
pnpm --dir contracts run validate
back\java\mvnw.cmd -q -Dtest=InterviewSchemaTest,InterviewSessionServiceTest,InterviewSessionHttpBoundaryTest,InternalInterviewCallbackSecurityTest test
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests/test_interview_contracts.py back/python/tests/test_interview_service.py back/python/tests/test_interview_internal_security.py -q
pnpm --dir front test -- --run src/api/interview.spec.ts src/views/InterviewPrepareView.spec.ts src/views/InterviewSessionView.spec.ts src/views/InterviewFeedbackView.spec.ts
```

## Contract Values Frozen By This Plan

```text
InterviewSessionState = QUESTION_GENERATING | WAITING_FOR_ANSWER |
                        ANSWER_ANALYZING | FEEDBACK_READY | COMPLETED |
                        FAILED | DELETED
InterviewWorkType     = QUESTION_GENERATION | ANSWER_ANALYSIS
InterviewQuestionType = BASIC_CONFIRMATION | PROJECT_DEEP_DIVE |
                        JOB_SCENARIO | SYNTHESIS_FOLLOW_UP
InterviewDifficulty   = BASIC | INTERMEDIATE | ADVANCED
FeedbackLevel         = HIGH | MEDIUM | LOW | INSUFFICIENT_EVIDENCE
ClaimState            = SUPPORTED_FACT | WORDING_ONLY_REWRITE |
                        NEEDS_USER_CONFIRMATION | RISKY_OR_UNSUPPORTED
```

Business IDs are `sessionNNN`, `questionNNN`, `answerNNN`, `feedbackNNN`, and `confirmationNNN`. Internal callbacks reuse the existing globally allocated `callbackNNN` namespace; V16 allocates the new entity prefixes in `id_sequences` and does not alter any historical sequence.

### Task 1: Freeze the v4 Contract and Shared Fixtures

**Files:**

- Create: `contracts/openapi/v4/openapi.yaml`
- Create: `contracts/internal/v4/interview-job.schema.json`
- Create: `contracts/internal/v4/interview-callback.schema.json`
- Create: `contracts/fixtures/v4/interview/session-create-valid.json`
- Create: `contracts/fixtures/v4/interview/session-create-invalid.json`
- Create: `contracts/fixtures/v4/interview/session-valid.json`
- Create: `contracts/fixtures/v4/interview/questions-valid.json`
- Create: `contracts/fixtures/v4/interview/answer-submit-valid.json`
- Create: `contracts/fixtures/v4/interview/answer-submit-invalid.json`
- Create: `contracts/fixtures/v4/interview/feedback-valid.json`
- Create: `contracts/fixtures/v4/interview/confirmation-valid.json`
- Create: `contracts/fixtures/v4/interview/job-question-generation-valid.json`
- Create: `contracts/fixtures/v4/interview/job-answer-analysis-valid.json`
- Create: `contracts/fixtures/v4/interview/callback-questions-valid.json`
- Create: `contracts/fixtures/v4/interview/callback-feedback-valid.json`
- Create: `contracts/fixtures/v4/interview/callback-duplicate.json`
- Create: `contracts/fixtures/v4/interview/callback-timeout.json`
- Create: `contracts/fixtures/v4/interview/callback-stale.json`
- Create: `contracts/fixtures/v4/interview/callback-after-delete.json`
- Create: `contracts/fixtures/v4/interview/error-session-gone.json`
- Modify: `contracts/package.json`
- Modify: `contracts/scripts/validate-contracts.mjs`
- Modify: `contracts/README.md`

**Interfaces:**

- Produces public `POST /api/v4/interview-sessions`, `GET /api/v4/interview-sessions/{sessionId}`, `GET /api/v4/interview-sessions/{sessionId}/questions`, `POST /api/v4/interview-sessions/{sessionId}/questions/regenerate`, `POST /api/v4/interview-sessions/{sessionId}/answers`, `GET /api/v4/interview-sessions/{sessionId}/feedback`, `POST /api/v4/interview-sessions/{sessionId}/confirmations`, and `DELETE /api/v4/interview-sessions/{sessionId}`.
- Produces authenticated internal `POST /internal/v4/interview-jobs` and authenticated callback `POST /internal/v4/interview-results`.
- Produces strict input/output payloads with no unknown top-level fields. `callbackToken` is allowed only in the internal v4 job/callback schemas and fixture placeholders must not contain a usable secret.

- [ ] **Step 1: Write failing v4 validator assertions before creating v4 artifacts**

Extend `contracts/scripts/validate-contracts.mjs` with a dedicated v4 loader rather than forcing interview schemas through the existing v1-v3 analysis-job loop. Add the following assertions:

```js
const v4 = await loadInterviewV4();
const create = await assertValidV4('interview/session-create-valid.json', v4.validate('CreateInterviewSessionRequest'));
if (create.matchTaskId !== 'task001' || 'matchResultId' in create) {
  throw new Error('v4 interview creation must bind a matching task without exposing a result id');
}
const questionCallback = await assertValidV4('interview/callback-questions-valid.json', v4.validateCallback);
if (questionCallback.payloadHash !== v4CallbackPayloadHash(questionCallback)) {
  throw new Error('v4 interview callback hash must cover the complete callback envelope');
}
await assertInvalidV4('interview/session-create-invalid.json', v4.validate('CreateInterviewSessionRequest'));
await assertInvalidV4('interview/answer-submit-invalid.json', v4.validate('SubmitInterviewAnswerRequest'));
```

Also assert that a question callback contains exactly four question types, that every `evidenceIds` member is `evidenceNNN`, that `NEEDS_USER_CONFIRMATION` claims are never marked `applied`, and that all v1/v2/v3 validation calls still execute.

- [ ] **Step 2: Run the contract validator and verify red**

Run:

```powershell
pnpm --dir contracts run validate
```

Expected: failure because v4 files, v4 schema registration, and v4 fixture validators do not yet exist.

- [ ] **Step 3: Add the minimal public and internal v4 schemas**

Copy only common security/error components from v3 and declare the exact v4 create shape:

```yaml
CreateInterviewSessionRequest:
  type: object
  additionalProperties: false
  required: [matchTaskId, idempotencyKey]
  properties:
    matchTaskId: { type: string, pattern: '^task[0-9]{3,}$' }
    idempotencyKey: { type: string, minLength: 16, maxLength: 128 }
```

Require `expectedVersion` and a new `idempotencyKey` for regeneration; require `questionId`, `answerText` with `minLength: 1`, `maxLength: 8000`, `expectedSessionVersion`, and `idempotencyKey` for answer submission. Define a single `InterviewFeedback` response with all five dimensions, `riskFlags`, `claims`, `evidenceIds`, `improvementSuggestion`, and `state`; do not add raw answer text to any response.

The internal job contains `workType`, `sessionId`, `revisionId`, `matchTaskId`, `attempt`, `callbackId`, `callbackUrl`, `callbackToken`, `provider`, a bounded requirement/evidence context, and either a question-generation constraint or a redacted answer-analysis payload. The callback includes the same identifiers, `outcome`, a `questions` array only for `QUESTION_GENERATION`, or a `feedback` object only for `ANSWER_ANALYSIS`. Both schemas set `additionalProperties: false` and reject `ownerId`, `userId`, database fields, host paths, JWTs, passwords, and persistence commands.

- [ ] **Step 4: Add fixtures that prove legal and illegal state-boundary payloads**

Use only synthetic text and non-usable credential placeholders. The valid question callback must contain exactly one of each type:

```json
[
  "BASIC_CONFIRMATION",
  "PROJECT_DEEP_DIVE",
  "JOB_SCENARIO",
  "SYNTHESIS_FOLLOW_UP"
]
```

The valid feedback fixture must include the five `FeedbackLevel` values, at least one allow-listed evidence reference, and one `NEEDS_USER_CONFIRMATION` claim whose `claimText` is not promoted to a resume update. The duplicate fixture must reuse identical `callbackId` and `payloadHash`; stale and after-delete fixtures remain schema-valid but state the Java rejection code expected by the test context.

- [ ] **Step 5: Run green contract and compatibility checks**

Run:

```powershell
pnpm --dir contracts run lint
pnpm --dir contracts run validate
```

Expected: OpenAPI v1-v4 lint successfully; v4 valid/invalid/timeout/duplicate/delete fixtures are classified correctly; all v1-v3 checks remain green.

- [ ] **Step 6: Commit only the v4 contract task files**

Run `git diff --cached --name-only` and verify it contains only this task's named files. Commit:

```text
feat(contract): add v4 interview practice contract
```

### Task 2: Add Interview IDs, Persistence, and Resume-Hide Blocking

**Files:**

- Modify: `back/java/src/main/java/com/resumethinking/platform/ids/BusinessIdType.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/TestIds.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/ids/ReadableIdGeneratorTest.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewSession.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewQuestion.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewAnswer.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewFeedback.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewConfirmation.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewCallbackReceipt.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewSessionRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewQuestionRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewAnswerRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewFeedbackRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewConfirmationRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewCallbackReceiptRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewSessionJpaRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewQuestionJpaRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewAnswerJpaRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewFeedbackJpaRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewConfirmationJpaRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewCallbackReceiptJpaRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/JpaInterviewSessionRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/JpaInterviewQuestionRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/JpaInterviewAnswerRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/JpaInterviewFeedbackRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/JpaInterviewConfirmationRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/JpaInterviewCallbackReceiptRepository.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewSessionResumeTaskBlocker.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/resumes/ResumeLifecycleService.java`
- Create: `back/java/src/main/resources/db/migration/V16__interview_practice_phase_one.sql`
- Modify: `database/resume_thinking_schema.sql`
- Create: `back/java/src/test/java/com/resumethinking/platform/interviews/InterviewSchemaTest.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/interviews/InterviewSessionDomainTest.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/resumes/ResumeLifecycleServiceTest.java`

**Interfaces:**

- Produces immutable, Java-owned records with `InterviewSession.State`, `InterviewSession.WorkType`, and all enum values frozen above.
- Produces one session per `(owner_id, idempotency_key)`, one answer per `(session_id, question_id)`, and one callback receipt per globally unique `callback_id`.
- Produces a `ResumeTaskBlocker` implementation that marks every non-deleted interview session for a hidden resume `DELETED` and removes its questions, encrypted answers, feedback, confirmations, and derived cache within the same resume lifecycle transaction.

- [ ] **Step 1: Write failing identifier, schema, domain, and lifecycle tests**

Add readable-ID tests before production changes:

```java
assertThat(ids.next(BusinessIdType.INTERVIEW_SESSION)).isEqualTo("session001");
assertThat(ids.next(BusinessIdType.INTERVIEW_QUESTION)).isEqualTo("question001");
ReadableIdGenerator.validate(BusinessIdType.INTERVIEW_CONFIRMATION, "confirmation001");
```

Create `InterviewSchemaTest` with assertions that the fresh schema and V16 both define all six tables, `session`, `question`, `answer`, `feedback`, and `confirmation` sequences, Chinese comments for every new column, encrypted `answer_ciphertext`/`answer_nonce`, FK bindings to resume/revision/match task, unique session and answer idempotency keys, and indexes for resume-state and owner-state lookup.

Create domain tests that prove these transitions:

```java
session.startQuestionGeneration(callbackId, callbackToken, now);
session.acceptQuestions(now);
assertThat(session.getState()).isEqualTo(InterviewSession.State.WAITING_FOR_ANSWER);

session.startAnswerAnalysis(answerId, callbackId, callbackToken, now);
session.acceptFeedback(now);
assertThat(session.getState()).isEqualTo(InterviewSession.State.FEEDBACK_READY);
```

Add a resume lifecycle test that hides an active resume, then asserts `InterviewSessionResumeTaskBlocker` marks the session deleted and the in-memory child repositories have no readable content.

- [ ] **Step 2: Run focused persistence tests and verify red**

Run:

```powershell
back\java\mvnw.cmd -q -Dtest=ReadableIdGeneratorTest,InterviewSchemaTest,InterviewSessionDomainTest,ResumeLifecycleServiceTest test
```

Expected: compilation or structural assertions fail because interview IDs, entities, repositories, V16, and the lifecycle blocker do not exist.

- [ ] **Step 3: Implement the minimal durable model**

Add these `BusinessIdType` values exactly:

```java
INTERVIEW_SESSION("session"), INTERVIEW_QUESTION("question"),
INTERVIEW_ANSWER("answer"), INTERVIEW_FEEDBACK("feedback"),
INTERVIEW_CONFIRMATION("confirmation")
```

`InterviewSession` stores owner/resume/revision/match task/profile IDs, job family, state, current work type, current callback ID/hash/attempt, current question/answer IDs, failure code, idempotency key, `clearedAt`, timestamps, and `@Version`. It must expose state-transition methods only; it must not expose callback tokens after construction.

`InterviewQuestion` stores question text, one frozen type, difficulty, requirement ID/text, a JSON-encoded allow-listed evidence ID set, and generation reason. `InterviewAnswer` stores an AES-GCM ciphertext/nonce plus a SHA-256 answer fingerprint, not plaintext. `InterviewFeedback` stores only a validated, redacted JSON payload. `InterviewConfirmation` stores a claim ID, decision, feedback ID, actor ID, and timestamp. All repository interfaces include deterministic in-memory implementations for unit tests and JPA adapters for application wiring.

Create V16 transactionally with this table relationship:

```sql
interview_sessions  -> resumes, resume_revisions, analysis_tasks, llm_profiles
interview_questions -> interview_sessions
interview_answers   -> interview_sessions, interview_questions
interview_feedback  -> interview_sessions, interview_answers, interview_questions
interview_confirmations -> interview_sessions, interview_feedback
interview_callback_receipts -> callback_id unique
```

Do not edit applied V1-V15 migrations. Add the same V16-final schema to `database/resume_thinking_schema.sql`, including Chinese maintenance comments and only safe readable metadata in `v_resumes_readable`.

Change the Spring `ResumeLifecycleService` primary constructor to accept `List<ResumeTaskBlocker>`, keep all existing compatibility constructors by wrapping their one blocker with `List.of(...)`, and call every blocker after acquiring the resume lock. This lets `MatchTaskResumeTaskBlocker` and `InterviewSessionResumeTaskBlocker` execute under the established resume-then-task/session lock order without creating a bean ambiguity.

- [ ] **Step 4: Run green persistence and lifecycle checks**

Run:

```powershell
back\java\mvnw.cmd -q -Dtest=ReadableIdGeneratorTest,InterviewSchemaTest,InterviewSessionDomainTest,ResumeLifecycleServiceTest,ResumeRevisionSchemaTest test
```

Expected: all new ID/schema/state/deletion checks pass and existing resume lifecycle/revision protections remain green.

- [ ] **Step 5: Commit only persistence task files**

Commit:

```text
feat(interview): add phase one persistence and lifecycle blocking
```

### Task 3: Implement Java v4 Authorization, Dispatch, Callback, and HTTP Boundaries

**Files:**

- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewSessionService.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewSessionController.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InternalInterviewCallbackController.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/PythonInterviewClient.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewAnalysisCallbackRequest.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewCallbackPayloadHash.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewExceptions.java`
- Create: `back/java/src/main/java/com/resumethinking/platform/interviews/InterviewResponse.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/auth/ApiExceptionHandler.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/config/SecurityConfig.java`
- Modify: `back/java/src/test/java/com/resumethinking/platform/config/InternalCallbackSecurityTest.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/interviews/InterviewSessionServiceTest.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/interviews/InterviewSessionHttpBoundaryTest.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/interviews/InternalInterviewCallbackControllerTest.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/interviews/PythonInterviewClientTest.java`

**Interfaces:**

- `InterviewSessionService.create(CreateInterviewSessionCommand)` returns a `QUESTION_GENERATING` session only when the caller owns an active effective revision and a succeeded evidence-backed match task; the command contains only `actorId`, `role`, `matchTaskId`, and `idempotencyKey`.
- `InterviewSessionService.submitAnswer(sessionId, actorId, SubmitInterviewAnswerCommand)` stores encrypted text and returns an `ANSWER_ANALYZING` session only once per question.
- `InterviewSessionService.acceptCallback(InterviewAnalysisCallbackRequest)` returns `CallbackResponse(code, accepted)` with exact duplicate/stale/deleted behavior.
- `PythonInterviewClient.dispatch(InternalInterviewJob)` sends only authenticated JSON to `/internal/v4/interview-jobs`; it does not modify `PythonAnalysisClient` or existing analysis dispatch behavior.

- [ ] **Step 1: Write failing service and HTTP tests**

Build an in-memory happy-path fixture containing an active effective resume, a succeeded v3 `MatchTask`, a persisted `AnalysisResult`, and at least one `AnalysisEvidence`. Test creation and owner isolation:

```java
InterviewSession created = service.create(new CreateInterviewSessionCommand(
        owner, UserRole.USER, taskId, "interview-key-0001"));

assertThat(created.getState()).isEqualTo(InterviewSession.State.QUESTION_GENERATING);
assertThatThrownBy(() -> service.getSession(created.getId(), otherOwner))
        .isInstanceOf(InterviewSessionNotFoundException.class);
```

Write tests for each critical mutation before implementation:

```java
assertThat(service.acceptCallback(questionCallback(created))).extracting(CallbackResponse::code)
        .isEqualTo("ACCEPTED");
assertThat(service.listQuestions(created.getId(), owner)).hasSize(4);

InterviewAnswer answer = service.submitAnswer(created.getId(), owner,
        new SubmitInterviewAnswerCommand(questionId, "我在项目中负责接口设计。", created.getVersion(), "answer-key-000001"));
assertThat(answer.getCiphertext()).doesNotContain("我在项目中负责接口设计。".getBytes(UTF_8));
```

Cover: unfinished/missing evidence match rejection; stale revision rejection; cross-owner read/answer/confirm/delete; a duplicate create key returning the same session; same answer key/content replaying the original answer; same answer key/different content returning `INTERVIEW_ANSWER_CONFLICT`; question callback duplicate/replay; callback-ID hash conflict; stale attempt; deleted session callback; model timeout; invalid evidence ID; unsupported claim state; regeneration only before an answer; completion after feedback; and resume delete/archival after Python dispatch.

Add MockMvc tests with exact v4 JSON and safe errors. Add a security test that `SecurityConfig.INTERNAL_INTERVIEW_CALLBACK_V4_PATH` accepts only the valid internal service token and that a valid user JWT cannot substitute for it.

- [ ] **Step 2: Run focused Java tests and verify red**

Run:

```powershell
back\java\mvnw.cmd -q -Dtest=InterviewSessionServiceTest,InterviewSessionHttpBoundaryTest,InternalInterviewCallbackControllerTest,PythonInterviewClientTest,InternalCallbackSecurityTest test
```

Expected: tests fail because v4 services, routes, errors, callback hashing, and internal dispatch do not exist.

- [ ] **Step 3: Implement owner-scoped session creation and question dispatch**

Implement this create sequence in one write transaction:

```java
MatchTask task = matchTasks.lockById(command.matchTaskId())
        .filter(value -> value.getCreatorId().equals(command.actorId())
                && value.getState() == MatchTask.State.SUCCEEDED
                && value.isResultAvailable()
                && value.getRevisionId() != null
                && (value.getPublicationState() == MatchTask.PublicationState.NOT_REQUESTED
                    || value.getPublicationState() == MatchTask.PublicationState.PUBLISHED))
        .orElseThrow(InterviewMatchNotReadyException::new);
Resume resume = lifecycle.lockActiveForRevision(task.getResumeId(), task.getRevisionId())
        .filter(value -> value.getOwnerId().equals(command.actorId())
                && task.getRevisionId().equals(value.getEffectiveRevisionId()))
        .orElseThrow(InterviewMatchNotReadyException::new);
AnalysisResult result = results.findByTaskId(task.getId())
        .filter(value -> value.resumeId().equals(resume.getId())
                && value.revisionId().equals(task.getRevisionId()))
        .orElseThrow(InterviewMatchNotReadyException::new);
```

Require non-empty `analysisEvidence.findByTaskId(task.getId())`, decrypt the profile only during dispatch, allocate a new `callbackNNN`, hash a random callback token, set `QUESTION_GENERATING`, persist the session, and dispatch only required requirement text/status/gap and allow-listed redacted evidence. If dispatch fails, transition to `FAILED` with `PYTHON_SERVICE_UNAVAILABLE` or `PYTHON_SERVICE_AUTHENTICATION_FAILED`; never leave a fabricated question set.

For question callbacks, require exactly one question of each frozen type; every cited evidence ID must belong to the original analysis task; persist questions then transition to `WAITING_FOR_ANSWER`. For answer callbacks, lock the active resume then session, validate answer/question/version/callback token/hash/attempt, persist validated redacted feedback, and transition to `FEEDBACK_READY`. Use `InterviewCallbackPayloadHash` with RFC 8785-style canonical JSON and SHA-256, including `sessionId`, `revisionId`, `workType`, `attempt`, callback ID/token, outcome, correlation ID, and result body.

Implement `DELETE` idempotently: owner-only lock, transition to `DELETED`, delete child rows, evict `interview:v4:<sessionId>` if used, and retain only session audit metadata/receipt hashes. `InterviewSessionResumeTaskBlocker` must perform the same transition under the resume lock.

- [ ] **Step 4: Add public responses and safe exception mapping**

Use these response mappings in `ApiExceptionHandler`:

```java
@ExceptionHandler(InterviewSessionNotFoundException.class)
ResponseEntity<?> interviewNotFound() { return error(HttpStatus.NOT_FOUND, "INTERVIEW_SESSION_NOT_FOUND"); }

@ExceptionHandler(InterviewSessionGoneException.class)
ResponseEntity<?> interviewGone() { return error(HttpStatus.GONE, "INTERVIEW_SESSION_GONE"); }

@ExceptionHandler(InterviewQuestionNotReadyException.class)
ResponseEntity<?> questionNotReady() { return error(HttpStatus.CONFLICT, "INTERVIEW_QUESTION_NOT_READY"); }
```

Treat `/api/v4/` and `/internal/v4/` as the same safe error envelope shape as v3. Extend SecurityConfig with exactly one new internal callback constant and include that path in both authorization and `InternalServiceTokenFilter.shouldNotFilter` path checks.

- [ ] **Step 5: Run green Java boundary tests**

Run:

```powershell
back\java\mvnw.cmd -q -Dtest=InterviewSessionServiceTest,InterviewSessionHttpBoundaryTest,InternalInterviewCallbackControllerTest,PythonInterviewClientTest,InternalCallbackSecurityTest,MatchTaskServiceTest,MatchTaskHttpBoundaryTest test
```

Expected: v4 ownership/state/hash/deletion behavior passes and existing matching tests remain green.

- [ ] **Step 6: Commit only Java orchestration task files**

Commit:

```text
feat(interview): add secure v4 session orchestration
```

### Task 4: Implement the Python v4 Interview Worker With Redaction and Strict Output Validation

**Files:**

- Create: `back/python/app/interview_models.py`
- Create: `back/python/app/interview_service.py`
- Modify: `back/python/app/main.py`
- Modify: `back/python/app/callback_client.py`
- Modify: `back/python/app/openai_compatible.py`
- Create: `back/python/tests/test_interview_contracts.py`
- Create: `back/python/tests/test_interview_service.py`
- Create: `back/python/tests/test_interview_internal_security.py`
- Modify: `back/python/tests/test_callback_client.py`
- Modify: `back/python/tests/test_openai_compatible.py`

**Interfaces:**

- `InterviewJob` is an alias-only, extra-forbidden Pydantic model for the frozen v4 job schema.
- `analyze_interview_job(job: InterviewJob | dict) -> dict` returns a hash-bound v4 callback and maps no model failure to a successful response.
- `OpenAICompatibleClient.complete_interview_structured(...)` retains current endpoint validation, bounded-response reading, JSON repair behavior, redaction, and secret filtering while validating either a question-set or feedback model.

- [ ] **Step 1: Write failing Python contract, service, and secret-boundary tests**

Create a synthetic v4 job fixture and assert strict aliases:

```python
job = InterviewJob.model_validate(question_generation_job())
assert job.work_type == "QUESTION_GENERATION"
assert job.session_id == "session001"
with pytest.raises(ValidationError):
    InterviewJob.model_validate({**question_generation_job(), "ownerId": "user001"})
```

Write generation tests that mock the model client and assert all four question types are returned once, every evidence ID is allow-listed, and callback hash covers the complete final payload. Write feedback tests that assert the answer/requirement/evidence text reaching the provider is redacted and an invented metric becomes `NEEDS_USER_CONFIRMATION` rather than `SUPPORTED_FACT`.

```python
callback = await analyze_interview_job(answer_analysis_job(answer="姓名：张三，接口延迟降低 99%"))
assert "张三" not in json.dumps(captured_provider_payload, ensure_ascii=False)
assert callback["result"]["feedback"]["claims"][0]["state"] == "NEEDS_USER_CONFIRMATION"
```

Add FastAPI tests for `/internal/v4/interview-jobs` with missing/wrong/placeholder internal tokens, bad callback URLs, Python snake-case fields, malformed base64, and a payload containing a unique secret. Each failure response must omit that secret.

- [ ] **Step 2: Run focused Python tests and verify red**

Run:

```powershell
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests/test_interview_contracts.py back/python/tests/test_interview_service.py back/python/tests/test_interview_internal_security.py back/python/tests/test_callback_client.py -q
```

Expected: collection or assertions fail because no v4 interview Pydantic models, route, service, or client completion method exists.

- [ ] **Step 3: Implement strict interview models and one bounded completion path**

Define these Pydantic types with `extra="forbid"`, alias-only validation, maximum text sizes, and validators:

```python
class InterviewQuestionSet(StrictModel):
    questions: list[InterviewQuestion]

    @model_validator(mode="after")
    def has_each_required_type_once(self) -> "InterviewQuestionSet":
        expected = {"BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"}
        if {item.question_type for item in self.questions} != expected or len(self.questions) != 4:
            raise ValueError("question set must contain each required type exactly once")
        return self
```

`InterviewFeedbackPayload` requires all five feedback dimensions. It rejects an unknown evidence ID, requires an evidence reference for `SUPPORTED_FACT` or `WORDING_ONLY_REWRITE`, and rejects claims marked as applied or resume-updating. It permits an empty claim list when the answer does not introduce a new fact.

Refactor only the JSON-completion core of `OpenAICompatibleClient` so its existing `complete_structured(AnalysisRequest)` behavior remains tested unchanged. Add `complete_interview_structured(system_instruction, request_payload, result_model)` that calls the same endpoint validation, response byte limit, safe JSON extraction, provider retry budget, output-secret filtering, and Pydantic validation. Do not create a second HTTP client with weaker validation.

- [ ] **Step 4: Implement redacted generation, feedback, and callback behavior**

`interview_service.py` must use `redact_text` on every model-bound string: requirement text, gap, evidence excerpt, current question, and answer. It must pass only preselected requirements/evidence from Java; it does not inspect a document or derive new evidence. Reuse `analysis_service._finalize_callback` only after making its type parameter accept the v4 callback model; do not duplicate a weaker hash implementation.

The error mapping is exact:

```python
except ModelUnavailable:
    callback = {**callback_base, "outcome": "TIMED_OUT", "errorCode": "INTERVIEW_MODEL_UNAVAILABLE"}
except ModelEndpointRejected:
    callback = {**callback_base, "outcome": "FAILED", "errorCode": "MODEL_ENDPOINT_REJECTED"}
except (ModelOutputInvalid, ValidationError, ValueError, AttributeError):
    callback = {**callback_base, "outcome": "FAILED", "errorCode": "INTERVIEW_MODEL_OUTPUT_INVALID"}
```

Add `/internal/v4/interview-jobs` by reusing `require_internal_service_auth`, callback URL allow-list validation, `BackgroundTasks`, and `CallbackClient`. Extend `STOP_CODES` with `INTERVIEW_SESSION_GONE`, `INTERVIEW_CALLBACK_STALE`, and `IDEMPOTENCY_CONFLICT` so Python does not retry a terminal Java rejection.

- [ ] **Step 5: Run green Python boundary tests**

Run:

```powershell
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests/test_interview_contracts.py back/python/tests/test_interview_service.py back/python/tests/test_interview_internal_security.py back/python/tests/test_callback_client.py back/python/tests/test_openai_compatible.py -q
```

Expected: question/feedback schemas, redaction, callback hashing, token checks, allowed evidence enforcement, and retry stopping work; existing model-client safety tests remain green.

- [ ] **Step 6: Commit only Python task files**

Commit:

```text
feat(interview): add redacted v4 analysis worker
```

### Task 5: Add the Vue v4 Interview Preparation, Session, and Feedback Workflow

**Files:**

- Modify: `front/src/api/contracts.ts`
- Create: `front/src/api/interview.ts`
- Create: `front/src/api/interview.spec.ts`
- Modify: `front/src/router/index.ts`
- Modify: `front/src/router.spec.ts`
- Modify: `front/src/views/MatchResultView.vue`
- Modify: `front/src/views/MatchResultView.spec.ts`
- Create: `front/src/views/InterviewPrepareView.vue`
- Create: `front/src/views/InterviewPrepareView.spec.ts`
- Create: `front/src/views/InterviewSessionView.vue`
- Create: `front/src/views/InterviewSessionView.spec.ts`
- Create: `front/src/views/InterviewFeedbackView.vue`
- Create: `front/src/views/InterviewFeedbackView.spec.ts`
- Modify: `front/src/i18n/messages.ts`
- Modify: `front/src/i18n/messages.spec.ts`
- Modify: `front/src/style.css`
- Create: `front/e2e/interview-practice.spec.ts`

**Interfaces:**

- `interviewApi.createSession`, `getSession`, `getQuestions`, `regenerateQuestions`, `submitAnswer`, `getFeedback`, `confirmClaim`, and `deleteSession` call only `/api/v4`.
- Protected routes are `/interviews/new`, `/interviews/:sessionId`, and `/interviews/:sessionId/feedback`.
- `MatchResultView` hands only `matchTaskId` to the preparation page; Java resolves the resume/revision/profile/job-family context and the browser never receives a client-authoritative version or owner identifier.

- [ ] **Step 1: Write failing client and view tests**

Add typed v4 client tests that assert URLs, request bodies, and no `matchResultId` field:

```ts
await interviewApi.createSession({ matchTaskId: 'task001', idempotencyKey: 'interview-key-0001' })
expect(request).toHaveBeenCalledWith(expect.objectContaining({
  method: 'POST', url: '/api/v4/interview-sessions',
  data: expect.not.objectContaining({ matchResultId: expect.anything() }),
}))
```

In `MatchResultView.spec.ts`, assert the entry is only visible for a succeeded evidence report and that it routes to `/interviews/new?matchTaskId=task001`; the Java service, not the page, validates revision binding. In preparation tests, mock a session in `QUESTION_GENERATING`, advance fake timers, then verify the four question labels render after `getQuestions` resolves. Test model failure, session-gone, no-data, and page unmount/reroute with a late response.

In session tests, verify answer submission disables the button while pending, uses the session version and a generated `interview-answer-` idempotency key, and never displays raw backend error messages. In feedback tests, verify each of the five dimensions, cited evidence IDs, risk flags, confirmation buttons, and no “write to resume” control.

- [ ] **Step 2: Run focused frontend tests and verify red**

Run:

```powershell
pnpm --dir front test -- --run src/api/interview.spec.ts src/views/InterviewPrepareView.spec.ts src/views/InterviewSessionView.spec.ts src/views/InterviewFeedbackView.spec.ts src/views/MatchResultView.spec.ts src/router.spec.ts
```

Expected: tests fail because v4 types/client/routes/views/entry point do not exist.

- [ ] **Step 3: Add v4 client types and route boundaries**

Add exact TypeScript union types matching the v4 contract. Keep them in `front/src/api/contracts.ts`; put HTTP calls and response-shape guards in the new `front/src/api/interview.ts`. Route guards stay `requiresAuth: true`; no route relies on a client-provided owner ID.

Use `router.push({ path: '/interviews/new', query: { matchTaskId } })` only after checking `task.state === 'SUCCEEDED'` and `result` is loaded. `InterviewPrepareView` validates only the task ID prefix before creating a session and clears unsafe/invalid query values by navigating back to `/resumes`.

- [ ] **Step 4: Implement operational views without changing existing matching behavior**

`InterviewPrepareView` creates or resumes a session, polls only while `QUESTION_GENERATING`, shows data-purpose text before dispatch, lists question type/difficulty/requirement/evidence/reason, and offers regeneration only before an answer exists. `InterviewSessionView` shows one selected question, remaining question count, answer text area, safe length feedback, submit state, and end-session action. `InterviewFeedbackView` reads only Java feedback, renders all five dimensions and clear evidence/risk sections, lets the user confirm/reject individual claim IDs, and links to a fresh practice session after completion.

Add explicit Chinese error mapping for `INTERVIEW_MATCH_NOT_READY`, `INTERVIEW_SESSION_NOT_FOUND`, `INTERVIEW_SESSION_GONE`, `INTERVIEW_QUESTION_NOT_READY`, `INTERVIEW_ANSWER_CONFLICT`, `INTERVIEW_CALLBACK_STALE`, `INTERVIEW_MODEL_UNAVAILABLE`, and `INTERVIEW_MODEL_OUTPUT_INVALID`. Keep generic error messages safe and do not interpolate provider text.

In `style.css`, use the current workbench tokens, fixed control heights, responsive grid/flex constraints, and clear `:focus-visible` styles. Do not alter existing login, profile, upload, result, recovery, or delete selectors except the localized report entry action.

- [ ] **Step 5: Run green frontend unit/build/browser checks**

Run:

```powershell
pnpm --dir front test -- --run
pnpm --dir front run build
pnpm --dir front exec playwright test e2e/interview-practice.spec.ts
```

Expected: existing Vue tests and build remain green; the v4 browser test covers preparation loading, question render, one answer, feedback, confirmation, and end-session behavior against the controlled local API.

- [ ] **Step 6: Commit only frontend task files**

Commit:

```text
feat(front): add interview practice workflow
```

### Task 6: Integrate the Vertical Slice and Record Fresh Evidence

**Files:**

- Create: `tests/integration/assert_interview_practice_flow.py`
- Create: `tests/integration/fixtures/interview-v4-question-callback.json`
- Create: `tests/integration/fixtures/interview-v4-feedback-callback.json`
- Modify: `tests/integration/run_mvp_flow.ps1`
- Modify: `docs/verification/mvp-evidence.md`
- Modify: `README.md`
- Modify: `docs/项目文件说明.md`

**Interfaces:**

- Produces a no-secret offline assertion over v4 contract fixtures and a controlled live Java-Python scenario.
- Documents startup prerequisites, the v4 user flow, what data enters the external-model boundary, and the distinction between controlled fake-provider evidence and unverified real-provider behavior.

- [ ] **Step 1: Write failing offline integration assertions**

Create an assertion script that loads the v4 fixtures and verifies the full state sequence without credentials:

```python
assert flow == [
    "QUESTION_GENERATING",
    "WAITING_FOR_ANSWER",
    "ANSWER_ANALYZING",
    "FEEDBACK_READY",
    "DELETED",
]
assert deleted_callback_expected_code == "INTERVIEW_SESSION_GONE"
assert {question["questionType"] for question in questions} == {
    "BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"
}
```

Add checks that fixtures contain no `apiKey`, `callbackToken`, base64 document, phone number, email address, or personal name. Make the script fail before the v4 fixtures and expected state rules exist.

- [ ] **Step 2: Run the offline assertion and verify red**

Run:

```powershell
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_interview_practice_flow.py -q
```

Expected: failure because the v4 fixtures, state model, and scenario helper do not exist.

- [ ] **Step 3: Add controlled Java-Python live verification**

Extend `run_mvp_flow.ps1` with an optional `-Interview` switch. It must require the existing local Java, Python, MySQL, Redis, and fake OpenAI-compatible provider prerequisites; create a synthetic v4 session; wait for four questions; submit one answer; wait for feedback; issue session deletion; and post the stored callback once more to assert `INTERVIEW_SESSION_GONE`. It must redact tokens, API keys, answer text, and raw model response from output.

Use only a fake local provider in automated verification. A real provider test is optional user validation and must be separately labelled in the evidence document.

- [ ] **Step 4: Run the full project verification set**

Run:

```powershell
pnpm --dir contracts run lint
pnpm --dir contracts run validate
back\java\mvnw.cmd -q test
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q
pnpm --dir front test -- --run
pnpm --dir front run build
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py tests/integration/assert_interview_practice_flow.py -q
powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1 -Interview
```

Expected: contract checks, unit suites, frontend build, offline MVP/interview assertions, and the controlled fake-provider one-round integration all return success. If a pre-existing unrelated test fails, record its exact existing failure and do not classify it as an interview regression without a baseline comparison.

- [ ] **Step 5: Update evidence and usage documentation from actual results**

Record exact command outputs, test counts, environment constraints, fake-provider scope, external-model data minimization, child-content deletion scope, late-callback result, and any remaining unverified conditions in `docs/verification/mvp-evidence.md`. Update `README.md` and `docs/项目文件说明.md` with v4 route ownership and local launch/test commands. Do not claim real-provider accuracy, fairness, production readiness, or physical deletion from a demonstration.

- [ ] **Step 6: Commit only integration and documentation files**

Commit:

```text
test(interview): verify phase one workflow
```

## Plan Self-Review

**Spec coverage:** Task 1 freezes the new v4 boundary; Task 2 provides durable ownership, encrypted answer retention, deletion, and resume blocking; Task 3 supplies Java authorization, state transitions, callback idempotency, and errors; Task 4 implements redacted Python processing; Task 5 provides all documented operational UI states; Task 6 verifies the vertical slice and documents evidence. PDF, other role families, multi-round follow-up, profile write-back, export, fairness, vectors, and production deployment are deliberately omitted.

**Placeholder scan:** A search for unresolved placeholder markers is clean. Each task contains a concrete test target, expected red command, production boundary, green command, and selective commit instruction.

**Type consistency:** Public IDs use `resume`, `revision`, `task`, `profile`, and `session` prefixes; internal callbacks reuse `callback`; question/answer/feedback/confirmation records use their own prefixes. Public v4 calls use `matchTaskId`, never `matchResultId`. Internal work types and state/claim enums match every task's Python, Java, and Vue descriptions.

**Execution recommendation:** The implementation has contract-first dependencies and shared Java integration points. Execute it sequentially in an isolated worktree, beginning with Task 1; do not parallel-edit contracts, migrations, callback code, or frontend types from multiple lanes.
