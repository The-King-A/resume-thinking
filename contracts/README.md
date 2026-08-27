# Resume Matching Contracts v1

This directory is the interface authority for the first Java-backend-job
matching slice. The product requirements remain in
`ai-resume-job-matching-project.md.docx`; the approved local design is in
`docs/superpowers/specs/2026-08-27-resume-matching-platform-design.md`.
When a field, path, enum, or wire behavior is defined here, Java, Python, and
Vue must use it rather than independently interpreting the product document.

## Authoritative Artifacts

| Artifact | Authority |
| --- | --- |
| `openapi/v1/openapi.yaml` | Public Java API, including user-facing requests, responses, errors, and authorization expectations. |
| `internal/v1/analysis-job.schema.json` | Java-to-Python short-lived analysis dispatch. |
| `internal/v1/analysis-callback.schema.json` | Python-to-Java callback. Java validates and persists it. |
| `fixtures/v1/` | Shared valid, invalid, duplicate, stale, deletion, and archive examples added by Task 2. |

The public API and internal schemas are versioned independently but remain at
`v1` for this slice. Additive optional fields are compatible changes. Removing
or changing a field's type, meaning, requiredness, enum value semantics, or
authorization behavior requires a new versioned artifact and representative
compatibility fixtures. No legacy consumer exists before v1, so no legacy
fixture is required yet.

## Ownership and Trust Boundaries

Spring Boot is the sole public business endpoint, authorization decision maker,
task-state owner, and MySQL/Redis writer. Every public route except register and
login requires a bearer JWT. For a protected resource route, a foreign ID and
an unknown ID both return the same `RESOURCE_NOT_FOUND` error envelope; the
application must not disclose whether another actor owns the resource.

FastAPI has no login, user authorization, MySQL/Redis credential, direct
database write, or public result write path. Java sends it a narrow internal
job with an ephemeral callback token. Python redacts sensitive values before
calling any user-selected external provider, validates provider output, and
posts a schema-conforming callback to Java. It must never log a raw resume,
provider API key, callback token, HTTP authorization header, or raw provider
response.

### Provider Endpoint Safety

Before a connection test or analysis dispatch, Java accepts only an HTTPS
endpoint. The sole local-development exception is an explicitly configured
`http://127.0.0.1` endpoint used by controlled tests. Java resolves every host
immediately before the request and rejects addresses that are private,
loopback, link-local, multicast, and reserved. It rejects redirects to a new
host and re-runs this check for every redirect or newly resolved address.
Python repeats this validation immediately before its provider request as
defense in depth. A rejected endpoint makes no provider request and returns
`MODEL_ENDPOINT_REJECTED` with a sanitized error. This is a runtime network
policy; a JSON Schema cannot safely infer DNS resolution or address class.

The job and callback schemas reject unexpected top-level fields. They
explicitly prohibit identity and storage-authority fields such as `ownerId`,
`userId`, `databaseCredentials`, `persistenceCommand`, and host filesystem
paths. The internal document is a controlled payload, never an undocumented
host path shared between services.

The v1 analysis-job JSON shape intentionally has no service-credential field.
Transport authentication is supplied out of band with the
`X-Internal-Service-Token` HTTP header, configured through the non-committed
`PYTHON_INTERNAL_SERVICE_TOKEN` environment variable. The Python worker fails
closed when the variable is absent. Before posting a callback, Python accepts
only loopback targets or an exact configured Java callback base
(`JAVA_CALLBACK_BASE_URL`, with optional comma-separated
`PYTHON_CALLBACK_ALLOWED_BASE_URLS` additions).

## Public Authorization Rules

- Registration intentionally permits `USER` and `ADMIN` role selection for the
  approved first slice. This is a known deployment-security risk, not a reason
  to omit server-side role checks.
- A `USER` reads, deletes, and restores only its own eligible resumes.
- An `ADMIN` may inspect and recover eligible records across owners and may
  issue an administrator soft delete.
- An administrator soft deletion is not visible in an owner's active list or
  owner recovery list. Only an administrator can restore it.
- API key material is accepted only in write requests for an LLM profile,
  encrypted by Java at rest, and omitted from every response. A read response
  exposes only `hasApiKey` and safe connection metadata.
- The delete request requires the exact `confirmationText` value
  `确认删除简历` and a current `expectedVersion`. The server, not the browser,
  checks both values.

## Resume Visibility Lifecycle

`resumes.status` is a soft-delete flag only:

| `status` | Meaning |
| --- | --- |
| `0` | Resume is not soft-deleted. |
| `1` | Soft deletion completed successfully. |

`visibilityState` records whether a record is presently displayable:

| From | Event | To | Actor / rule |
| --- | --- | --- | --- |
| `ACTIVE` | owner soft delete | `USER_SOFT_DELETED` | Owner `USER` or eligible owner action. |
| `ACTIVE` | administrator soft delete | `ADMIN_SOFT_DELETED` | `ADMIN`; owner cannot recover it. |
| `ACTIVE` | owner-created cache deadline | `USER_CACHE_ARCHIVED` | Java scheduler after seven days. |
| `ACTIVE` | admin-created cache deadline | `ADMIN_CACHE_ARCHIVED` | Java scheduler after thirty days. |
| `USER_SOFT_DELETED` | owner restore | `ACTIVE` | Same owner, current version. |
| `USER_CACHE_ARCHIVED` | owner restore | `ACTIVE` | Same owner, current version. |
| any eligible soft-deleted/archive state | administrator restore | `ACTIVE` | `ADMIN`, current version. |

The scheduler uses durable MySQL `visible_until`, transitions matching active
rows, deletes related Redis keys, and records an audit event. It does not
physically delete MySQL records. Normal active list/read routes do not reload
archived data from MySQL. Recovery is an explicit, indexed, owner- or
administrator-scoped query. Physical database deletion has no public API and
is an operator-only direct MySQL procedure.

`USER_CACHE_ARCHIVED` and `ADMIN_CACHE_ARCHIVED` are cache-expiry archive
states, not soft-deleted states. A record in either state has no Redis view,
keeps `status = 0`, and cannot be soft-deleted again through any page/API
delete route. It must first be explicitly restored by its owner (`USER`) or an
administrator (`ADMIN`) according to the authorization rules above; only after
that restore returns it to `ACTIVE` may a subsequent delete be requested.

Every successful restore recomputes `visible_until` from the restore timestamp
and the original creator role: seven days for a `USER` creator and thirty days
for an `ADMIN` creator. A restored record therefore cannot immediately
re-archive because it retained an old deadline.

## Task Lifecycle and Callback Rules

The durable task state machine is:

```text
QUEUED -> PROCESSING -> SUCCEEDED | FAILED | TIMED_OUT
active task -> BLOCKED when its resume is soft-deleted or cache-archived
```

Only Java performs these transitions. A callback can be accepted only when all
of the following match the active durable task:

1. `taskId`, `attempt`, and callback-token hash;
2. current resume version and `ACTIVE` visibility state;
3. a fresh `callbackId`, or the same callback ID and the same `payloadHash`;
4. every returned `evidenceId` belongs to `allowedEvidence` supplied in the
   original analysis job, with valid offsets for the current resume version.

`payloadHash` is the lowercase SHA-256 digest of the UTF-8 bytes produced by
[RFC 8785 JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785)
for the complete callback object after omitting its `payloadHash` member. The
hash does not normalize text beyond RFC 8785, does not add whitespace, and
uses RFC 8785 number serialization. Java and Python use this exact algorithm
before either creates or compares a callback receipt.

Repeated transport delivery with the same callback ID and payload hash returns
an idempotent accepted replay. Reusing a callback ID with a changed payload
returns `IDEMPOTENCY_CONFLICT`. A callback for a deleted or archived resume
returns `TASK_GONE`; a callback for an earlier attempt returns
`STALE_ATTEMPT`. These outcomes stop Python retrying and prevent a late result
from restoring a hidden resume or recreating a result.

Python retries only transport failures and Java 5xx responses, preserving its
original `callbackId` and `payloadHash`. It stops for a successful response,
`TASK_GONE`, `STALE_ATTEMPT`, or `IDEMPOTENCY_CONFLICT`. Fixed connect/read
timeouts and malformed structured model output become observable task failures,
not fabricated match results.

## Match Semantics

The fixed score is:

```text
0.40 skills + 0.25 project experience + 0.15 work content
+ 0.10 education/experience + 0.10 soft skills
```

Every requirement result has a job requirement text and type, cited resume
evidence/location, match state/type, component score, evidence strength, gap,
and suggestion state. `RELATED_BUT_EVIDENCE_INSUFFICIENT` and `UNMET` are not
positive matches. Suggestions are classified as `SUPPORTED_FACT`,
`WORDING_ONLY_REWRITE`, `NEEDS_USER_CONFIRMATION`, or
`RISKY_OR_UNSUPPORTED`; unconfirmed or unsupported facts do not become applied
resume content.

## Error Envelope and Stable Codes

Every error response follows `ApiError`:

```json
{
  "code": "RESUME_ARCHIVED",
  "message": "The resume is archived and must be restored first.",
  "correlationId": "00000000-0000-4000-8000-000000000001",
  "retryable": false,
  "details": [{"field": "expectedVersion", "reason": "must be current"}]
}
```

`details` is optional and may contain only safe validation information, never
resume text, provider responses, secrets, or another owner's identity.

| Code | Meaning | Retryable |
| --- | --- | --- |
| `VALIDATION_ERROR` | Request shape or safe field validation failed. | No |
| `AUTHENTICATION_REQUIRED` | JWT missing, expired, or invalid. | No |
| `FORBIDDEN` | Authenticated actor lacks an administrator capability. | No |
| `DUPLICATE_RESOURCE` | Username, email, or other unique resource already exists. | No |
| `INVALID_CONFIRMATION` | Delete phrase does not match exactly. | No |
| `VERSION_CONFLICT` | Optimistic-lock version is stale. | No; refresh first |
| `RESOURCE_NOT_FOUND` | Resource is unknown or not visible to the actor. | No |
| `RESUME_ARCHIVED` | Resume needs explicit recovery before an active-only operation. | No |
| `RESUME_SOFT_DELETED` | Resume is soft-deleted and cannot accept the requested operation. | No |
| `TASK_GONE` | Task is deleted, archived, or otherwise blocked. | No |
| `STALE_ATTEMPT` | Callback does not match the task's current attempt. | No |
| `IDEMPOTENCY_CONFLICT` | Same idempotency/callback key carries different data. | No |
| `MODEL_UNAVAILABLE` | Provider/network timeout or availability failure. | Yes |
| `MODEL_OUTPUT_INVALID` | Provider returned invalid or unresolvable structured output. | No |
| `MODEL_ENDPOINT_REJECTED` | Configured endpoint violates endpoint safety policy. | No |
| `UNSUPPORTED_FILE` | File is not TXT or DOCX; PDF is explicitly unsupported in v1. | No |
| `PAYLOAD_TOO_LARGE` | Upload exceeded configured policy. | No |
| `TASK_NOT_READY` | A task has not produced a result yet. | Yes after polling delay |

## Validation and Fixtures

Run the current contract check with:

```powershell
pnpm --dir contracts run lint
```

Task 2 adds a validator and shared fixtures for valid registration/profile/task
requests, a valid internal analysis job, invalid matching input,
valid/duplicate/stale/deleted callbacks, both archive policies, and the error
envelope. Java and Python tests consume those same fixtures; they do not
hand-maintain competing examples.
