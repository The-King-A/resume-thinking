# Task 10 Report: Controlled Cross-Service Flow

## Implemented

- Added deterministic TXT, DOCX, and unsupported-PDF fixtures under
  `tests/integration/fixtures/`.
- Added `assert_mvp_flow.py` with offline fixture/evidence assertions and an
  opt-in live flow. The live flow registers USER and ADMIN actors, configures a
  per-user model profile, exercises TXT/DOCX/PDF upload behavior, validates an
  evidence-backed result, checks user delete/restore, checks a late callback as
  `TASK_GONE`, and checks administrator delete/restore visibility.
- Added `run_mvp_flow.ps1` for health checks, optional native service startup,
  `.env` loading, and sanitized output. It defaults to `SKIP` when prerequisites
  are not configured; `-RequireLive` makes that condition fail.
- Added the opt-in Playwright UI lifecycle test and `front/vitest.config.ts` so
  Vitest excludes Playwright files from `*.spec.ts` discovery.

## Verification

Commands run from the primary worktree:

```text
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q
=> 3 passed

C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m py_compile tests/integration/assert_mvp_flow.py
=> passed

PowerShell AST parse of tests/integration/run_mvp_flow.ps1
=> passed

pnpm --dir front exec vitest run --passWithNoTests
=> 6 files, 21 tests passed (e2e excluded)

pnpm --dir front exec tsc --ignoreConfig --noEmit --target ES2022 --module NodeNext --moduleResolution NodeNext --types node e2e/resume-lifecycle.spec.ts
=> passed

pnpm --dir front exec playwright test e2e/resume-lifecycle.spec.ts --list
=> 1 test listed

pnpm --dir front exec playwright test e2e/resume-lifecycle.spec.ts
=> 1 skipped (E2E_LIVE not set)

powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1
=> [flow] SKIP configuration=MISSING_ENV

powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1 -RequireLive
=> [flow] FAIL preflight=CONFIGURATION_OR_SERVICE, exit 2
```

## Privacy and boundaries

No MySQL, JWT, AES, or external-provider secret was guessed, committed, or
printed. The embedded provider uses one randomly generated, loopback-only
fixture credential held in memory for the lifetime of the live process; it is
never written to a file or included in logs. API clients discard error bodies,
and the PowerShell launcher forwards only allow-listed IDs, enum states, and
HTTP statuses. Resume/job text and callback tokens are not logged.

The live provider is deterministic test infrastructure, not an accuracy claim
about a production model. Cache-expiry clock advancement, Redis key inspection,
duplicate callback injection, and stale-attempt injection remain explicitly
unverified until a dedicated integration profile exposes those controls and
authorized MySQL/Redis credentials are supplied.

## Fix Round 1

The controlled flow was tightened after review:

- Evidence assertions can now bind `sourceStart`/`sourceEnd` to the exact
  checked-in resume text, including bounds and excerpt equality.
- The frozen callback fixtures are exercised offline for exact duplicate
  replay, older-attempt stale delivery, and deleted-task `TASK_GONE` context.
  A live callback-capture proxy is intentionally not enabled because the
  Java Task 7 callback endpoints are not yet present in this integration HEAD.
- Live USER uploads assert a seven-day `createdAt` to `visibleUntil` window;
  an ADMIN-owned upload asserts the explicit 30-day window. Mutable-clock
  archive transitions remain opt-in/unverified because no public clock control
  is exposed.
- The late/deleted task still must return `TASK_GONE` through the Java task and
  result APIs. When `REDIS_HOST`/`REDIS_PORT` are configured, the runner also
  sends a protocol-level Redis `PING` and `EXISTS resume:view:<late-id>` check;
  otherwise it emits a sanitized `SKIP` for that optional probe.
- The launcher verifies Java actuator health (which implies startup/Flyway
  completed), Python `/health`, MySQL TCP reachability, and Redis RESP `PONG`
  before submitting. It accepts an explicit `MVP_PYTHON_EXECUTABLE` only when
  that executable reports Python 3.11, then falls back to `py -3.11` or known
  Python311 paths. Any attempted live assertion failure exits nonzero even
  without `-RequireLive`; missing configuration or unavailable services remain
  `SKIP` unless strict mode is requested.

### Fix Round 1 verification

Commands run from the primary worktree:

```text
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q
=> 6 passed in 0.04s

C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m py_compile tests/integration/assert_mvp_flow.py
=> passed

$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path tests/integration/run_mvp_flow.ps1),[ref]$tokens,[ref]$errors) | Out-Null; "errors=$($errors.Count)"
=> errors=0

pnpm --dir front exec vitest run --passWithNoTests
=> 6 files passed, 21 tests passed (e2e excluded)

pnpm --dir front run build
=> vite build succeeded (1658 modules transformed)

powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1
=> [flow] SKIP configuration=MISSING_ENV; exit=0

powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1 -RequireLive
=> [flow] FAIL preflight=CONFIGURATION_OR_SERVICE; exit=2
```

No live Java/MySQL/Redis flow was claimed from this worktree: Task 7 public
endpoints and authorized service credentials are still integration
prerequisites. No credentials, callback tokens, resume/job text, or response
bodies are printed.

## Fix Round 2

The evidence assertion was tightened to the frozen v1 public result contract.
It now requires UUID `taskId`/`resumeId`, every score component as a finite
number in `[0, 1]`, and the exact weighted composite. Each requirement must
carry its v1 ID/text/enums and bounded component score. Each evidence item must
contain only the v1 fields (`id`, `sourceType`, `sourceLocation`,
`sourceStart`, `sourceEnd`, `excerpt`, `confidence`, `strength`), with UUID and
enum validation, integer increasing offsets, finite confidence, unique IDs,
and optional source-text slice equality. Legacy `evidenceId`, `sourceOffset`,
missing fields, unknown evidence references, and unsupported suggestion states
are rejected.

### Fix Round 2 verification

```text
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q
=> 8 passed in 0.03s

C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m py_compile tests/integration/assert_mvp_flow.py
=> passed

$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path tests/integration/run_mvp_flow.ps1),[ref]$tokens,[ref]$errors) | Out-Null; "errors=$($errors.Count)"
=> errors=0

git diff --check
=> passed (only Git's LF/CRLF normalization warnings)
```
