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
