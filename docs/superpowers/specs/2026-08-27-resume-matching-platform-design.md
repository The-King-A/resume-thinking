# Resume Matching Platform Architecture and MVP Design

**Status:** Approved architecture, ready for implementation planning

**Product source:** `ai-resume-job-matching-project.md.docx`

**Scope source:** This document records the decisions approved during design. It is the architecture source of truth until a versioned OpenAPI contract and shared fixtures are created.

## 1. Product Outcome

Build a trustworthy job-assistance platform for new graduates. The first releasable vertical slice serves only the **Java backend development** role family. It lets a registered user configure an OpenAI-compatible model profile, upload a TXT or DOCX resume, paste a Java-backend job description, receive an evidence-backed match result, and manage visibility through soft deletion and archival recovery.

The product is not a generic resume generator. A match must connect each job requirement to cited resume evidence. Unsupported facts, numbers, outcomes, or experience must never appear in a generated suggestion or export.

## 2. Approved Scope

### First vertical slice

- Public registration with a selectable `USER` or `ADMIN` role.
- JWT login and role/ownership enforcement on every protected route.
- Per-user OpenAI-compatible API profiles: endpoint URL, model name, encrypted API key, connection test, selection, and a manual model-name fallback.
- TXT and DOCX resume upload; PDF is an explicit unsupported-file outcome in this slice.
- Java-backend job descriptions entered as text and parsed into structured requirements.
- Hybrid, explainable matching with fixed weights: skills 0.40, projects 0.25, work content 0.15, education/experience 0.10, and soft skills 0.10.
- Requirement-level evidence view, including job source text, resume excerpt/location, match status, component score, evidence strength, gap, and suggestion state.
- GitHub-style soft-delete confirmation: the caller enters the exact phrase `确认删除简历` before the action is accepted.
- Role-aware soft-delete recovery and cache-expiry archival recovery.

### Explicit non-goals for the first slice

- PDF parsing, other job families, Nginx, full Docker Compose, Redis clustering, vector persistence, interview practice, resume export, and formal fairness experiments.
- A user-facing hard-delete endpoint. Physical deletion is an operator-only direct MySQL operation and is irreversible.
- A claim that unrestricted administrator self-registration is secure for production.

## 3. Local Runtime Architecture

The first slice uses the approved hybrid development topology:

```text
Vue 3 + TypeScript (native pnpm dev server, port 5173)
              |
              v
Spring Boot 3 / JDK 21 (native, port 8080) <-> MySQL 8.4 (local, port 3306)
              |                                      |
              |                                      v
              +----------------------------------> Redis (one Docker container, port 6379)
              |
              v
FastAPI / Python 3.11 (native, port 8000) -> user-selected OpenAI-compatible API
```

Spring Boot is the sole public API, authorization, orchestration, task-state, and persistence authority. FastAPI has no public user login, no user authorization decision, and no direct MySQL or Redis write path. It performs extraction, redaction, parsing, matching, guarded model calls, and returns structured work results only to Java.

The code compiles and runs with JDK 21. Maven Wrapper is used because no global Maven is installed. Python execution is pinned to the available Python 3.11 installation, not the machine-default Python 3.13/3.14 interpreters. Docker is initially needed only for Redis; full Compose is a later deployment phase.

## 4. User, Role, and Model Rules

### Roles

- `USER` registers through the public registration flow, owns its resume records, and can only read, soft-delete, archive-recover, or user-recover its own eligible records.
- `ADMIN` can be selected directly during registration by product decision. It can inspect and recover records across users and can initiate an administrator soft-delete.

All public role checks remain enforced server-side. The deliberate ability for any registrant to choose `ADMIN` means the system has a known deployment-security risk: a registrant can obtain global resume access. This must be recorded in the UI privacy disclosure, test report, and final project documentation. It is not silently treated as a production-safe permission model.

### Per-user model configuration

Each logged-in user owns model profiles with a display name, endpoint URL, model name, encrypted API key, optional connection-test metadata, and selected/default marker. The key is accepted by Java over the authenticated API, encrypted at rest, never returned in a read response, never logged, and decrypted only in memory when a task needs it.

The UI offers known-compatible presets and a custom OpenAI-compatible profile. It attempts a model-list query when the provider supports it, with manual model-name entry as a fallback. A connection test returns only safe status and diagnostics. Custom endpoints must be validated before a server-side request; production mode accepts HTTPS endpoints and rejects private/reserved network targets unless a specifically configured local-development exception exists.

Java passes a narrow, short-lived internal task configuration to Python. Python redacts resume material before any external-model request and must not persist API keys or raw resume text in logs, errors, queues, or analytics.

## 5. Data Model and Lifecycle

MySQL is the durable source of truth. Resume content and sensitive parsed fields are encrypted at rest. Redis is a short-lived cache, progress, and idempotency layer; its loss cannot change the authoritative MySQL state.

### Principal records

| Record | Purpose |
| --- | --- |
| `users` | Account identity, password hash, selected role, timestamps. |
| `llm_profiles` | User-owned endpoint/model metadata and encrypted API key. |
| `resumes` | Owner, title, source type, encrypted raw content, parser version, display lifecycle, deletion status, version, and retention timestamps. |
| `resume_evidence` | Parsed fields, stable source offsets/excerpts, confidence, and manual-correction provenance. |
| `job_descriptions` / `job_requirements` | Java-backend posting text and normalized mandatory/preferred requirements. |
| `analysis_tasks` / `analysis_results` | Task state, attempt, callback receipt, matching result, and evidence links. |
| `resume_recovery_audit` | Actor, action, prior/new lifecycle state, timestamp, and correlation ID. |

### Resume state fields

`resumes.status` honors the user decision:

- `0`: not soft-deleted.
- `1`: soft deletion completed successfully.

`resumes.visibility_state` is a separate enum so cache archival is not confused with deletion:

- `ACTIVE`
- `USER_SOFT_DELETED`
- `ADMIN_SOFT_DELETED`
- `USER_CACHE_ARCHIVED`
- `ADMIN_CACHE_ARCHIVED`

The record also carries `owner_id`, `soft_deleted_by`, `soft_deleted_at`, `visible_until`, `archived_at`, `restored_at`, and optimistic-lock `version` fields.

On creation or successful restore, `visible_until` is fixed rather than extended by reads: seven days for a record created by a `USER`, and thirty days for one created by an `ADMIN`. A Java scheduler uses MySQL's durable timestamp as the authority, transitions an eligible active record to the matching cache-archived state, and deletes its Redis keys. A subsequent ordinary page visit does not reload an archived record from MySQL; an explicit recovery does.

### Deletion and recovery rules

- A normal deletion requires authentication, ownership/role authorization, the typed confirmation phrase, and an expected record version. Java sets `status=1`, moves the record to a soft-deleted state, clears Redis keys, writes an audit record, and prevents late analysis callbacks from restoring visibility.
- A `USER` can recover only its own `USER_SOFT_DELETED` and its own `USER_CACHE_ARCHIVED` records.
- An `ADMIN` can inspect and recover eligible soft-deleted or archived records for every user. An administrator-soft-deleted record is excluded from the owner's normal and recovery views; only an administrator can restore it.
- The recovery search is an indexed, owner/role-scoped query, not an unbounded in-memory table scan.
- A direct MySQL physical delete is outside application functionality. It removes recoverability and must be performed by an authorized operator according to a separate operating procedure.

The user-facing privacy disclosure must say plainly that Redis expiry and soft deletion do not physically remove MySQL data. MySQL resume data persists until a direct operator deletion.

## 6. Processing and Matching Flow

1. A user registers/logs in, selects a permitted personal model profile, and uploads a TXT/DOCX resume with a Java-backend job description.
2. Java validates file type/size, ownership, task idempotency, and model-profile ownership; it creates a queued task and records a lifecycle version.
3. Java sends a scoped internal work request to Python. No undocumented host path is shared between services.
4. Python extracts text, detects/redacts sensitive fields, identifies resume evidence and job requirements, and calls the selected OpenAI-compatible API only with redacted material.
5. Python validates the structured model response. It returns requirement references, evidence identifiers, match state, component scores, strength, gap, and guarded suggestions to Java.
6. Java validates evidence references against the current resume version and writes the result only when the task and resume are still eligible. Python never writes user-facing data directly.
7. Vue reads the result solely through Java and renders evidence, task state, errors, recovery controls, and deletion controls appropriate to the caller's role.

The score is the documented weighted composite. A requirement state is exactly one of `SATISFIED`, `PARTIALLY_SATISFIED`, `RELATED_BUT_EVIDENCE_INSUFFICIENT`, or `UNMET`. The last two never become a positive match. Suggestions are classified as `SUPPORTED_FACT`, `WORDING_ONLY_REWRITE`, `NEEDS_USER_CONFIRMATION`, or `RISKY_OR_UNSUPPORTED`; only the first two may be shown as safe candidates, and neither can invent facts.

## 7. Contract, State, and Failure Rules

Before implementation fanout, a single contract guardian creates these authoritative artifacts:

- `contracts/openapi/v1/openapi.yaml` for public Java APIs.
- `contracts/internal/v1/analysis-callback.schema.json` for Java/Python handoff and callback payloads.
- `contracts/fixtures/v1/` for valid, invalid, timeout, duplicate-message, stale-callback, archival, user-delete, admin-delete, authorization, and legacy fixtures where a consumer exists.

Every response uses an error envelope containing a stable code, safe user message, correlation ID, retryability, and non-sensitive validation detail. Tasks use guarded transitions equivalent to `QUEUED -> PROCESSING -> SUCCEEDED | FAILED | TIMED_OUT`; an archival or soft-delete transition blocks result persistence. A callback carries task ID, attempt, callback ID, payload hash, and service credential. Identical retries are accepted idempotently, stale or deleted work is rejected without recreating data, and malformed model output is a recoverable failure rather than a fabricated result.

## 8. UX Commitments

- Registration exposes role selection as approved.
- Model configuration uses presets, custom endpoints, test connection, selectable models, manual fallback, safe error messages, and no secret re-display.
- The evidence result view prioritizes scanning requirements, cited resume proof, gaps, confidence, and failures.
- Delete is a deliberate modal flow requiring the exact confirmation phrase; it does not rely on a client-only check.
- User recovery lists only the user's eligible records. Administrator recovery lists eligible records across owners with clear owner context.
- Archived, deleted, pending, partial/low-confidence, model-failure, and no-data states are explicit UI states.

## 9. Verification Gates

The vertical slice is complete only after fresh evidence demonstrates:

- shared contract validation against all required fixtures;
- Java unit/integration tests for authorization, lifecycle transitions, encryption boundaries, task idempotency, and callback version checks;
- Python tests for redaction, TXT/DOCX parsing, structured-output validation, fixed scoring, evidence integrity, and model-failure handling;
- real Java-to-Python integration for one controlled resume/job input;
- authorization tests for guessed IDs, user-owned recovery, cross-user denial, administrator recovery, and administrator soft-delete hiding an owner's record;
- Redis expiry/archive and explicit restore tests for the 7-day and 30-day owner-role policies;
- delete-during-processing, duplicate callback, stale attempt, and late callback tests;
- scans showing API keys, raw resume body, phone numbers, emails, and names do not appear in logs or error payloads;
- Vue evidence for all operational states, including delete confirmation and recovery dialogs.

## 10. Delivery Sequence

1. Initialize Git and record the pre-implementation baseline.
2. Create and review the versioned contracts, state table, error codes, fixtures, retention inventory, and slice brief in one contract-guardian lane.
3. After contract freeze, implement the Java, Python, and Vue lanes with non-overlapping ownership.
4. Integrate the real handoff, run the shared evidence suite, and obtain independent review focused on security, privacy, concurrency, and requirements.
5. Add PDF, other job families, full Compose/Nginx, interview practice, and evaluation work through separate approved slices.

## 11. Residual Risks and Honest Claims

- Open administrator self-registration is an intentional product decision but not a secure production-role issuance strategy.
- Long-lived MySQL resume storage conflicts with the product document's usual minimum-retention intent; the application discloses this explicitly and does not call cache expiry a physical deletion.
- A custom model endpoint may fail, change behavior, retain data under provider policy, or return invalid structured output. The system exposes such failure rather than claiming a successful analysis.
- The first slice proves the specified local workflow only. It does not prove production readiness, fairness, model accuracy, or support for formats/features outside the approved scope.
