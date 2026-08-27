# Resume Matching Platform

This repository contains the initial hybrid runtime for the resume matching
platform: a Spring Boot public API, a FastAPI analysis service, and a Vue web
application. Versioned API and internal-message contracts live in
[`contracts/`](contracts/README.md) and are the shared interface authority.

## Local Setup

Copy `.env.example` to a local `.env` and replace its placeholder values with
authorized development credentials. Do not commit `.env` files.

The Java service reads these values from the process environment. To launch
the local Spring profile from PowerShell, export the assignments from `.env`
first:

```powershell
Get-Content .env |
  Where-Object { $_ -match '^[A-Z0-9_]+=' } |
  ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "Env:$name" -Value $value
  }
Push-Location back\java
try { .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local' }
finally { Pop-Location }
```

`back/java/src/main/resources/application-local.yml` is tracked and contains
only environment-variable references; it is activated by the `local` profile.

Run the local Redis-only dependency definition with Docker Compose when Docker
Desktop is available:

```powershell
docker compose -f docker-compose.redis.yml up -d
```

Build and test the service roots:

```powershell
back\java\mvnw.cmd -q -DskipTests compile
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pip install -e "back/python[test]"
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q
pnpm --dir front install
pnpm --dir front run build
```

## Controlled MVP Flow

The cross-service fixture checks are kept under
[`tests/integration/`](tests/integration). They run without a database or
provider and validate the checked-in TXT/DOCX/PDF inputs:

```powershell
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q
```

To exercise the Java-to-Python handoff, first copy `.env.example` to `.env`,
replace every placeholder with authorized local values, and make Redis
available. Then run:

```powershell
powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1
```

The launcher defaults to an explicit `SKIP` when services or credentials are
missing. Use `-RequireLive` (or `MVP_REQUIRE_LIVE=1`) for a CI failure instead.
The live runner uses a temporary loopback fake provider and keeps its
credential in memory; it never prints JWTs, API keys, callback tokens, or
resume contents. The Playwright lifecycle check is opt-in:

```powershell
$env:E2E_LIVE = '1'
$env:E2E_API_BASE_URL = 'http://127.0.0.1:8080'
$env:E2E_PROVIDER_URL = 'http://127.0.0.1:<fake-provider-port>'
pnpm --dir front exec playwright test e2e/resume-lifecycle.spec.ts
```

See [`tests/integration/task-10-report.md`](tests/integration/task-10-report.md)
for the covered states and prerequisite behavior.
