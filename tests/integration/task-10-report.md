# Task 10 Integration Evidence

## Scope

This lane adds deterministic TXT, DOCX, and explicitly unsupported PDF inputs;
an offline assertion module; a controlled PowerShell launcher; and an opt-in
Playwright lifecycle test. The checks exercise the public Java API and treat
Java as the only persistence and authorization owner.

## Default and live modes

`assert_mvp_flow.py` is safe to run without services. Its default pytest path
only validates the checked-in fixtures and the evidence/score invariants. The
live flow is opt-in (`--live`) and starts a loopback-only fake OpenAI-compatible
provider in memory. It generates one ephemeral provider credential for that
provider, never writes it to disk, and never prints it.

`run_mvp_flow.ps1` loads an existing `.env` without echoing values, reuses
healthy services, and starts the Maven/Python processes only when the required
configuration is present. Missing credentials, Docker/Redis, or service health
produce an explicit `SKIP` by default. Add `-RequireLive` (or set
`MVP_REQUIRE_LIVE=1`) when a missing prerequisite must fail CI. Only
allow-listed lines containing IDs, enum states, and HTTP statuses are relayed.

The Playwright test is similarly disabled unless `E2E_LIVE=1` and
`E2E_PROVIDER_URL` are supplied. The frontend must be started with
`VITE_API_BASE_URL` pointing at the Java API. A non-loopback provider also
requires `E2E_PROVIDER_API_KEY`; loopback tests use an in-memory ephemeral key.

## Covered assertions

- TXT and DOCX uploads are accepted and remain `ACTIVE`.
- PDF upload is rejected with HTTP 415 and `UNSUPPORTED_FILE` in live mode.
- A completed task is `SUCCEEDED`, and every returned requirement has a
  non-empty evidence excerpt with a non-negative, increasing source range.
- User soft deletion removes active visibility, recovery lists the record, and
  explicit restore returns it to active visibility.
- A gated provider callback released after deletion is rejected as
  `TASK_GONE`; no result is recreated and recovery metadata remains available.
- Administrator soft deletion hides the record from the owner, while the
  administrator recovery scope can restore it.
- Cache-expiry advancement is reported as `SKIP` because production has no
  public clock-mutation endpoint; deterministic clock tests belong to the Java
  integration profile.

## Verification run

```powershell
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q
$tokens=$null; $errors=$null
[System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path tests/integration/run_mvp_flow.ps1),[ref]$tokens,[ref]$errors) | Out-Null
```

The fixture/contract checks pass offline. A live run must be performed only in
an explicitly provisioned environment with authorized credentials; this lane
does not guess, embed, or commit MySQL, JWT, encryption, or provider secrets.
