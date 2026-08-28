# 简历匹配平台 MVP 实施计划

> **面向智能体执行者：** 必须使用
> `superpowers:subagent-driven-development`（推荐）或
> `superpowers:executing-plans`，按任务逐项实施本计划。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 交付首个可运行、以证据为依据的 Java 后端简历与岗位匹配功能切片，包含
每用户的 OpenAI 兼容模型配置、按角色控制的生命周期，以及 Redis 归档恢复。

**架构：** Vue 3 是操作客户端。运行在 JDK 21 上的 Spring Boot 3 是唯一的公共 API、
授权、编排和持久化权威。Python 3.11 上的 FastAPI 负责提取、脱敏、匹配和受控模型调用。
MySQL 保存加密的持久记录，以及权威的任务进度、幂等键、回调凭据和结果；本 MVP 中 Redis
只保存派生的 `resume:view:{resumeId}` 页面缓存。所有 MySQL/Redis 写入（包括 Python 回调）
均由 Java 负责。Redis 任务进度/幂等处理留到后续切片。

**技术栈：** Vue 3、TypeScript、Vite、Element Plus、Pinia、Vitest、Playwright、
Spring Boot 3、Java 21、Maven Wrapper、Spring Security、JPA、Flyway、MySQL 8.4、
Redis 7（Docker 容器）、FastAPI、Pydantic、httpx、python-docx、pytest、OpenAPI、
JSON Schema。

**规范：** docs/superpowers/specs/2026-08-27-resume-matching-platform-design.md

## 全局约束

- 本切片只包含 Java 后端岗位族。支持 TXT 和 DOCX；PDF 返回 UNSUPPORTED_FILE。
- 使用 JDK 21 编译和运行 Java。Python 只使用已安装的 Python 3.11 解释器。
- Java 是唯一的公共业务、授权、任务状态、MySQL 和 Redis 权威。Python 没有用户登录，
  也不能直接写入 MySQL/Redis。
- 在并行实施前冻结 OpenAPI、内部回调模式、生命周期状态、错误信封和测试样例。
- 将简历、岗位文本、模型服务输出和用户配置视为不可信。外部调用前先脱敏，且不要记录
  敏感信息或完整简历文本。
- `resumes.status=0` 表示未软删除；`resumes.status=1` 表示软删除成功。`visibility_state`
  记录 `ACTIVE`、`USER_SOFT_DELETED`、`ADMIN_SOFT_DELETED`、`USER_CACHE_ARCHIVED` 或
  `ADMIN_CACHE_ARCHIVED`。
- MySQL 会保留记录，直到授权运维人员物理删除。七天和三十天策略只删除 Redis 键
  并归档页面可见性。
- `USER` 只能恢复自己的符合条件的简历。`ADMIN` 可恢复每个所有者的符合条件简历。管理员
  软删除对普通所有者不可见且不可恢复。
- 按产品决策，公开注册允许 USER 和 ADMIN 角色。继续执行所有服务器端授权检查，并记录
  全局访问风险。
- 匹配严格使用 0.40 技能、0.25 项目经历、0.15 工作内容、0.10
  教育/经历和 0.10 软技能。
- 每个用户拥有 OpenAI 兼容配置。API 密钥必须加密持久化，绝不返回或记录。
- 契约相关工作按顺序执行。契约冻结后，只能在隔离 Git 工作树中让智能体处理所有权
  不重叠的 Java、Python 和前端工作。

---

## 执行前置条件

- 任务 1 开始在隔离工作树实施前，先使用 `using-git-worktrees`。
- 启动 Redis 容器前先启动 Docker Desktop。
- 在运行 Flyway 前取得已授权的 MySQL URL、数据库名、用户名和密码。只将值
  存在未跟踪的 `.env` 文件中。
- 在本地生成 base64 AES-GCM master key 和 JWT signing key；两者都不得提交。
- 自动化测试使用本地模拟 OpenAI 兼容服务。真实模型服务凭据只通过完成后的 UI 输入。

## 计划的文件结构

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

## 任务 1：契约负责人 - 冻结公共 API 与生命周期

**文件：**
- 新建：contracts/README.md
- 新建：contracts/package.json
- 新建：contracts/openapi/v1/openapi.yaml
- 新建：contracts/internal/v1/analysis-job.schema.json
- 新建：contracts/internal/v1/analysis-callback.schema.json

**接口：**
- 输入：已批准的设计规范。
- 输出：权威的公共 Java API 契约与 Java 到 Python 的交接契约。

- [ ] **步骤 1：在任何接口之前定义错误和状态模式。**

~~~yaml
ApiError:
  type: object
  required: [code, message, correlationId, retryable]
  properties:
    code: { type: string, example: RESUME_ARCHIVED }
    message: { type: string, example: 简历已归档，请先恢复。 }
    correlationId: { type: string, format: uuid }
    retryable: { type: boolean }
VisibilityState:
  type: string
  enum: [ACTIVE, USER_SOFT_DELETED, ADMIN_SOFT_DELETED, USER_CACHE_ARCHIVED, ADMIN_CACHE_ARCHIVED]
TaskState:
  type: string
  enum: [QUEUED, PROCESSING, SUCCEEDED, FAILED, TIMED_OUT, BLOCKED]
~~~

- [ ] **步骤 2：定义精确的公共路由和所有权规则。**

创建以下路由：POST /api/v1/auth/register、POST /api/v1/auth/login、GET
/api/v1/auth/me、CRUD /api/v1/llm-profiles、POST /api/v1/llm-profiles/{profileId}/test、
POST /api/v1/resumes、GET /api/v1/resumes、GET /api/v1/resumes/{resumeId}、DELETE
/api/v1/resumes/{resumeId}、GET/POST 恢复路由、POST /api/v1/match-tasks、GET
/api/v1/match-tasks/{taskId}、GET /api/v1/match-tasks/{taskId}/result，以及管理员
恢复/删除/还原路由。

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

每个受保护路径都声明 Bearer 认证；未知 ID 和外部 ID 必须返回相同的
RESOURCE_NOT_FOUND 错误信封。

- [ ] **步骤 3：定义不含用户身份的内部分析任务和回调负载。**

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

回调模式要求 taskId、attempt（尝试次数）、callbackId、callbackToken，以及指向任务中所提供证据的
证据 ID。它禁止 ownerId、数据库凭据、明文密码和任意持久化字段。

- [ ] **步骤 4：在 contracts/README.md 中记录状态转换和错误代码。**

记录 `QUEUED -> PROCESSING -> SUCCEEDED|FAILED|TIMED_OUT`，以及软删除或归档后任意活动
任务 -> `BLOCKED`。记录 `ACTIVE -> USER_SOFT_DELETED|ADMIN_SOFT_DELETED|USER_CACHE_ARCHIVED|ADMIN_CACHE_ARCHIVED`，并记录受角色限制的恢复到 `ACTIVE`。定义 `TASK_GONE`、`STALE_ATTEMPT`、`IDEMPOTENCY_CONFLICT`、`RESUME_ARCHIVED`、`RESUME_SOFT_DELETED`、`RESOURCE_NOT_FOUND`、`MODEL_UNAVAILABLE`、`MODEL_OUTPUT_INVALID`、`MODEL_ENDPOINT_REJECTED` 和 `UNSUPPORTED_FILE`。

- [ ] **步骤 5：创建契约检查工具包，执行契约检查并提交。**

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

运行：pnpm --dir contracts install --frozen-lockfile=false

运行：pnpm --dir contracts exec redocly lint openapi/v1/openapi.yaml

预期：没有 OpenAPI 错误。

~~~bash
git add contracts/README.md contracts/package.json contracts/pnpm-lock.yaml contracts/openapi/v1/openapi.yaml contracts/internal/v1
git commit -m "feat(contracts): freeze v1 API and analysis handoff"
~~~

## 任务 2：契约负责人 - 测试样例矩阵与验证工具

**文件：**
- 新建：contracts/scripts/validate-contracts.mjs
- 新建：contracts/fixtures/v1/auth-register-valid.json
- 新建：contracts/fixtures/v1/llm-profile-valid.json
- 新建：contracts/fixtures/v1/match-request-valid.json
- 新建：contracts/fixtures/v1/match-request-invalid.json
- 新建：contracts/fixtures/v1/callback-valid.json
- 新建：contracts/fixtures/v1/callback-duplicate.json
- 新建：contracts/fixtures/v1/callback-stale.json
- 新建：contracts/fixtures/v1/callback-after-soft-delete.json
- 新建：contracts/fixtures/v1/archive-user.json
- 新建：contracts/fixtures/v1/archive-admin.json
- 新建：contracts/fixtures/v1/error-envelope.json

**接口：**
- 输入：任务 1 的模式。
- 输出：Java 和 Python 测试可直接使用、无需重新解释字段的测试样例。

- [ ] **步骤 1：向任务 1 的工具包添加校验脚本。**

~~~json
{
  "scripts": {
    "lint": "redocly lint openapi/v1/openapi.yaml",
    "validate": "node scripts/validate-contracts.mjs"
  }
}
~~~

将这些脚本合并到现有任务 1 的工具包，不要改变其固定依赖；然后运行
pnpm --dir contracts install，使锁文件与脚本保持一致。

- [ ] **步骤 2：在校验器之前编写预期无效的匹配请求。**

~~~json
{
  "resumeId": "not-a-uuid",
  "llmProfileId": "not-a-uuid",
  "jobDescriptionText": "",
  "idempotencyKey": ""
}
~~~

让 callback-duplicate 复用 callback-valid 的 `callbackId` 和 `payloadHash`（负载哈希）；让 callback-stale
使用较早的 attempt（尝试次数）；让 callback-after-soft-delete 在结构上有效，但由 Java 按语义拒绝。

- [ ] **步骤 3：编写测试样例校验器，并确保无效样例失败。**

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

扩展脚本，断言已知有效测试样例通过，并确认 match-request-invalid 被匹配请求模式拒绝。

- [ ] **步骤 4：安装并运行契约校验。**

运行：pnpm --dir contracts install --frozen-lockfile=false

运行：pnpm --dir contracts run lint && pnpm --dir contracts run validate

预期：每个有效测试样例都通过，校验器按预期报告无效测试样例被拒绝。

- [ ] **步骤 5：提交测试样例基线。**

~~~bash
git add contracts/package.json contracts/pnpm-lock.yaml contracts/scripts contracts/fixtures
git commit -m "test(contracts): add v1 fixture matrix"
~~~

## 任务 3：初始化混合运行环境与本地配置

**文件：**
- 新建：.gitignore
- 新建：.env.example
- 新建：docker-compose.redis.yml
- 新建：README.md
- 新建：back/java Spring Boot Maven Wrapper 项目
- 新建：back/java/src/main/resources/application.yml
- 新建：back/java/src/main/resources/application-local.yml.example
- 新建：back/python/pyproject.toml
- 新建：back/python/app/__init__.py
- 新建：back/python/app/main.py
- 新建：back/python/tests/test_health.py
- 新建：front Vue 3 TypeScript Vite 项目

**接口：**
- 输入：已冻结的契约。
- 输出：可构建的 Java、Python、Vue 项目根目录，以及仅含 Redis 的容器定义。

- [ ] **步骤 1：添加密钥和生成文件排除规则。**

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

- [ ] **步骤 2：添加非敏感环境变量模板。**

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

- [ ] **步骤 3：定义并校验仅含 Redis 的 Compose 文件。**

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

运行：docker compose -f docker-compose.redis.yml config

预期：恰好包含一个 Redis 服务和一个命名卷。

- [ ] **步骤 4：创建兼容的 Java、Python 和前端项目骨架。**

在仓库根目录执行以下完全相同的非交互式 Java 初始化：

~~~powershell
$bootstrapRoot = Join-Path $env:TEMP ('resume-platform-' + [guid]::NewGuid())
$bootstrapZip = Join-Path $bootstrapRoot 'java.zip'
New-Item -ItemType Directory -Path $bootstrapRoot | Out-Null
Invoke-WebRequest -Uri 'https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=3.4.3&baseDir=resume-platform-api&groupId=com.resumethinking&artifactId=resume-platform-api&name=resume-platform-api&packageName=com.resumethinking.platform&javaVersion=21&dependencies=web,validation,data-jpa,security,data-redis,mysql,flyway,actuator' -OutFile $bootstrapZip
Expand-Archive -LiteralPath $bootstrapZip -DestinationPath $bootstrapRoot
New-Item -ItemType Directory -Force -Path 'back' | Out-Null
Move-Item -LiteralPath (Join-Path $bootstrapRoot 'resume-platform-api') -Destination 'back\java'
~~~

使用以下命令生成 Vue 项目骨架：

运行：pnpm create vite front --template vue-ts --no-interactive

运行：pnpm --dir front add axios element-plus pinia vue-router

运行：pnpm --dir front add -D vitest jsdom @testing-library/vue @playwright/test

创建 back/python/pyproject.toml：

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

创建初始 Python 健康检查接口及其一个通过的测试：

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

创建 application-local.yml.example，其中只放置环境变量形式的连接设置：

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

- [ ] **步骤 5：对每个项目根目录运行最小构建检查。**

运行：back\java\mvnw.cmd -q -DskipTests compile

运行：C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pip install -e "back/python[test]"

运行：C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q

运行：pnpm --dir front install && pnpm --dir front run build

预期：Java 和前端构建成功；Python 报告一个通过的健康检查测试。

- [ ] **步骤 6：在不包含本地密钥的情况下提交初始化文件。**

~~~bash
git add .gitignore .env.example docker-compose.redis.yml README.md back/java back/python front
git commit -m "chore: bootstrap hybrid application runtime"
~~~

## 任务 4：Java 工作流 - 身份、加密与模型配置所有权

**文件：**
- 新建：back/java/src/main/resources/db/migration/V1__users_and_llm_profiles.sql
- 新建：back/java/src/main/java/com/resumethinking/platform/auth/User.java
- 新建：back/java/src/main/java/com/resumethinking/platform/auth/UserRole.java
- 新建：back/java/src/main/java/com/resumethinking/platform/auth/AuthService.java
- 新建：back/java/src/main/java/com/resumethinking/platform/auth/AuthController.java
- 新建：back/java/src/main/java/com/resumethinking/platform/auth/JwtService.java
- 新建：back/java/src/main/java/com/resumethinking/platform/config/SecurityConfig.java
- 新建：back/java/src/main/java/com/resumethinking/platform/crypto/AesGcmCryptoService.java
- 新建：back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileService.java
- 新建：back/java/src/main/java/com/resumethinking/platform/profiles/LlmProfileController.java
- 测试：back/java/src/test/java/com/resumethinking/platform/auth/AuthServiceTest.java
- 测试：back/java/src/test/java/com/resumethinking/platform/profiles/LlmProfileServiceTest.java

**接口：**
- 输入：任务 1 的认证/配置路由和任务 2 的测试样例。
- 输出：AuthService.register(RegisterCommand)、AuthService.login(LoginCommand)、
  LlmProfileService.create(UUID, CreateLlmProfileCommand) 和
  LlmProfileService.decryptForDispatch(UUID, UUID)。

- [ ] **步骤 1：编写失败的身份和配置测试。**

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

- [ ] **步骤 2：运行测试，证明该层尚不存在。**

运行：back\java\mvnw.cmd -Dtest=AuthServiceTest,LlmProfileServiceTest test

预期：FAIL，因为服务和由迁移支持的实体尚不存在。

- [ ] **步骤 3：实现 V1 模式、BCrypt/JWT、AES-GCM 和配置服务。**

使用唯一的用户名/邮箱、bcrypt 密码哈希和 JWT 角色声明。模型配置
保存 owner_id、接口 URL、模型名称、密文、随机数、密钥版本和 `selected`
标记。AesGcmCryptoService 使用 base64 编码的 256 位应用密钥，每次加密使用新的
12 字节随机数，并启用 GCM 完整性校验。

~~~java
public record DispatchLlmProfile(URI baseUrl, String model, String apiKey) {}

public DispatchLlmProfile decryptForDispatch(UUID actorId, UUID profileId) {
    LlmProfile profile = repository.findByIdAndOwnerId(profileId, actorId)
        .orElseThrow(ResourceNotFoundException::new);
    return new DispatchLlmProfile(URI.create(profile.getBaseUrl()), profile.getModel(),
        crypto.decrypt(profile.getCiphertext(), profile.getNonce()));
}
~~~

- [ ] **步骤 4：实现接口地址校验和有界配置测试。**

生产环境拒绝非 HTTPS 的自定义 URL 和私有/保留目标。仅当
app.allow-local-model-endpoints=true 时允许 http://127.0.0.1。测试接口使用固定
连接/读取超时调用 /models，并且只返回清理后的状态和模型名称。

- [ ] **步骤 5：运行定向测试并提交。**

运行：back\java\mvnw.cmd -Dtest=AuthServiceTest,LlmProfileServiceTest test

预期：通过，且断言输出或日志中不包含密码或 API 密钥。

~~~bash
git add back/java/pom.xml back/java/src/main back/java/src/test/java/com/resumethinking/platform/auth back/java/src/test/java/com/resumethinking/platform/profiles
git commit -m "feat(java): add auth and encrypted model profiles"
~~~

## 任务 5：Java 工作流 - 简历生命周期、Redis 归档与恢复

**文件：**
- 新建：back/java/src/main/resources/db/migration/V2__resumes_and_lifecycle.sql
- 新建：back/java/src/main/java/com/resumethinking/platform/resumes/Resume.java
- 新建：back/java/src/main/java/com/resumethinking/platform/resumes/VisibilityState.java
- 新建：back/java/src/main/java/com/resumethinking/platform/resumes/ResumeLifecycleService.java
- 新建：back/java/src/main/java/com/resumethinking/platform/resumes/ArchiveScheduler.java
- 新建：back/java/src/main/java/com/resumethinking/platform/resumes/ResumeController.java
- 新建：back/java/src/main/java/com/resumethinking/platform/config/RedisConfig.java
- 测试：back/java/src/test/java/com/resumethinking/platform/resumes/ResumeLifecycleServiceTest.java
- 测试：back/java/src/test/java/com/resumethinking/platform/resumes/ArchiveSchedulerTest.java
- 新建：back/java/src/test/java/com/resumethinking/platform/resumes/ResumeControllerTest.java

**接口：**
- 输入：任务 4 的操作者身份和任务 1 的生命周期契约。
- 输出：softDelete(DeleteResumeCommand)、recover(UUID, UUID, UserRole, long)、
  archiveDue(Instant)，以及 resume:view:{resumeId} 下的 Redis 键。

- [ ] **步骤 1：编写失败的生命周期测试。**

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

- [ ] **步骤 2：运行测试，验证生命周期行为尚不存在。**

运行：back\java\mvnw.cmd -Dtest=ResumeLifecycleServiceTest,ArchiveSchedulerTest test

预期：失败，因为简历状态转换和缓存适配器尚不存在。

- [ ] **步骤 3：实现 V2 持久化和单一事务型生命周期服务。**

添加所有者 ID、标题、来源类型、加密原始内容、status、visibility_state、
visible_until、soft_deleted_by、soft_deleted_at、archived_at、restored_at 和 JPA version。
在创建/恢复时，根据创建者角色设置 visible_until：USER 为七天，ADMIN
为三十天。

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

- [ ] **步骤 4：实现持久化归档，替代 Redis 键空间事件逻辑。**

每分钟分页处理 visible_until 已到期的活动记录。根据创建者角色将每条记录转换为
USER_CACHE_ARCHIVED 或 ADMIN_CACHE_ARCHIVED，并清除全部简历缓存键。普通
读取/列表路由排除非 ACTIVE 记录。恢复路由使用带索引、按所有者/角色限定的
SQL 查询，绝不物理删除 MySQL 行。

- [ ] **步骤 5：添加控制器测试样例并运行定向检查。**

测试普通用户删除/恢复、管理员删除/恢复、管理员删除后的所有者拒绝、固定时钟推进后的
归档、重复删除和重复恢复。断言外部猜测的简历 ID 与未知简历 ID 返回相同的
RESOURCE_NOT_FOUND 错误信封。

运行：back\java\mvnw.cmd -Dtest=ResumeLifecycleServiceTest,ArchiveSchedulerTest test

预期：通过，且日志中不包含原始简历文本。

- [ ] **步骤 6：提交生命周期工作流。**

~~~bash
git add back/java/src/main back/java/src/test/java/com/resumethinking/platform/resumes
git commit -m "feat(java): add resume lifecycle and archival recovery"
~~~

## 任务 6：Python 工作流 - 提取、脱敏、匹配与受保护的模型调用

**文件：**
- 新建：back/python/app/settings.py
- 新建：back/python/app/models.py
- 新建：back/python/app/redaction.py
- 新建：back/python/app/extraction.py
- 新建：back/python/app/matching.py
- 新建：back/python/app/openai_compatible.py
- 新建：back/python/app/analysis_service.py
- 新建：back/python/app/callback_client.py
- 修改：back/python/app/main.py
- 新建：back/python/tests/test_redaction.py
- 新建：back/python/tests/test_extraction.py
- 新建：back/python/tests/test_matching.py
- 新建：back/python/tests/test_openai_compatible.py
- 新建：back/python/tests/test_analysis_service.py
- 新建：back/python/test_support/fake_openai_server.py

**接口：**
- 输入：任务 1 的内部模式和任务 2 的测试样例。
- 输出：POST /internal/v1/analysis-jobs、redact_text(text)、
  extract_resume(source_type, bytes) 和 analyze_job(job)。

- [ ] **步骤 1：编写失败的脱敏、提取、评分和无效模型测试。**

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

- [ ] **步骤 2：运行测试，证明该服务尚不存在。**

运行：C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q

预期：在收集阶段失败，因为模块尚不存在。

- [ ] **步骤 3：在模型适配器之前实现确定性的文本提取和脱敏。**

TXT 按文档化的替换策略解码 UTF-8。DOCX 使用 python-docx 段落和稳定的
段落/字符偏移。以保守方式检测邮箱、电话、身份证号和地址模式。返回脱敏文本及替换元数据。
除 TXT/DOCX 外的所有来源类型均以 UNSUPPORTED_FILE 拒绝。

~~~python
def composite_score(*, skills: float, projects: float, work_content: float,
                    education_experience: float, soft_skills: float) -> float:
    return round(0.40 * skills + 0.25 * projects + 0.15 * work_content
                 + 0.10 * education_experience + 0.10 * soft_skills, 4)
~~~

- [ ] **步骤 4：使用严格的 Pydantic 校验实现 OpenAI 兼容适配器。**

使用固定的连接/读取超时，仅向 {base_url}/chat/completions 发送脱敏数据。
校验岗位要求 ID、证据 ID、匹配状态、分项得分、证据强度、差距和建议分类。不要记录 HTTP
请求头、API 密钥、请求内容或原始模型服务响应。将超时转换为 MODEL_UNAVAILABLE，将无效
JSON/模式转换为
MODEL_OUTPUT_INVALID。

- [ ] **步骤 5：实现回调重试行为。**

FastAPI 校验内部任务，启动后台工作，并严格发送契约规定的回调字段。
对传输失败或 5xx 响应使用相同 callbackId 重试。收到 TASK_GONE、
STALE_ATTEMPT、IDEMPOTENCY_CONFLICT 或成功响应时停止。写入日志前剥离回调响应正文。

- [ ] **步骤 6：运行 Python 检查并提交该工作流。**

运行：C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q

预期：TXT/DOCX 证据偏移、个人信息脱敏、文档规定的权重、错误的模型服务输出和回调停止
条件均通过。

~~~bash
git add back/python
git commit -m "feat(python): add guarded analysis service"
~~~

## 任务 7：Java 工作流 - 上传、任务编排与竞态安全的回调持久化

**文件：**
- 新建：back/java/src/main/resources/db/migration/V3__matching_tasks_results_and_evidence.sql
- 新建：back/java/src/main/java/com/resumethinking/platform/matching/MatchTask.java
- 新建：back/java/src/main/java/com/resumethinking/platform/matching/MatchTaskService.java
- 新建：back/java/src/main/java/com/resumethinking/platform/matching/MatchTaskController.java
- 新建：back/java/src/main/java/com/resumethinking/platform/matching/PythonAnalysisClient.java
- 新建：back/java/src/main/java/com/resumethinking/platform/matching/InternalAnalysisCallbackController.java
- 测试：back/java/src/test/java/com/resumethinking/platform/matching/MatchTaskServiceTest.java
- 测试：back/java/src/test/java/com/resumethinking/platform/matching/InternalAnalysisCallbackControllerTest.java

**接口：**
- 输入：任务 4-6 和已冻结的测试样例。
- 输出：createTask(CreateMatchTaskCommand)、getTask(UUID, UUID, UserRole) 和
  acceptCallback(AnalysisCallbackRequest)。

- [ ] **步骤 1：编写失败的幂等性和迟到回调测试。**

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

- [ ] **步骤 2：运行定向任务编排测试。**

运行：back\java\mvnw.cmd -Dtest=MatchTaskServiceTest,InternalAnalysisCallbackControllerTest test

预期：失败，因为任务持久化和回调尚不存在。

- [ ] **步骤 3：实现 V3 模式和创建校验。**

保存任务 ID、简历 ID、简历生命周期版本、创建者 ID、幂等键、尝试次数、
回调令牌哈希、任务状态和乐观锁。对回调凭据的 `callbackId` 和 `payloadHash`（负载哈希）建立唯一保存约束。
已归档/已删除的简历和其他所有者的模型配置，使用与未知资源相同的未找到行为拒绝。

- [ ] **步骤 4：向 Python 派发范围受限的内部请求。**

PythonAnalysisClient 只在 Java 内存中解密，创建回调令牌，并将任务 1 的负载
发送到 PYTHON_ANALYSIS_BASE_URL/internal/v1/analysis-jobs。它不发送用户 ID、主机文件系统
路径、MySQL 凭据或持久化命令。

- [ ] **步骤 5：在受保护事务中持久化回调。**

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

已归档/软删除的任务返回 TASK_GONE，旧尝试返回 STALE_ATTEMPT，复用 callback
ID 但负载改变返回 IDEMPOTENCY_CONFLICT。

- [ ] **步骤 6：运行任务测试并提交。**

运行：back\java\mvnw.cmd -Dtest=MatchTaskServiceTest,InternalAnalysisCallbackControllerTest test

预期：重复提交、重复回调、过期尝试、回调前归档/删除以及不重新创建迟到结果均通过。

~~~bash
git add back/java/src/main back/java/src/test/java/com/resumethinking/platform/matching
git commit -m "feat(java): orchestrate matching tasks and callbacks"
~~~

## 任务 8：前端工作流 - 身份认证与模型配置界面

**文件：**
- 新建：front/src/api/http.ts
- 新建：front/src/api/contracts.ts
- 新建：front/src/stores/auth.ts
- 新建：front/src/stores/llmProfiles.ts
- 新建：front/src/router/index.ts
- 新建：front/src/views/LoginView.vue
- 新建：front/src/views/RegisterView.vue
- 新建：front/src/views/ModelProfilesView.vue
- 新建：front/src/components/ModelProfileForm.vue
- 测试：front/src/views/RegisterView.spec.ts
- 测试：front/src/components/ModelProfileForm.spec.ts

**接口：**
- 输入：任务 1 的公共路由。
- 输出：authStore.register()、authStore.login()、llmProfileStore.create()、
  llmProfileStore.testConnection() 和路由守卫。

- [ ] **步骤 1：编写失败的角色选择和密钥不回显测试。**

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

- [ ] **步骤 2：运行测试，验证 UI 尚不存在。**

运行：pnpm --dir front exec vitest run src/views/RegisterView.spec.ts src/components/ModelProfileForm.spec.ts

预期：失败，因为存储、视图和组件尚不存在。

- [ ] **步骤 3：实现类型化 HTTP 和路由守卫。**

让 api/http.ts 附加 JWT，将契约错误信封转换为类型化 ApiError，并在确认认证失败后清除登录
状态。将 API 类型从 OpenAPI 复制到 api/contracts.ts。只持久化安全的身份元数据和 token；
绝不持久化模型配置密钥。

- [ ] **步骤 4：实现可用的模型服务配置。**

使用 Element Plus 校验、预设选择、自定义接口地址输入、来自安全测试响应的模型下拉框、
手动模型备用输入、测试按钮、默认配置控件，以及保存后会清空的密码输入框。注册页面说明
实际角色范围：ADMIN 可以处理跨所有者的恢复记录；不将其称为仅演示模式。不要渲染
密文、随机数、API 密钥或原始模型服务错误文本。

- [ ] **步骤 5：运行前端测试和构建，然后提交。**

运行：pnpm --dir front exec vitest run src/views/RegisterView.spec.ts src/components/ModelProfileForm.spec.ts

运行：pnpm --dir front run build

预期：通过，且生成的构建资源中不含敏感信息。

~~~bash
git add front/src front/package.json front/pnpm-lock.yaml
git commit -m "feat(web): add auth and model profile workflows"
~~~

## 任务 9：前端工作流 - 简历、证据、删除、归档与恢复

**文件：**
- 新建：front/src/views/ResumeListView.vue
- 新建：front/src/views/UploadMatchView.vue
- 新建：front/src/views/MatchResultView.vue
- 新建：front/src/views/RecoveryView.vue
- 新建：front/src/views/AdminRecoveryView.vue
- 新建：front/src/components/DeleteResumeDialog.vue
- 新建：front/src/components/MatchEvidenceTable.vue
- 新建：front/src/components/RecoveryDialog.vue
- 测试：front/src/components/DeleteResumeDialog.spec.ts
- 测试：front/src/views/RecoveryView.spec.ts
- 测试：front/src/views/AdminRecoveryView.spec.ts

**接口：**
- 输入：任务 1 的生命周期/任务路由以及任务 5/7 的响应结构。
- 输出：上传、任务轮询、证据展示、用户恢复和管理员恢复流程。

- [ ] **步骤 1：编写失败的删除和按角色范围恢复测试。**

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

- [ ] **步骤 2：运行生命周期 UI 测试并观察失败。**

运行：pnpm --dir front exec vitest run src/components/DeleteResumeDialog.spec.ts src/views/RecoveryView.spec.ts src/views/AdminRecoveryView.spec.ts

预期：失败，因为生命周期 UI 尚不存在。

- [ ] **步骤 3：实现上传和任务进度状态。**

只接受 .txt 和 .docx。明确显示 PDF 不支持状态。要求选择模型配置并填写
Java 后端岗位文本。发送幂等键。仅在 QUEUED 或 PROCESSING 时轮询，并在不
捏造结果的情况下展示超时、失败、归档和无数据状态。

- [ ] **步骤 4：实现证据优先的匹配 UI。**

MatchEvidenceTable 渲染岗位要求文本、要求类型、证据摘录/位置、匹配状态、分项得分、证据
强度和差距。将 RELATED_BUT_EVIDENCE_INSUFFICIENT 与 UNMET 渲染为非正向状态。单独渲染
建议状态，绝不把未经确认的事实呈现为已应用的
变更。

- [ ] **步骤 5：实现删除和恢复控件。**

严格发送 { confirmationText: '确认删除简历', expectedVersion }。接受删除后从活动列表移除。
USER 恢复只能调用用户路由。ADMIN 恢复调用管理员路由，包含所有者上下文，
并受角色守卫保护。归档和软删除状态要披露 MySQL 保留策略。

- [ ] **步骤 6：运行测试/构建并提交。**

运行：pnpm --dir front exec vitest run src/components/DeleteResumeDialog.spec.ts src/views/RecoveryView.spec.ts src/views/AdminRecoveryView.spec.ts

运行：pnpm --dir front run build

预期：通过，且响应式布局不会裁切确认控件。

~~~bash
git add front/src
git commit -m "feat(web): add matching lifecycle and recovery views"
~~~

## 任务 10：跨服务集成与受控端到端测试样例

**文件：**
- 新建：tests/integration/run_mvp_flow.ps1
- 新建：tests/integration/fixtures/java-backend-job.txt
- 新建：tests/integration/fixtures/student-resume.txt
- 新建：tests/integration/fixtures/student-resume.docx
- 新建：tests/integration/fixtures/invalid-resume.pdf
- 新建：tests/integration/assert_mvp_flow.py
- 新建：front/e2e/resume-lifecycle.spec.ts
- 修改：README.md

**接口：**
- 输入：任务 4-9 的真实服务和任务 6 的模拟模型服务。
- 输出：一条受控简历/岗位流程的可重复证明。

- [ ] **步骤 1：在服务连接之前编写端到端断言。**

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

- [ ] **步骤 2：启动受控依赖并验证健康检查接口。**

运行：docker compose -f docker-compose.redis.yml up -d

以隐藏的 PowerShell 进程启动本地服务：

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

预期：提交请求前，Java 健康检查、Python 健康检查、Flyway 数据库迁移和 Redis PING 均成功。

- [ ] **步骤 3：实现经过清理的启动/编排脚本。**

脚本校验必需的环境变量，启动模拟模型服务，注册用户，创建模型配置，
上传测试样例，启动匹配任务，在固定期限内等待，只打印 ID/状态，并在 finally 块
中停止模拟模型服务。它绝不会打印简历文本、JWT、API 密钥或回调令牌。

- [ ] **步骤 4：添加竞态和归档检查。**

在回调前暂停模拟模型服务，软删除简历，释放回调，并断言 TASK_GONE、
结果行数为零、无 Redis 结果键且用户不可见简历。使用重复回调和
过期尝试重复测试。注入 Clock Bean（时钟 Bean）：生产环境使用 Clock.systemUTC()，集成
配置注入可变固定时钟，在调用 ArchiveScheduler 前推进七天或三十天。断言
显式恢复是返回 ACTIVE 的唯一途径。

- [ ] **步骤 5：运行完整受控流程并提交。**

运行：powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1

运行：C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q

预期：真实 Java/Python 交接、生命周期门禁和确定性的模拟模型服务匹配流程全部通过。

~~~bash
git add tests/integration front/e2e README.md
git commit -m "test: add controlled cross-service MVP flow"
~~~

## 任务 11：安全、可视化与发布证据门槛

**文件：**
- 新建：docs/verification/mvp-evidence.md
- 新建：docs/verification/log-scan-patterns.txt
- 修改：back/java/src/test/java/com/resumethinking/platform/resumes/ResumeControllerTest.java
- 新建：back/python/tests/test_log_safety.py
- 修改：README.md

**接口：**
- 输入：之前的全部实现和测试。
- 输出：可重复的证据，将已验证行为、确定性模拟、已知风险和排除范围分开记录。

- [ ] **步骤 1：添加失败的授权和日志泄露测试。**

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

- [ ] **步骤 2：从干净的服务状态运行所有契约、单元、集成和前端检查。**

运行：pnpm --dir contracts run lint && pnpm --dir contracts run validate

运行：back\java\mvnw.cmd test

运行：C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests tests/integration -q

运行：pnpm --dir front exec vitest run && pnpm --dir front run build

预期：每条命令都以零退出，证据记录经过清理的测试样例名称和结果。

- [ ] **步骤 3：运行可视化 Playwright 检查。**

运行：pnpm --dir front exec playwright test e2e/resume-lifecycle.spec.ts --project=chromium

为角色注册、模型配置、上传、等待中的任务、证据结果、删除确认、归档无数据、用户恢复和
管理员恢复捕获桌面/移动端截图。检查空白页面、重叠、裁切、隐藏的确认按钮，以及 DOM 中
是否存在敏感值。

- [ ] **步骤 4：编写证据报告并提交。**

报告包含命令/结果、测试样例覆盖范围、生命周期竞态、缓存策略、模拟模型服务范围、公开
管理员角色风险、MySQL 保留策略和排除功能。报告不宣称已达到生产可用性、公平性或真实
模型服务质量。

~~~bash
git add docs/verification README.md
git commit -m "docs: record MVP verification evidence"
~~~

## 计划自审

### 规范覆盖

- JDK 21、混合本地运行环境、仅 Redis 的 Docker、仅 Java 后端岗位范围、TXT/DOCX 以及
  PDF 拒绝由任务 3、5、6、9 和 10 实现。
- 可选择的注册角色、JWT、每用户加密模型配置、安全接口地址和不再次显示密钥由任务 4 和
  8 实现。
- MySQL 保留、status、可见性生命周期、归档日期、Redis 清理以及用户/管理员恢复由任务
  5 和 9 实现。
- 脱敏、基于证据的固定权重、OpenAI 兼容调用、事实分类和模型失败处理由任务 6 和 7 实现。
- 契约冻结、测试样例、回调幂等、竞态处理和跨服务证明由任务 1、2、7 和 10 实现。
- 授权、个人信息/日志防护、可视化检查和残余风险报告由任务 11 实现。

### 完整性扫描

计划为每个任务列出确切文件、接口、测试命令、预期结果和提交命令。没有遗留未分配的
实现事项。

### 类型一致性

契约定义 UUID ID、expectedVersion、status、visibilityState、任务尝试次数、callbackId 和
callbackToken。Java、Python 与前端任务一致使用这些名称，并从已冻结的契约复制 API 结构。
