# Resume Matching Platform MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Deliver the first working, evidence-backed Java-backend resume-to-job matching vertical slice with per-user OpenAI-compatible model profiles, role-aware lifecycle controls, and Redis archival recovery.

**Architecture:** Vue 3 is the operational client. Spring Boot 3 on JDK 21 is the only public API, authorization, orchestration, and persistence authority. FastAPI on Python 3.11 performs extraction, redaction, matching, and guarded model calls. MySQL holds encrypted durable records; Redis holds page cache, task progress, and idempotency state. Java owns every MySQL/Redis write, including Python callbacks.

**Tech Stack:** Vue 3, TypeScript, Vite, Element Plus, Pinia, Vitest, Playwright, Spring Boot 3, Java 21, Maven Wrapper, Spring Security, JPA, Flyway, MySQL 8.4, Redis 7 Docker container, FastAPI, Pydantic, httpx, python-docx, pytest, OpenAPI, JSON Schema.

**Spec:** docs/superpowers/specs/2026-08-27-resume-matching-platform-design.md

## Global Constraints

- Only the Java-backend job family is in this slice. TXT and DOCX are supported; PDF returns UNSUPPORTED_FILE.
- Compile and run Java with JDK 21. Run Python only with the installed Python 3.11 executable.
- Java is the sole public business, authorization, task-state, MySQL, and Redis authority. Python has no user login and no direct MySQL/Redis writes.
- Freeze OpenAPI, internal callback schema, lifecycle states, error envelope, and fixtures before implementation fanout.
- Treat resumes, job text, provider output, and user configuration as untrusted. Redact before an external call and do not log secrets or full resume text.
- resumes.status=0 means not soft-deleted; resumes.status=1 means soft deletion succeeded. visibility_state records ACTIVE, USER_SOFT_DELETED, ADMIN_SOFT_DELETED, USER_CACHE_ARCHIVED, or ADMIN_CACHE_ARCHIVED.
- MySQL retains records until an authorized operator physically deletes them. Seven-day and thirty-day policies delete Redis keys and archive page visibility only.
- USER restores only its own eligible resume. ADMIN can recover eligible resumes for every owner. An administrator soft deletion is invisible and unrecoverable to the normal owner.
- Public registration permits USER and ADMIN roles by product decision. Continue all server-side authorization checks and document the global-access risk.
- Matching uses exactly 0.40 skills, 0.25 projects, 0.15 work content, 0.10 education/experience, and 0.10 soft skills.
- Every user owns OpenAI-compatible profiles. Persist API keys encrypted and never return or log them.
- Execute contract work sequentially. After contract freeze, use agents only for non-overlapping Java, Python, and frontend ownership in an isolated Git worktree.

---

## Execution Preconditions

- Use using-git-worktrees before Task 1 starts implementation in an isolated checkout.
- Start Docker Desktop before starting the Redis container.
- Obtain the authorized MySQL URL, database name, username, and password before Flyway runs. Store values only in an untracked .env file.
- Generate a base64 AES-GCM master key and JWT signing key locally; never commit either value.
- Automated tests use a local fake OpenAI-compatible server. Real provider credentials enter through the completed UI only.

## Planned File Structure

~~~
contracts/
  README.md
  package.json
  openapi/v1/openapi.yaml
  internal/v1/analysis-job.schema.json
  internal/v1/analysis-callback.schema.json
  fixtures/v1/
  scripts/validate-contracts.mjs
back/java/
  pom.xml
  mvnw.cmd
  src/main/java/com/resumethinking/platform/
  src/main/resources/application.yml
  src/main/resources/application-local.yml.example
  src/main/resources/db/migration/
  src/test/java/com/resumethinking/platform/
back/python/
  pyproject.toml
  app/
    __init__.py
  tests/
    test_health.py
  test_support/fake_openai_server.py
front/
  src/api/
  src/stores/
  src/views/
  src/components/
  src/**/*.spec.ts
  e2e/resume-lifecycle.spec.ts
docker-compose.redis.yml
.env.example
.gitignore
README.md
tests/integration/
docs/verification/
~~~

## Task 1: Contract Guardian - Freeze Public API and Lifecycle

**Files:**
- Create: contracts/README.md
- Create: contracts/package.json
- Create: contracts/openapi/v1/openapi.yaml
- Create: contracts/internal/v1/analysis-job.schema.json
- Create: contracts/internal/v1/analysis-callback.schema.json

**Interfaces:**
- Consumes: approved design specification.
- Produces: the authoritative public Java API contract and Java-to-Python handoff contract.

- [ ] **Step 1: Define error and state schemas before any endpoint.**

~~~yaml
ApiError:
  type: object
  required: [code, message, correlationId, retryable]
  properties:
    code: { type: string, example: RESUME_ARCHIVED }
    message: { type: string, example: The resume is archived and must be restored first. }
    correlationId: { type: string, format: uuid }
    retryable: { type: boolean }
VisibilityState:
  type: string
  enum: [ACTIVE, USER_SOFT_DELETED, ADMIN_SOFT_DELETED, USER_CACHE_ARCHIVED, ADMIN_CACHE_ARCHIVED]
TaskState:
  type: string
  enum: [QUEUED, PROCESSING, SUCCEEDED, FAILED, TIMED_OUT, BLOCKED]
~~~

- [ ] **Step 2: Define exact public routes and ownership rules.**

Create routes for POST /api/v1/auth/register, POST /api/v1/auth/login, GET /api/v1/auth/me, CRUD /api/v1/llm-profiles, POST /api/v1/llm-profiles/{profileId}/test, POST /api/v1/resumes, GET /api/v1/resumes, GET /api/v1/resumes/{resumeId}, DELETE /api/v1/resumes/{resumeId}, GET/POST recovery routes, POST /api/v1/match-tasks, GET /api/v1/match-tasks/{taskId}, GET /api/v1/match-tasks/{taskId}/result, and administrator recovery/delete/restore routes.

~~~yaml
DeleteResumeRequest:
  type: object
  required: [confirmationText, expectedVersion]
  properties:
    confirmationText: { type: string, const: "确认删除简历" }
    expectedVersion: { type: integer, minimum: 0 }
CreateMatchTaskRequest:
  type: object
  required: [resumeId, llmProfileId, jobDescriptionText, idempotencyKey]
  properties:
    resumeId: { type: string, format: uuid }
    llmProfileId: { type: string, format: uuid }
    jobDescriptionText: { type: string, minLength: 20, maxLength: 20000 }
    idempotencyKey: { type: string, minLength: 16, maxLength: 128 }
~~~

Every protected path declares bearer authentication and returns the same RESOURCE_NOT_FOUND envelope for an unknown ID and a foreign ID.

- [ ] **Step 3: Define internal analysis job and callback payloads without user identity.**

~~~json
{
  "taskId": "uuid",
  "attempt": 1,
  "resumeVersion": 3,
  "sourceType": "DOCX",
  "redactionRequired": true,
  "callbackUrl": "http://127.0.0.1:8080/internal/v1/analysis-results",
  "callbackToken": "ephemeral-secret",
  "provider": {
    "baseUrl": "https://provider.example/v1",
    "model": "model-name",
    "apiKey": "memory-only-secret"
  }
}
~~~

The callback schema requires taskId, attempt, callbackId, callbackToken, and evidence IDs that refer to evidence supplied in the job. It forbids ownerId, database credentials, plaintext passwords, and arbitrary persistence fields.

- [ ] **Step 4: Record state transitions and error codes in contracts/README.md.**

Record QUEUED -> PROCESSING -> SUCCEEDED|FAILED|TIMED_OUT and any active task -> BLOCKED after soft deletion or archival. Record ACTIVE -> USER_SOFT_DELETED|ADMIN_SOFT_DELETED|USER_CACHE_ARCHIVED|ADMIN_CACHE_ARCHIVED, plus role-limited restore to ACTIVE. Define TASK_GONE, STALE_ATTEMPT, IDEMPOTENCY_CONFLICT, RESUME_ARCHIVED, RESUME_SOFT_DELETED, RESOURCE_NOT_FOUND, MODEL_UNAVAILABLE, MODEL_OUTPUT_INVALID, MODEL_ENDPOINT_REJECTED, and UNSUPPORTED_FILE.

- [ ] **Step 5: Create the linter package, lint the contract, and commit it.**

~~~json
{
  "private": true,
  "devDependencies": {
    "@redocly/cli": "^1.27.2",
    "ajv": "^8.17.1",
    "ajv-formats": "^3.0.1"
  }
}
~~~

Run: pnpm --dir contracts install --frozen-lockfile=false

Run: pnpm --dir contracts exec redocly lint openapi/v1/openapi.yaml

Expected: no OpenAPI errors.

~~~bash
git add contracts/README.md contracts/package.json contracts/pnpm-lock.yaml contracts/openapi/v1/openapi.yaml contracts/internal/v1
git commit -m "feat(contracts): freeze v1 API and analysis handoff"
~~~

## Task 2: Contract Guardian - Fixture Matrix and Validation Harness

**Files:**
- Create: contracts/scripts/validate-contracts.mjs
- Create: contracts/fixtures/v1/auth-register-valid.json
- Create: contracts/fixtures/v1/llm-profile-valid.json
- Create: contracts/fixtures/v1/match-request-valid.json
- Create: contracts/fixtures/v1/match-request-invalid.json
- Create: contracts/fixtures/v1/callback-valid.json
- Create: contracts/fixtures/v1/callback-duplicate.json
- Create: contracts/fixtures/v1/callback-stale.json
- Create: contracts/fixtures/v1/callback-after-soft-delete.json
- Create: contracts/fixtures/v1/archive-user.json
- Create: contracts/fixtures/v1/archive-admin.json
- Create: contracts/fixtures/v1/error-envelope.json

**Interfaces:**
- Consumes: schemas from Task 1.
- Produces: fixtures that Java and Python tests consume without reinterpreting fields.

- [ ] **Step 1: Add validation scripts to the Task 1 package.**

~~~json
{
  "scripts": {
    "lint": "redocly lint openapi/v1/openapi.yaml",
    "validate": "node scripts/validate-contracts.mjs"
  }
}
~~~

Merge these scripts into the existing Task 1 package without changing its pinned dependencies, then run pnpm --dir contracts install so the lockfile matches the scripts.

- [ ] **Step 2: Write the expected-invalid match request before the validator.**

~~~json
{
  "resumeId": "not-a-uuid",
  "llmProfileId": "not-a-uuid",
  "jobDescriptionText": "",
  "idempotencyKey": ""
}
~~~

Make callback-duplicate reuse callback-valid's callbackId and payload hash. Make callback-stale use an older attempt. Make callback-after-soft-delete structurally valid but semantically rejected by Java.

- [ ] **Step 3: Write the fixture validator and make the invalid fixture fail.**

~~~js
import Ajv from 'ajv';
import addFormats from 'ajv-formats';
import { readFile } from 'node:fs/promises';

const ajv = new Ajv({ allErrors: true, strict: false });
addFormats(ajv);
const callbackSchema = JSON.parse(await readFile('internal/v1/analysis-callback.schema.json', 'utf8'));
const callback = JSON.parse(await readFile('fixtures/v1/callback-valid.json', 'utf8'));
const validateCallback = ajv.compile(callbackSchema);
if (!validateCallback(callback)) throw new Error(ajv.errorsText(validateCallback.errors));
~~~

Extend the script to assert known-valid fixtures pass and match-request-invalid is rejected by the matching request schema.

- [ ] **Step 4: Install and run contract validation.**

Run: pnpm --dir contracts install --frozen-lockfile=false

Run: pnpm --dir contracts run lint && pnpm --dir contracts run validate

Expected: every valid fixture passes, and the validator reports the invalid fixture rejection as expected.

- [ ] **Step 5: Commit the fixture baseline.**

~~~bash
git add contracts/package.json contracts/pnpm-lock.yaml contracts/scripts contracts/fixtures
git commit -m "test(contracts): add v1 fixture matrix"
~~~

## Task 3: Bootstrap Hybrid Runtime and Local Configuration

**Files:**
- Create: .gitignore
- Create: .env.example
- Create: docker-compose.redis.yml
- Create: README.md
- Create: back/java Spring Boot Maven Wrapper project
- Create: back/java/src/main/resources/application.yml
- Create: back/java/src/main/resources/application-local.yml.example
- Create: back/python/pyproject.toml
- Create: back/python/app/__init__.py
- Create: back/python/app/main.py
- Create: back/python/tests/test_health.py
- Create: front Vue 3 TypeScript Vite project

**Interfaces:**
- Consumes: frozen contracts.
- Produces: buildable Java, Python, and Vue roots plus a Redis-only container definition.

- [ ] **Step 1: Add secret and generated-file exclusions.**

~~~gitignore
.env
.env.*
!.env.example
back/java/target/
back/python/.venv/
back/python/.pytest_cache/
front/node_modules/
front/dist/
playwright-report/
test-results/
~~~

- [ ] **Step 2: Add the non-secret environment template.**

~~~dotenv
MYSQL_URL=jdbc:mysql://127.0.0.1:3306/resume_thinking
MYSQL_USERNAME=replace-with-authorized-user
MYSQL_PASSWORD=replace-with-authorized-password
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
JWT_SIGNING_KEY_BASE64=replace-with-32-byte-base64-key
APP_ENCRYPTION_KEY_BASE64=replace-with-32-byte-base64-key
PYTHON_ANALYSIS_BASE_URL=http://127.0.0.1:8000
JAVA_CALLBACK_BASE_URL=http://127.0.0.1:8080
~~~

- [ ] **Step 3: Define and validate the Redis-only Compose file.**

~~~yaml
services:
  redis:
    image: redis:7.4-alpine
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - resume_redis_data:/data
volumes:
  resume_redis_data:
~~~

Run: docker compose -f docker-compose.redis.yml config

Expected: exactly one redis service and one named volume.

- [ ] **Step 4: Create compatible Java, Python, and frontend shells.**

Run this exact non-interactive Java bootstrap from the repository root:

~~~powershell
$bootstrapRoot = Join-Path $env:TEMP ('resume-platform-' + [guid]::NewGuid())
$bootstrapZip = Join-Path $bootstrapRoot 'java.zip'
New-Item -ItemType Directory -Path $bootstrapRoot | Out-Null
Invoke-WebRequest -Uri 'https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=3.4.3&baseDir=resume-platform-api&groupId=com.resumethinking&artifactId=resume-platform-api&name=resume-platform-api&packageName=com.resumethinking.platform&javaVersion=21&dependencies=web,validation,data-jpa,security,data-redis,mysql,flyway,actuator' -OutFile $bootstrapZip
Expand-Archive -LiteralPath $bootstrapZip -DestinationPath $bootstrapRoot
New-Item -ItemType Directory -Force -Path 'back' | Out-Null
Move-Item -LiteralPath (Join-Path $bootstrapRoot 'resume-platform-api') -Destination 'back\java'
~~~

Generate the Vue shell with:

Run: pnpm create vite front --template vue-ts --no-interactive

Run: pnpm --dir front add axios element-plus pinia vue-router

Run: pnpm --dir front add -D vitest jsdom @testing-library/vue @playwright/test

Create back/python/pyproject.toml:

~~~toml
[build-system]
requires = ["setuptools>=75"]
build-backend = "setuptools.build_meta"

[project]
name = "resume-analysis-service"
version = "0.1.0"
requires-python = ">=3.11,<3.12"
dependencies = [
  "fastapi>=0.115,<1",
  "uvicorn[standard]>=0.32,<1",
  "pydantic>=2.10,<3",
  "httpx>=0.28,<1",
  "python-docx>=1.1,<2",
  "python-multipart>=0.0.18,<1"
]
[project.optional-dependencies]
test = ["pytest>=8.3,<9", "pytest-asyncio>=0.24,<1"]

[tool.setuptools]
packages = ["app"]
~~~

Create the initial Python health endpoint and its one passing test:

~~~python
from fastapi import FastAPI

app = FastAPI()

@app.get('/health')
def health() -> dict[str, str]:
    return {'status': 'ok'}
~~~

~~~python
from fastapi.testclient import TestClient
from app.main import app

def test_health() -> None:
    assert TestClient(app).get('/health').json() == {'status': 'ok'}
~~~

Create application-local.yml.example with environment-only connection settings:

~~~yaml
spring:
  datasource:
    url: ${MYSQL_URL}
    username: ${MYSQL_USERNAME}
    password: ${MYSQL_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
app:
  jwt-signing-key-base64: ${JWT_SIGNING_KEY_BASE64}
  encryption-key-base64: ${APP_ENCRYPTION_KEY_BASE64}
  python-analysis-base-url: ${PYTHON_ANALYSIS_BASE_URL}
~~~

- [ ] **Step 5: Run the smallest build check for every root.**

Run: back\java\mvnw.cmd -q -DskipTests compile

Run: C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pip install -e "back/python[test]"

Run: C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q

Run: pnpm --dir front install && pnpm --dir front run build

Expected: Java and frontend build. Python reports one passing health test.

- [ ] **Step 6: Commit bootstrap files without local secrets.**

~~~bash
git add .gitignore .env.example docker-compose.redis.yml README.md back/java back/python front
git commit -m "chore: bootstrap hybrid application runtime"
~~~

## Task 4: Java Lane - Identity, Encryption, and Model Profile Ownership

**Files:**
- Create: back/java/src/main/resources/db/migration/V1__users_and_llm_profiles.sql
- Create: back/java/src/main/java/com/resumethinking/platform/auth/User.java
- Create: back/java/src/main/java/com/resumethinking/platform/auth/UserRole.java
- Create: back/java/src/main/java/com/resumethinking/platform/auth/AuthService.java
- Create: back/java/src/main/java/com/resumethinking/platform/auth/AuthController.java
- Create: back/java/src/main/java/com/resumethinking/platform/auth/JwtService.java
- Create: back/java/src/main/java/com/resumethinking/platform/config/SecurityConfig.java
- Create: back/java/src/main/java/com/resumethinking/platform/crypto/AesGcmCryptoService.java
- Create: back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileService.java
- Create: back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileController.java
- Test: back/java/src/test/java/com/resumethinking/platform/auth/AuthServiceTest.java
- Test: back/java/src/test/java/com/resumethinking/platform/profiles/LlmProfileServiceTest.java

**Interfaces:**
- Consumes: Task 1 auth/profile routes and Task 2 fixtures.
- Produces: AuthService.register(RegisterCommand), AuthService.login(LoginCommand), LlmProfileService.create(UUID, CreateLlmProfileCommand), and LlmProfileService.decryptForDispatch(UUID, UUID).

- [ ] **Step 1: Write failing identity and profile tests.**

~~~java
@Test
void registersSelectedAdminRoleWithoutPersistingPlaintextPassword() {
    var result = authService.register(new RegisterCommand("admin1", "admin1@example.test", "StrongPassphrase1", UserRole.ADMIN));
    assertThat(result.role()).isEqualTo(UserRole.ADMIN);
    assertThat(userRepository.findById(result.id()).orElseThrow().getPasswordHash()).doesNotContain("StrongPassphrase1");
}

@Test
void profileReadNeverReturnsApiKeyAndCrossOwnerDecryptFails() throws Exception {
    var profile = profileService.create(ownerId, new CreateLlmProfileCommand("work", "https://api.example/v1", "model-a", "secret-key"));
    assertThat(objectMapper.writeValueAsString(profile)).doesNotContain("secret-key");
    assertThatThrownBy(() -> profileService.decryptForDispatch(otherUserId, profile.id())).isInstanceOf(ResourceNotFoundException.class);
}
~~~

- [ ] **Step 2: Run the tests to prove the layer is absent.**

Run: back\java\mvnw.cmd -Dtest=AuthServiceTest,LlmProfileServiceTest test

Expected: FAIL because the services and migration-backed entities do not exist.

- [ ] **Step 3: Implement V1 schema, BCrypt/JWT, AES-GCM, and profile services.**

Use unique username/email, bcrypt password hashes, and JWT role claims. Llm profiles hold owner_id, endpoint URL, model name, ciphertext, nonce, key version, and selected flag. AesGcmCryptoService uses the base64 256-bit application key, a fresh 12-byte nonce per encryption, and GCM authentication.

~~~java
public record DispatchLlmProfile(URI baseUrl, String model, String apiKey) {}

public DispatchLlmProfile decryptForDispatch(UUID actorId, UUID profileId) {
    LlmProfile profile = repository.findByIdAndOwnerId(profileId, actorId)
        .orElseThrow(ResourceNotFoundException::new);
    return new DispatchLlmProfile(URI.create(profile.getBaseUrl()), profile.getModel(),
        crypto.decrypt(profile.getCiphertext(), profile.getNonce()));
}
~~~

- [ ] **Step 4: Implement endpoint validation and bounded profile test.**

Reject non-HTTPS custom URLs and private/reserved targets in production. Allow http://127.0.0.1 only when app.allow-local-model-endpoints=true. The test endpoint calls /models with fixed connect/read timeouts and returns sanitized status/model names only.

- [ ] **Step 5: Run focused tests and commit.**

Run: back\java\mvnw.cmd -Dtest=AuthServiceTest,LlmProfileServiceTest test

Expected: PASS with no password or API key in assertion output or logs.

~~~bash
git add back/java/pom.xml back/java/src/main back/java/src/test/java/com/resumethinking/platform/auth back/java/src/test/java/com/resumethinking/platform/profiles
git commit -m "feat(java): add auth and encrypted model profiles"
~~~

## Task 5: Java Lane - Resume Lifecycle, Redis Archival, and Recovery

**Files:**
- Create: back/java/src/main/resources/db/migration/V2__resumes_and_lifecycle.sql
- Create: back/java/src/main/java/com/resumethinking/platform/resumes/Resume.java
- Create: back/java/src/main/java/com/resumethinking/platform/resumes/VisibilityState.java
- Create: back/java/src/main/java/com/resumethinking/platform/resumes/ResumeLifecycleService.java
- Create: back/java/src/main/java/com/resumethinking/platform/resumes/ArchiveScheduler.java
- Create: back/java/src/main/java/com/resumethinking/platform/resumes/ResumeController.java
- Create: back/java/src/main/java/com/resumethinking/platform/config/RedisConfig.java
- Test: back/java/src/test/java/com/resumethinking/platform/resumes/ResumeLifecycleServiceTest.java
- Test: back/java/src/test/java/com/resumethinking/platform/resumes/ArchiveSchedulerTest.java
- Create: back/java/src/test/java/com/resumethinking/platform/resumes/ResumeControllerTest.java

**Interfaces:**
- Consumes: actor identity from Task 4 and lifecycle contract from Task 1.
- Produces: softDelete(DeleteResumeCommand), recover(UUID, UUID, UserRole, long), archiveDue(Instant), and Redis keys under resume:view:{resumeId}.

- [ ] **Step 1: Write failing lifecycle tests.**

~~~java
@Test
void userSoftDeleteRemovesCacheAndCanRecoverOnlyOwnRecord() {
    Resume resume = activeResume(userId, UserRole.USER, clock.instant(), 2L);
    lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(), userId, UserRole.USER, "确认删除简历", 2L));
    assertThat(resume.getStatus()).isEqualTo(1);
    assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.USER_SOFT_DELETED);
    verify(redisCache).evict("resume:view:" + resume.getId());
    assertThatThrownBy(() -> lifecycleService.recover(resume.getId(), otherUserId, UserRole.USER, 3L))
        .isInstanceOf(ResourceNotFoundException.class);
}

@Test
void administratorSoftDeleteCannotBeRecoveredByOwner() {
    Resume resume = activeResume(ownerId, UserRole.USER, clock.instant(), 1L);
    lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(), adminId, UserRole.ADMIN, "确认删除简历", 1L));
    assertThatThrownBy(() -> lifecycleService.recover(resume.getId(), ownerId, UserRole.USER, 2L))
        .isInstanceOf(ResourceNotFoundException.class);
}
~~~

- [ ] **Step 2: Run the tests to verify lifecycle behavior is absent.**

Run: back\java\mvnw.cmd -Dtest=ResumeLifecycleServiceTest,ArchiveSchedulerTest test

Expected: FAIL because resume transitions and cache adapter do not exist.

- [ ] **Step 3: Implement V2 persistence and one transactional lifecycle service.**

Add owner ID, title, source type, encrypted raw content, status, visibility_state, visible_until, soft_deleted_by, soft_deleted_at, archived_at, restored_at, and JPA version. On create/recover, use creator role to set visible_until: seven days for USER, thirty days for ADMIN.

~~~java
public ResumeView recover(UUID resumeId, UUID actorId, UserRole role, long expectedVersion) {
    Resume resume = repository.findRecoverable(resumeId, actorId, role).orElseThrow(ResourceNotFoundException::new);
    requireVersion(resume, expectedVersion);
    resume.restore(clock.instant(), resume.getCreatorRole() == UserRole.ADMIN ? Duration.ofDays(30) : Duration.ofDays(7));
    redisCache.put(resume);
    auditRepository.save(ResumeRecoveryAudit.restored(resumeId, actorId, clock.instant()));
    return ResumeView.from(resume);
}
~~~

- [ ] **Step 4: Implement durable archival instead of Redis keyspace-event logic.**

Each minute, page through active rows whose visible_until is due. Transition each to USER_CACHE_ARCHIVED or ADMIN_CACHE_ARCHIVED according to creator role and evict all resume cache keys. Normal read/list routes exclude non-ACTIVE rows. Recovery routes use indexed, owner/role-scoped SQL queries and never physically delete MySQL rows.

- [ ] **Step 5: Add controller fixture tests and run focused checks.**

Test normal user delete/restore, admin delete/restore, owner denial after admin delete, archive after fixed-clock advancement, duplicate delete, and duplicate restore. Assert foreign and unknown resume IDs return the same RESOURCE_NOT_FOUND envelope.

Run: back\java\mvnw.cmd -Dtest=ResumeLifecycleServiceTest,ArchiveSchedulerTest test

Expected: PASS with no raw resume text in logs.

- [ ] **Step 6: Commit the lifecycle lane.**

~~~bash
git add back/java/src/main back/java/src/test/java/com/resumethinking/platform/resumes
git commit -m "feat(java): add resume lifecycle and archival recovery"
~~~

## Task 6: Python Lane - Extraction, Redaction, Matching, and Guarded Model Calls

**Files:**
- Create: back/python/app/settings.py
- Create: back/python/app/models.py
- Create: back/python/app/redaction.py
- Create: back/python/app/extraction.py
- Create: back/python/app/matching.py
- Create: back/python/app/openai_compatible.py
- Create: back/python/app/analysis_service.py
- Create: back/python/app/callback_client.py
- Modify: back/python/app/main.py
- Create: back/python/tests/test_redaction.py
- Create: back/python/tests/test_extraction.py
- Create: back/python/tests/test_matching.py
- Create: back/python/tests/test_openai_compatible.py
- Create: back/python/tests/test_analysis_service.py
- Create: back/python/test_support/fake_openai_server.py

**Interfaces:**
- Consumes: Task 1 internal schemas and Task 2 fixtures.
- Produces: POST /internal/v1/analysis-jobs, redact_text(text), extract_resume(source_type, bytes), and analyze_job(job).

- [ ] **Step 1: Write failing redaction, extraction, score, and invalid-model tests.**

~~~python
def test_redaction_removes_email_phone_and_identity_number() -> None:
    result = redact_text("Li Ming, li@example.test, 13800138000, 110101199001011234")
    assert "li@example.test" not in result.redacted_text
    assert "13800138000" not in result.redacted_text
    assert "110101199001011234" not in result.redacted_text

def test_composite_score_uses_documented_weights() -> None:
    score = composite_score(skills=1.0, projects=0.8, work_content=0.6, education_experience=0.5, soft_skills=0.4)
    assert score == pytest.approx(0.40 + 0.20 + 0.09 + 0.05 + 0.04)

async def test_invalid_model_json_returns_model_output_invalid() -> None:
    client = FakeOpenAiClient('{"requirements": [}')
    with pytest.raises(ModelOutputInvalid):
        await client.complete_structured(valid_request())
~~~

- [ ] **Step 2: Run tests to prove the service is absent.**

Run: C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q

Expected: FAIL at collection because the modules do not exist.

- [ ] **Step 3: Implement deterministic extraction and redaction before the model adapter.**

TXT decodes UTF-8 with a documented replacement strategy. DOCX uses python-docx paragraphs and stable paragraph/character offsets. Detect email, phone, identity number, and address patterns conservatively. Return redacted text plus replacement metadata. Reject every source type other than TXT/DOCX with UNSUPPORTED_FILE.

~~~python
def composite_score(*, skills: float, projects: float, work_content: float,
                    education_experience: float, soft_skills: float) -> float:
    return round(0.40 * skills + 0.25 * projects + 0.15 * work_content
                 + 0.10 * education_experience + 0.10 * soft_skills, 4)
~~~

- [ ] **Step 4: Implement the OpenAI-compatible adapter with strict Pydantic validation.**

Post only redacted data to {base_url}/chat/completions with fixed connect/read timeouts. Validate requirement IDs, evidence IDs, match states, component scores, strength, gap, and suggestion classification. Do not log HTTP headers, API keys, request content, or raw provider responses. Translate timeout to MODEL_UNAVAILABLE and invalid JSON/schema to MODEL_OUTPUT_INVALID.

- [ ] **Step 5: Implement callback retry behavior.**

FastAPI validates the internal job, starts background work, and posts exactly the contract callback fields. Retry transport failures or 5xx responses with the same callbackId. Stop on TASK_GONE, STALE_ATTEMPT, IDEMPOTENCY_CONFLICT, or success. Strip callback response bodies before logs.

- [ ] **Step 6: Run Python checks and commit the lane.**

Run: C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q

Expected: PASS for TXT/DOCX evidence offsets, PII redaction, documented weighting, malformed provider output, and callback stop conditions.

~~~bash
git add back/python
git commit -m "feat(python): add guarded analysis service"
~~~

## Task 7: Java Lane - Upload, Task Orchestration, and Race-Safe Callback Persistence

**Files:**
- Create: back/java/src/main/resources/db/migration/V3__matching_tasks_results_and_evidence.sql
- Create: back/java/src/main/java/com/resumethinking/platform/matching/MatchTask.java
- Create: back/java/src/main/java/com/resumethinking/platform/matching/MatchTaskService.java
- Create: back/java/src/main/java/com/resumethinking/platform/matching/MatchTaskController.java
- Create: back/java/src/main/java/com/resumethinking/platform/matching/PythonAnalysisClient.java
- Create: back/java/src/main/java/com/resumethinking/platform/matching/InternalAnalysisCallbackController.java
- Test: back/java/src/test/java/com/resumethinking/platform/matching/MatchTaskServiceTest.java
- Test: back/java/src/test/java/com/resumethinking/platform/matching/InternalAnalysisCallbackControllerTest.java

**Interfaces:**
- Consumes: Tasks 4-6 and frozen fixtures.
- Produces: createTask(CreateMatchTaskCommand), getTask(UUID, UUID, UserRole), and acceptCallback(AnalysisCallbackRequest).

- [ ] **Step 1: Write failing idempotency and late-callback tests.**

~~~java
@Test
void duplicateSubmissionReturnsOriginalTaskForSameOwnerAndIdempotencyKey() {
    var first = taskService.createTask(command(userId, resumeId, profileId, "same-key-00000001"));
    var second = taskService.createTask(command(userId, resumeId, profileId, "same-key-00000001"));
    assertThat(second.id()).isEqualTo(first.id());
}

@Test
void callbackAfterArchiveReturnsTaskGoneAndDoesNotPersistResult() {
    task.markProcessing();
    lifecycleService.archiveDue(clock.instant().plus(Duration.ofDays(8)));
    var response = callbackController.accept(callbackFor(task, 1, "callback-1"));
    assertThat(response.code()).isEqualTo("TASK_GONE");
    assertThat(resultRepository.countByTaskId(task.getId())).isZero();
}
~~~

- [ ] **Step 2: Run focused orchestration tests.**

Run: back\java\mvnw.cmd -Dtest=MatchTaskServiceTest,InternalAnalysisCallbackControllerTest test

Expected: FAIL because task persistence and callbacks are absent.

- [ ] **Step 3: Implement V3 schema and creation checks.**

Store task ID, resume ID, resume lifecycle version, creator ID, idempotency key, attempt, callback token hash, task state, and optimistic lock. Store callback receipt callbackId and payload hash uniquely. Reject archived/deleted resumes and foreign model profiles with the same not-found behavior used for unknown resources.

- [ ] **Step 4: Dispatch a scoped internal request to Python.**

PythonAnalysisClient decrypts only in Java memory, creates a callback token, and sends the Task 1 payload to PYTHON_ANALYSIS_BASE_URL/internal/v1/analysis-jobs. It sends no user ID, host filesystem path, MySQL credential, or persistence command.

- [ ] **Step 5: Persist callbacks in one guarded transaction.**

~~~java
if (receiptRepository.existsByCallbackId(request.callbackId())) return acceptedReplay(request);
MatchTask task = taskRepository.lockById(request.taskId()).orElseThrow(TaskGoneException::new);
if (!task.accepts(request.attempt(), request.callbackToken(), resume.getVersion(), resume.getVisibilityState())) {
    throw new TaskGoneException();
}
validateEvidenceReferences(request.result(), resume.getId());
receiptRepository.save(CallbackReceipt.from(request));
resultRepository.save(AnalysisResult.from(request));
task.markSucceeded();
~~~

Return TASK_GONE for archived/soft-deleted work, STALE_ATTEMPT for old attempts, and IDEMPOTENCY_CONFLICT for changed payload under a reused callback ID.

- [ ] **Step 6: Run task tests and commit.**

Run: back\java\mvnw.cmd -Dtest=MatchTaskServiceTest,InternalAnalysisCallbackControllerTest test

Expected: PASS for duplicate submission, duplicate callback, stale attempt, archive/delete before callback, and no late-result recreation.

~~~bash
git add back/java/src/main back/java/src/test/java/com/resumethinking/platform/matching
git commit -m "feat(java): orchestrate matching tasks and callbacks"
~~~

## Task 8: Frontend Lane - Authentication and Model Profile UX

**Files:**
- Create: front/src/api/http.ts
- Create: front/src/api/contracts.ts
- Create: front/src/stores/auth.ts
- Create: front/src/stores/llmProfiles.ts
- Create: front/src/router/index.ts
- Create: front/src/views/LoginView.vue
- Create: front/src/views/RegisterView.vue
- Create: front/src/views/ModelProfilesView.vue
- Create: front/src/components/ModelProfileForm.vue
- Test: front/src/views/RegisterView.spec.ts
- Test: front/src/components/ModelProfileForm.spec.ts

**Interfaces:**
- Consumes: Task 1 public routes.
- Produces: authStore.register(), authStore.login(), llmProfileStore.create(), llmProfileStore.testConnection(), and route guards.

- [ ] **Step 1: Write failing role-selection and secret-re-display tests.**

~~~ts
it('submits selected ADMIN role during registration', async () => {
  const wrapper = mount(RegisterView, { global: { plugins: [pinia] } })
  await wrapper.get('[data-test="role-admin"]').setValue(true)
  await wrapper.get('[data-test="register-submit"]').trigger('click')
  expect(mockRegister).toHaveBeenCalledWith(expect.objectContaining({ role: 'ADMIN' }))
})

it('never renders a saved API key', async () => {
  const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
  expect(wrapper.text()).not.toContain('secret-api-key')
})
~~~

- [ ] **Step 2: Run the tests to verify the UI is absent.**

Run: pnpm --dir front exec vitest run src/views/RegisterView.spec.ts src/components/ModelProfileForm.spec.ts

Expected: FAIL because stores, views, and components do not exist.

- [ ] **Step 3: Implement typed HTTP and route guards.**

Make api/http.ts attach JWTs, convert only contract error envelopes into typed ApiError values, and clear auth after a confirmed authentication failure. Copy API types from OpenAPI into api/contracts.ts. Persist only safe identity metadata and token; never persist a profile key.

- [ ] **Step 4: Implement usable provider configuration.**

Use Element Plus validation, preset selection, custom endpoint input, model dropdown from the safe test response, manual model fallback, test button, default-profile control, and a password input that clears after save. Registration explains the actual role scope: an ADMIN can process recovery records across owners; it does not call this a demo-only mode. Do not render ciphertext, nonce, API key, or raw provider error text.

- [ ] **Step 5: Run frontend tests and build, then commit.**

Run: pnpm --dir front exec vitest run src/views/RegisterView.spec.ts src/components/ModelProfileForm.spec.ts

Run: pnpm --dir front run build

Expected: PASS without secrets in produced assets.

~~~bash
git add front/src front/package.json front/pnpm-lock.yaml
git commit -m "feat(web): add auth and model profile workflows"
~~~

## Task 9: Frontend Lane - Resume, Evidence, Deletion, Archive, and Recovery

**Files:**
- Create: front/src/views/ResumeListView.vue
- Create: front/src/views/UploadMatchView.vue
- Create: front/src/views/MatchResultView.vue
- Create: front/src/views/RecoveryView.vue
- Create: front/src/views/AdminRecoveryView.vue
- Create: front/src/components/DeleteResumeDialog.vue
- Create: front/src/components/MatchEvidenceTable.vue
- Create: front/src/components/RecoveryDialog.vue
- Test: front/src/components/DeleteResumeDialog.spec.ts
- Test: front/src/views/RecoveryView.spec.ts
- Test: front/src/views/AdminRecoveryView.spec.ts

**Interfaces:**
- Consumes: Task 1 lifecycle/task routes and Tasks 5/7 response shapes.
- Produces: upload, task polling, evidence display, user recovery, and administrator recovery flows.

- [ ] **Step 1: Write failing deletion and role-scoped recovery tests.**

~~~ts
it('keeps delete disabled until the exact confirmation phrase is entered', async () => {
  const wrapper = mount(DeleteResumeDialog, { props: { open: true, resumeId: 'r-1', version: 2 } })
  await wrapper.get('input').setValue('确认删除')
  expect(wrapper.get('[data-test="confirm-delete"]').attributes('disabled')).toBeDefined()
  await wrapper.get('input').setValue('确认删除简历')
  expect(wrapper.get('[data-test="confirm-delete"]').attributes('disabled')).toBeUndefined()
})

it('does not render another owner in the USER recovery list', async () => {
  const wrapper = mount(RecoveryView, { global: { plugins: [pinia] } })
  await flushPromises()
  expect(wrapper.text()).not.toContain('other-owner-resume')
})
~~~

- [ ] **Step 2: Run lifecycle UI tests and observe failure.**

Run: pnpm --dir front exec vitest run src/components/DeleteResumeDialog.spec.ts src/views/RecoveryView.spec.ts src/views/AdminRecoveryView.spec.ts

Expected: FAIL because lifecycle UI is absent.

- [ ] **Step 3: Implement upload and task-progress states.**

Accept only .txt and .docx. Show explicit PDF unsupported state. Require selected model profile and Java-backend job text. Send an idempotency key. Poll only while QUEUED or PROCESSING and show timeout, failure, archived, and no-data states without inventing results.

- [ ] **Step 4: Implement evidence-first matching UI.**

MatchEvidenceTable renders requirement text, requirement type, evidence excerpt/location, match state, score component, strength, and gap. Render RELATED_BUT_EVIDENCE_INSUFFICIENT and UNMET as non-positive. Render suggestion state separately and never present an unconfirmed fact as an applied change.

- [ ] **Step 5: Implement deletion and recovery controls.**

Send exactly { confirmationText: '确认删除简历', expectedVersion }. Remove an accepted deletion from the active list. USER recovery calls only user routes. ADMIN recovery calls administrator routes, includes owner context, and is role-guarded. Archive and soft-delete states disclose MySQL retention.

- [ ] **Step 6: Run tests/build and commit.**

Run: pnpm --dir front exec vitest run src/components/DeleteResumeDialog.spec.ts src/views/RecoveryView.spec.ts src/views/AdminRecoveryView.spec.ts

Run: pnpm --dir front run build

Expected: PASS and responsive layouts with no clipped confirmation controls.

~~~bash
git add front/src
git commit -m "feat(web): add matching lifecycle and recovery views"
~~~

## Task 10: Cross-Service Integration and Controlled End-to-End Fixture

**Files:**
- Create: tests/integration/run_mvp_flow.ps1
- Create: tests/integration/fixtures/java-backend-job.txt
- Create: tests/integration/fixtures/student-resume.txt
- Create: tests/integration/fixtures/student-resume.docx
- Create: tests/integration/fixtures/invalid-resume.pdf
- Create: tests/integration/assert_mvp_flow.py
- Create: front/e2e/resume-lifecycle.spec.ts
- Modify: README.md

**Interfaces:**
- Consumes: real services from Tasks 4-9 and fake provider from Task 6.
- Produces: repeatable proof of one controlled resume/job flow.

- [ ] **Step 1: Write the end-to-end assertion before service wiring.**

~~~python
from pathlib import Path
from docx import Document

resume_text = "Java developer\nSpring Boot\nMySQL\nRedis\nBuilt a REST API"
Path("tests/integration/fixtures/student-resume.txt").write_text(resume_text, encoding="utf-8")
Path("tests/integration/fixtures/java-backend-job.txt").write_text(
    "Java backend developer. Required: Java, Spring Boot, MySQL. Preferred: Redis.", encoding="utf-8"
)
doc = Document()
doc.add_paragraph(resume_text)
doc.save("tests/integration/fixtures/student-resume.docx")
Path("tests/integration/fixtures/invalid-resume.pdf").write_bytes(b"not a valid PDF")

def test_match_result_binds_requirements_to_existing_evidence(api: ApiClient) -> None:
    result = api.wait_for_result(api.create_match_task())
    assert result["state"] == "SUCCEEDED"
    assert all(item["jobRequirementText"] for item in result["requirements"])
    assert all(item["evidence"][0]["sourceOffset"] >= 0
               for item in result["requirements"] if item["evidence"])
~~~

- [ ] **Step 2: Start controlled dependencies and verify health endpoints.**

Run: docker compose -f docker-compose.redis.yml up -d

Start native services as hidden PowerShell processes:

~~~powershell
$envFile = Get-Content -LiteralPath '.env' | Where-Object { $_ -match '^[A-Z0-9_]+=' }
foreach ($line in $envFile) {
  $name, $value = $line -split '=', 2
  Set-Item -Path ("Env:" + $name) -Value $value
}
$java = Start-Process -FilePath '.\mvnw.cmd' -ArgumentList 'spring-boot:run', '-Dspring-boot.run.profiles=local' -WorkingDirectory 'back\java' -WindowStyle Hidden -PassThru
$python = Start-Process -FilePath 'C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe' -ArgumentList '-m', 'uvicorn', 'app.main:app', '--app-dir', 'back/python', '--port', '8000' -WorkingDirectory '.' -WindowStyle Hidden -PassThru
foreach ($url in @('http://127.0.0.1:8080/actuator/health', 'http://127.0.0.1:8000/health')) {
  $ready = $false
  for ($attempt = 1; $attempt -le 60 -and -not $ready; $attempt++) {
    try { $ready = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 $url).StatusCode -eq 200 } catch { Start-Sleep -Seconds 1 }
  }
  if (-not $ready) { throw "Service did not become healthy: $url" }
}
~~~

Expected: Java health, Python health, Flyway migration, and Redis ping succeed before submissions.

- [ ] **Step 3: Implement sanitized startup/orchestration script.**

The script validates required environment variables, starts fake provider, registers users, creates profiles, uploads fixtures, starts a match task, waits with a fixed deadline, prints only IDs/statuses, and stops fake provider in finally. It never prints resume text, JWTs, API keys, or callback tokens.

- [ ] **Step 4: Add race and archival checks.**

Pause fake provider before callback, soft-delete the resume, release callback, and assert TASK_GONE, zero result rows, no Redis result key, and no user-visible resume. Repeat with duplicate callback and stale attempt. Inject a Clock bean: production uses Clock.systemUTC(), while the integration profile injects a mutable fixed clock that advances seven or thirty days before calling ArchiveScheduler. Assert explicit restore is the only path back to ACTIVE.

- [ ] **Step 5: Run complete controlled flow and commit.**

Run: powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1

Run: C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q

Expected: real Java/Python handoff, lifecycle gates, and deterministic fake-provider match all pass.

~~~bash
git add tests/integration front/e2e README.md
git commit -m "test: add controlled cross-service MVP flow"
~~~

## Task 11: Security, Visual, and Release Evidence Gate

**Files:**
- Create: docs/verification/mvp-evidence.md
- Create: docs/verification/log-scan-patterns.txt
- Modify: back/java/src/test/java/com/resumethinking/platform/resumes/ResumeControllerTest.java
- Create: back/python/tests/test_log_safety.py
- Modify: README.md

**Interfaces:**
- Consumes: all prior implementation and tests.
- Produces: reproducible evidence separating verified behavior, deterministic simulation, known risk, and excluded scope.

- [ ] **Step 1: Add failing authorization and log-leak tests.**

~~~java
@Test
void guessedForeignResumeIdAndUnknownIdShareTheSameNotFoundEnvelope() throws Exception {
    UUID foreignResumeId = fixtures.createActiveResume(otherUserId).id();
    String userToken = fixtures.jwtFor(userId, UserRole.USER);
    mockMvc.perform(get("/api/v1/resumes/{id}", foreignResumeId).header("Authorization", userToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
}
~~~

~~~python
from pathlib import Path

def test_log_scan_has_no_fixture_email_phone_or_api_key() -> None:
    text = Path("build/test.log").read_text(encoding="utf-8")
    assert "student@example.test" not in text
    assert "13800138000" not in text
    assert "fake-api-key" not in text
~~~

- [ ] **Step 2: Run every contract, unit, integration, and frontend check from a clean service state.**

Run: pnpm --dir contracts run lint && pnpm --dir contracts run validate

Run: back\java\mvnw.cmd test

Run: C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests tests/integration -q

Run: pnpm --dir front exec vitest run && pnpm --dir front run build

Expected: every command exits zero and evidence records sanitized fixture names and results.

- [ ] **Step 3: Run visual Playwright checks.**

Run: pnpm --dir front exec playwright test e2e/resume-lifecycle.spec.ts --project=chromium

Capture desktop/mobile screenshots for role registration, model configuration, upload, pending task, evidence result, delete confirmation, archived no-data, user recovery, and administrator recovery. Check blank screens, overlap, clipping, hidden confirm button, and secret values in the DOM.

- [ ] **Step 4: Write evidence report and commit.**

The report includes commands/results, fixture coverage, lifecycle races, cache policy, fake-provider scope, public-admin-role risk, MySQL retention policy, and excluded features. It does not claim production readiness, fairness, or real-provider quality.

~~~bash
git add docs/verification README.md
git commit -m "docs: record MVP verification evidence"
~~~

## Plan Self-Review

### Spec Coverage

- JDK 21, hybrid native runtime, Redis-only Docker, Java-backend-only scope, TXT/DOCX, and PDF rejection are implemented by Tasks 3, 5, 6, 9, and 10.
- Selectable registration roles, JWT, encrypted per-user model profiles, safe endpoints, and secret non-re-display are implemented by Tasks 4 and 8.
- MySQL retention, status, visibility lifecycle, archive dates, Redis cleanup, and user/admin recovery are implemented by Tasks 5 and 9.
- Redaction, evidence-backed fixed weighting, OpenAI-compatible calls, fact classification, and model failure are implemented by Tasks 6 and 7.
- Contract freeze, fixtures, callback idempotency, race handling, and cross-service proof are implemented by Tasks 1, 2, 7, and 10.
- Authorization, PII/log safeguards, visual checks, and residual-risk reporting are implemented by Task 11.

### Completeness Scan

The plan contains exact files, interfaces, test commands, expected results, and commit commands for every task. No unassigned implementation item remains.

### Type Consistency

The contract establishes UUID IDs, expectedVersion, status, visibilityState, task attempt, callbackId, and callbackToken. Java, Python, and frontend tasks use those names consistently and copy API shapes from the frozen contract.
