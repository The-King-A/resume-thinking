# MVP Verification Evidence

Date: 2026-08-27

This report records the evidence for the first resume-to-job matching slice. It
separates implemented behavior, deterministic/offline checks, unverified live
integration, and residual risk. It does not claim production readiness,
real-provider quality, fairness, or physical deletion of MySQL data.

## Contract and Scope

- `contracts/openapi/v1/openapi.yaml` and the JSON schemas under
  `contracts/internal/v1/` are the versioned interface authority.
- The released role family is Java backend development only. The role field is
  retained so additional role families can be added later.
- Java/Spring Boot is the only public business, authorization, persistence, and
  task-state authority. Python/FastAPI performs extraction, redaction, guarded
  model calls, and callback delivery. Vue is the operational client.
- The local target runtime is JDK 21, Maven/Spring Boot 3, Python 3.11, Vue 3,
  MySQL 8.4, and Redis 7.

## Implemented Behavior

### Identity and model profiles

- Registration supports `USER` and `ADMIN`; administrator creation is
  intentionally unrestricted for this phase.
- Each account owns its OpenAI-compatible endpoint and model profile. The
  provider key is encrypted at rest, used only in Java memory for dispatch, and
  never returned by an API response or frontend form. Updating other profile
  fields preserves an existing key when no replacement is supplied.
- Endpoint validation rejects unsafe targets except for an explicit local
  development allowance. Connection tests return status only.

### Resume and matching flow

- TXT and DOCX resumes can be uploaded and retained in encrypted MySQL
  storage. Java writes a derived Redis view with a retention TTL and evicts it
  on deletion or archival; public list/read authorization remains backed by the
  durable Java state. PDF is explicitly rejected in the MVP.
- A Java backend job description creates an asynchronous task. Python receives
  redacted material and an allow-listed evidence set, then returns structured
  scores, requirement matches, source ranges, and suggestion states.
- Evidence references are checked against the task's allowed source ranges;
  unsupported or unconfirmed claims remain separate from generated resume
  content. Late, stale, duplicate, or unauthorized callbacks cannot recreate a
  deleted result.
- The result page retries a transient `TASK_NOT_READY` response instead of
  requiring a manual refresh.

### Visibility, deletion, and recovery

- A `USER` resume is visible for seven days and an `ADMIN`-created resume for
  thirty days. Expiry archives page visibility and removes Redis keys; it does
  not delete the MySQL row.
- Manual deletion requires authentication, ownership/administrator scope, the
  typed confirmation phrase, and the expected version. It removes Redis data
  and records a soft-delete status in MySQL. There is no public hard-delete
  endpoint.
- User recovery is owner-scoped. Administrator recovery covers all eligible
  records and an administrator soft-delete hides a record from the owner until
  administrator restoration. Physical MySQL deletion is an operator-only,
  manual database procedure.
- The internal Java-to-Python route requires the configured
  `PYTHON_INTERNAL_SERVICE_TOKEN`; missing or placeholder values fail closed.

## Offline and Static Evidence

The following checks were run against the integrated worktree
(`feature/resume-matching-mvp`):

| Check | Result | Evidence boundary |
| --- | --- | --- |
| `pnpm --dir contracts run lint` | PASS | OpenAPI v1 syntax and lint rules |
| `pnpm --dir contracts run validate` | PASS | 12 valid fixtures plus the expected invalid match fixture |
| `E:\maven\...\mvn.cmd test` in `back/java` | PASS, 59 tests | Java unit and HTTP-boundary tests; no real DB/Redis |
| Python 3.11 `pytest back/python/tests tests/integration -q` | PASS, 55 tests | Redaction, parsing, callback rules, fixtures, and offline flow assertions |
| `pnpm --dir front exec vitest run` | PASS, 46 tests | Vue/API behavior under jsdom |
| `pnpm --dir front run build` | PASS | `vue-tsc` and Vite production build |
| PowerShell AST parse of `run_mvp_flow.ps1` | PASS, 0 parser errors | Launcher syntax only |
| Launcher preflight with a temporary fake token | PASS | Output was generic `FAIL preflight=CONFIGURATION_OR_SERVICE`; the fake token was absent from output |

The offline integration assertions cover controlled TXT/DOCX/PDF fixtures,
evidence and score invariants, retention calculations, callback race fixtures,
and sanitized error behavior. They do not open a connection to Java, MySQL,
Redis, or an external model.

## Controlled Simulation

`tests/integration/assert_mvp_flow.py --live` contains an in-memory,
loopback-only OpenAI-compatible provider and ephemeral credentials. It is a
deterministic handoff simulator, not evidence of a real provider's quality or
availability. The PowerShell launcher only relays allow-listed IDs, states,
status codes, and stable error categories. It loads `PYTHON_INTERNAL_SERVICE_TOKEN`
as process configuration, requires it (along with the other service
credentials) before a live attempt, and never echoes or passes it as a command
argument.

## Real Integration Not Verified

No live cross-service run was claimed for this report. At verification time:

- no authorized local `.env` was available in the worktree;
- the MySQL Windows service was running, but Redis on `127.0.0.1:6379` was not
  reachable;
- the launcher therefore stopped at its preflight and did not start Java or
  Python, run Flyway against MySQL, or execute the callback flow;
- no real OpenAI-compatible provider was contacted and no Playwright browser
  screenshots were captured.

To obtain live evidence, provision authorized values in an untracked `.env`,
start Redis, then run:

```powershell
powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1 -RequireLive
```

The command must report Java/Python health, MySQL TCP readiness, Redis PING,
and the controlled flow before it can be treated as live evidence.

## Residual Risks and Follow-up

- Administrator registration remains open by product decision and must be
  restricted before a shared or public deployment.
- The scheduler, Flyway migrations, JPA mappings, Redis serialization, and
  deletion races still need a real MySQL/Redis smoke run. Passing unit tests is
  not a substitute for that environment evidence.
- Cache expiry and soft deletion intentionally leave encrypted resume data in
  MySQL. Operator deletion procedures, access controls, backups, and audit
  retention are outside this application slice.
- Real provider latency, malformed output, rate limits, cost, factual quality,
  fairness, and security review remain unverified. External model traffic must
  use an authorized provider and the documented redaction boundary.
- PDF parsing, additional role families, export, vector search, interview
  workflows, clustering, and production deployment hardening are excluded from
  this MVP.
