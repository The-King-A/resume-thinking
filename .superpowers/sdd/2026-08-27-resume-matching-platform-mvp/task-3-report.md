# Task 3 Bootstrap Report

## Delivered

- Added non-secret environment template and generated-file exclusions, including
  `contracts/node_modules/`.
- Added a Redis-only Compose definition; no containers were started.
- Added JDK 21 Spring Boot 3.4.3 Maven-wrapper shell and local configuration
  example with environment references only.
- Added Python 3.11 FastAPI health endpoint and a test-first health check.
- Added the Vue 3 TypeScript Vite shell and requested runtime/test dependencies.

## Validation

| Check | Result |
| --- | --- |
| `docker compose -f docker-compose.redis.yml config` | Passed; one `redis` service and one `resume_redis_data` named volume. Docker Desktop was stopped, so Redis was not started. |
| `back\\java\\mvnw.cmd -q -DskipTests compile` | Started but produced no output while fetching the external Maven distribution; it was stopped after 60 seconds. |
| `E:\\maven\\apache-maven-3.9.16-bin\\apache-maven-3.9.16\\bin\\mvn.cmd -q -DskipTests compile` | Passed with JDK 21.0.12 as the documented equivalent fallback. |
| `C:\\Users\\theking.guo\\AppData\\Local\\Programs\\Python\\Python311\\python.exe -m pip install -e "back/python[test]"` | Passed. |
| `C:\\Users\\theking.guo\\AppData\\Local\\Programs\\Python\\Python311\\python.exe -m pytest back/python/tests -q` | Passed: 1 test. FastAPI emitted an upstream `TestClient` deprecation warning. |
| `pnpm --dir front install` | Passed. |
| `pnpm --dir front run build` | Passed. |

## TDD Evidence

The health test was written before `app.main` existed. After installing the
declared test dependency, it failed with `ModuleNotFoundError: No module named
'app.main'`. The minimal FastAPI application and `/health` endpoint were then
added, and the same test passed.

## External Constraint

Spring Initializr now rejects the frozen bootstrap URL's `bootVersion=3.4.3`
request because its public compatibility range has moved to Spring Boot 4. The
committed shell therefore preserves the required Spring Boot 3.4.3/JDK 21
coordinates and Maven wrapper, while the compile verification used the local
Maven fallback after the wrapper's external distribution download stalled.

## Fix Round 1

- Added tracked `application-local.yml` with `local` profile activation and
  environment-variable references only.
- Updated README local startup instructions to export `.env` assignments before
  launching `mvnw.cmd` with the `local` profile.
- Focused config check initially failed because the profile and export step were
  absent, then passed after the fix.
- `mvnw.cmd -q -DskipTests compile` again stalled without output and was stopped;
  the equivalent local Maven 3.9.16 compile passed.
- Python health test and frontend production build passed after the fix.
- The Unix `back/java/mvnw` entrypoint is marked executable in Git; the Windows
  `mvnw.cmd` path remains the documented command on this host.
