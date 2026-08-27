# Resume Matching Platform

This repository contains the initial hybrid runtime for the resume matching
platform: a Spring Boot public API, a FastAPI analysis service, and a Vue web
application. Versioned API and internal-message contracts live in
[`contracts/`](contracts/README.md) and are the shared interface authority.

## Local Setup

Copy `.env.example` to a local `.env` and replace its placeholder values with
authorized development credentials. Do not commit `.env` files.

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
