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
