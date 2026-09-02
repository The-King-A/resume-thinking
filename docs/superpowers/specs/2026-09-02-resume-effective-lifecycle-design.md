# Effective Resume Lifecycle And Re-Match Design

## Outcome

The effective-resume list contains only resumes whose currently published
revision has completed a validated evidence-backed match. Uploading a document
does not by itself make it effective. A failed, timed-out, superseded, or
unpublished candidate remains outside that list.

An effective resume can be re-matched with a new job description and model
profile, or edited and re-matched with a new title and/or TXT/DOCX file. A
candidate edit never overwrites the currently effective revision. The old
revision and its report remain available until the replacement match succeeds.
Each effective-resume row has Chinese actions for `重新匹配`, `查看匹配报告`,
and `删除`. The report action opens the newest successfully published report.
The first release retains prior reports but does not add a report-history
picker.

The only enabled job family remains `JAVA_BACKEND`.

## Sources, Scope, And Contract Version

The product source of truth is
`E:/resume_thinking/ai-resume-job-matching-project.md.docx`. It requires
evidence-backed explanations, fact-constrained behavior, private data
handling, Java as the only public authorization and persistence boundary, and
Python as a processing-only service.

The existing public contract is `contracts/openapi/v2/openapi.yaml`. Its
`POST /api/v2/resumes` behavior currently publishes an upload immediately,
which conflicts with this slice. It remains a documented legacy endpoint so
existing callers are not silently broken. The strict lifecycle is introduced
in `contracts/openapi/v3/openapi.yaml` and `contracts/fixtures/v3/`; the
frontend moves its upload, effective-list, re-match, and report-discovery flow
to v3. Existing authentication, LLM-profile, recovery, and direct task-result
endpoints remain v2 until separately versioned.

This is deliberately a v3 lifecycle contract rather than a behavioral change
to v2. New data created by the v3 flow is governed by the effective-resume
rules; v2 remains only a compatibility surface and is not used by the UI.

Non-goals for this slice are new job families, automatic title renaming, a
full report-history selector, resume text editing in the browser, PDF support,
and any change to external-model provider configuration or API-key handling.

## Durable Model

`resumes` becomes the logical, owner-scoped resume record. It retains the
existing lifecycle fields and gains nullable `effective_revision_id` and
`pending_revision_id` pointers. Its visible title and source type are derived
from the effective revision; a pending-only resume has no effective list item
or Redis view.

`resume_revisions` is a new immutable table. A revision contains its parent
resume ID, monotonic revision number, title, application-normalized title key,
TXT/DOCX source type, parser version, encrypted raw document bytes and nonce,
creation metadata, and a state of `PENDING`, `EFFECTIVE`, `FAILED`, or
`SUPERSEDED`. It has `UNIQUE(resume_id, revision_no)`. Raw document bytes
continue to be encrypted at rest and are decrypted only in the existing Java
processing boundary.

`analysis_tasks` and `analysis_results` gain a non-null `revision_id` foreign
key. They keep their logical `resume_id` for owner checks and compatibility.
Evidence and results therefore remain bound to the original immutable document
revision even after a later edit. A task stores the submitted job description
and selected profile ID as it does today; no API key or provider response is
stored in a task or result.

The task exposes a publication state in addition to its processing state:

| Publication state | Meaning |
| --- | --- |
| `NOT_REQUESTED` | A re-match of the already effective revision; it only creates a new report. |
| `PENDING` | Initial upload or edited candidate awaiting a successful match. |
| `PUBLISHED` | The validated successful result atomically became the effective revision. |
| `REJECTED_DUPLICATE_TITLE` | Evidence matching succeeded, but publication was rejected by the durable title constraint. |

The existing task states remain `QUEUED`, `PROCESSING`, `SUCCEEDED`, `FAILED`,
`TIMED_OUT`, and `BLOCKED`. A duplicate-title publication rejection retains the
valid report and sets `SUCCEEDED` plus `REJECTED_DUPLICATE_TITLE`; it does not
claim that the candidate is an effective resume.

## Lifecycle And Concurrency

### Initial submission

1. The authenticated user submits one TXT/DOCX file, optional title, selected
   saved LLM profile, Java-backend job description, and an idempotency key.
2. Java authorizes the user, encrypts the file, creates a logical resume with
   a `PENDING` first revision, and creates a task for that revision in one
   transaction.
3. Java dispatches the existing redacted Java-to-Python analysis after commit.
   Python still has no user authorization or durable business-write path.
4. A callback succeeds only after Java validates the response schema, evidence
   identifiers, locations, and excerpts against the immutable revision.
5. In the callback transaction Java stores the result, marks the task
   successful, sets `effective_revision_id`, promotes the revision, and writes
   the derived Redis view after commit. Failed and timed-out tasks do none of
   those publication actions.

The result page remains the owner-scoped place to retry or delete an initial
candidate that did not become effective. It is never shown in `/resumes`.

### Re-match and edit

`重新匹配` opens an owner-scoped editing page prefilled with the effective
title, most recent job description, and selected model profile. The page
allows a title change and an optional replacement TXT/DOCX file.

- If neither title nor file changes, the new task uses the current effective
  revision. A successful result updates `latestSuccessfulTaskId`; a failed
  task leaves the existing effective revision and report unchanged.
- If title or file changes, Java creates a pending replacement revision. The
  existing effective revision remains visible and its report remains readable
  while matching is in progress.
- Submitting another edit supersedes the previous pending revision under the
  logical-resume lock and blocks its queued or processing tasks. A callback
  must match the expected revision and active candidate task before it can
  publish.
- A successful replacement atomically switches the effective pointer. A
  failure, timeout, or superseded callback never alters the old effective
  pointer.

The callback, delete, archive, restore, and candidate-supersede paths acquire
the logical resume lock before the task lock. This extends the existing
late-callback race protection and prevents a deleted or superseded revision
from recreating visible data.

## Effective Title Constraint And Recovery

Titles are unique per owner only, after Unicode-safe application normalization
of surrounding whitespace and case. The database owns the guarantee:
`resumes` stores a nullable `effective_title_key` that is non-null only when
the record has an effective revision, is `ACTIVE`, and has `status = 0`. A
unique index on `(owner_id, effective_title_key)` permits multiple `NULL`
values and therefore releases a title on soft deletion or cache archival.

Publication and recovery check the database constraint inside their write
transaction and map a duplicate-key result to `409 DUPLICATE_RESOURCE` with
the field-level code `DUPLICATE_RESUME_TITLE`. A preliminary lookup may give a
friendlier early error but is never the correctness mechanism.

Consequences are intentional:

- An effective resume cannot share a title with another effective resume owned
  by the same user.
- Soft deleting an effective resume releases its title, so a new effective
  resume can later use it.
- Restoring a soft-deleted or expired record whose title has been reused is
  rejected without changing either resume. There is no automatic title rename:
  the user must first soft-delete the competing effective resume or re-match
  that competing resume under a different title before retrying recovery.
- Administrators retain their existing cross-owner lifecycle authority. They
  do not bypass same-owner title uniqueness, and normal users can only read,
  retry, delete, recover, or report on their own logical resumes.

Redis remains a derived cache only. It never makes a publication or title
uniqueness decision. Pending candidates are never cached. Publication,
supersession, soft deletion, cache archival, recovery, and title changes use
the existing after-commit cache update/eviction pattern.

## API And UI Contract

The frozen v3 contract contains these protected lifecycle operations:

- `POST /api/v3/match-submissions`: initial multipart upload plus match task.
- `POST /api/v3/resumes/{resumeId}/match-submissions`: re-match or edit and
  re-match; multipart file is optional, and the request includes the expected
  effective revision identity and idempotency key.
- `GET /api/v3/resumes`: only effective, active, unexpired resumes visible to
  the current owner; administrators receive the existing permitted scope.
- `GET /api/v3/resumes/{resumeId}/match-context`: owner-scoped effective or
  pending candidate metadata used to prefill retry/re-match controls.

The v3 `Resume` response includes `effectiveRevisionId`,
`latestSuccessfulTaskId`, and publication metadata. The latest report action
uses that task ID with the existing owner-protected result retrieval flow. No
raw resume content, encrypted content, nonce, API key, callback token, or
provider diagnostic is included in a browser response.

The frontend adds a Chinese re-match route and form. It preserves the existing
model-profile selector, job-family limit, authentication redirect behavior,
and top notification system. It displays success, validation, duplicate-title,
provider/task, and stale-edit outcomes as safe Chinese notifications. A report
button is absent when no latest successful task exists.

## Migration And Existing Data

A new Flyway migration follows V9 and never edits checksum-locked earlier
migrations. During the documented write-quiescent migration it:

1. creates revisions and revision foreign keys;
2. creates revision records for existing resume documents;
3. backfills effective pointers only where a successful task, persisted result,
   and validated evidence relationship exist;
4. classifies unmatched active rows as non-effective rather than guessing;
5. audits duplicate owner/title candidates before creating the unique index;
   where duplicates exist, it retains only the newest verifiably matched row as
   effective and keeps the others without data deletion; and
6. updates the empty-database SQL snapshot and readable maintenance view.

The migration does not delete MySQL records. It preserves the existing
seven-day user and thirty-day administrator cache-retention behavior.

## Fixtures, Tests, And Acceptance Evidence

The authoritative fixture directory is `contracts/fixtures/v3/`. It contains
valid and invalid initial submissions, valid and invalid re-match submissions,
successful and failed callbacks, a timeout, a duplicate callback replay, a
superseded-candidate callback, a duplicate-title publication, soft-delete
title reuse, restore conflict, and legacy-v2 compatibility examples.

Tests must fail first for:

- initial upload being invisible before a valid evidence callback and visible
  only after publication;
- failed initial matching not creating an effective list item or Redis view;
- a document/title edit that fails preserving the old effective revision and
  old report;
- an unchanged-document re-match updating the latest report only on success;
- late callbacks after delete, archive, or supersession being safely blocked;
- owner/admin report authorization and pending-candidate retry authorization;
- duplicate title races at publication, soft-delete title reuse, and restore
  conflicts; and
- Chinese list actions, re-match form behavior, report navigation, and top
  success/failure notifications.

Completion requires frozen-contract validation, focused Java lifecycle and
HTTP tests, Python contract compatibility tests, focused Vitest and Playwright
coverage, the full Java and frontend suites, the frontend production build,
and a browser walk-through of upload, successful publication, re-match,
report viewing, failed replacement, duplicate title, and soft deletion.
