# 简历匹配平台

本仓库包含简历匹配平台的首个混合运行环境：Spring Boot 公共 API、FastAPI
分析服务和 Vue 网页应用。版本化 API 与内部消息契约位于
[`contracts/`](contracts/README.md)，它们是各服务共同遵循的接口依据。

各目录和关键文件的职责说明见 [`docs/项目文件说明.md`](docs/项目文件说明.md)。

## 本地配置

将 `.env.example` 复制为本地 `.env`，并把占位值替换为已获授权的开发凭据。
不要提交任何 `.env` 文件。

Java 服务从进程环境变量读取这些值。要从 PowerShell 启动本地 Spring 配置，
请先从 `.env` 导出变量：

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

`back/java/src/main/resources/application-local.yml` 已纳入版本控制，其中只包含
环境变量引用；启用 `local` 配置后会加载它。

### 数据库初始化（二选一）

对于空数据库，选择以下其中一条路径，绝不要同时执行两条路径。

1. 让 Java 服务首次启动时由 Flyway 自动执行 V1 至 V6 迁移。
2. 需要由数据库管理员手工创建表时，只对空数据库执行
   [`database/resume_thinking_schema.sql`](database/resume_thinking_schema.sql)。随后在 Java
   服务首次启动前设置 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` 和
   `SPRING_FLYWAY_BASELINE_VERSION=6`，由 Flyway 写入自己的基线记录。

绝不要手工创建或写入 `flyway_schema_history`。

### 本地演示数据

演示数据默认关闭，只会在 `local` 配置下同时满足
`APP_DEMO_SEED_ENABLED=true` 时运行。设置该标志前，必须在本地选择
`DEMO_USER_PASSWORD` 和 `DEMO_ADMIN_PASSWORD`，且每个密码至少 12 个字符。启动器
不会打印这些密码；不要在文档或版本库中记录实际值。

FastAPI 工作进程的内部分析路由由 `X-Internal-Service-Token` HTTP 请求标头保护。
请为 Java 调用方和 Python 工作进程配置同一个本地生成的
`PYTHON_INTERNAL_SERVICE_TOKEN`；该值有意不放入 JSON 任务契约，且不得提交。
回调目标默认仅允许回环地址，以及 `JAVA_CALLBACK_BASE_URL` 配置的来源/路径。
还可以通过逗号分隔的 `PYTHON_CALLBACK_ALLOWED_BASE_URLS` 添加额外的精确回调基址。
无效或未配置的目标会在发起网络请求前被拒绝。

如果 Docker Desktop 可用，请使用 Docker Compose 启动仅包含 Redis 的本地依赖：

```powershell
docker compose -f docker-compose.redis.yml up -d
```

构建并测试各服务根目录：

```powershell
back\java\mvnw.cmd -q -DskipTests compile
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pip install -e "back/python[test]"
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q
pnpm --dir front install
pnpm --dir front run build
```

## 受控 MVP 流程

跨服务测试样例检查位于 [`tests/integration/`](tests/integration)。这些检查无需
数据库或模型服务即可运行，并会验证仓库中已提交的 TXT/DOCX/PDF 输入：

```powershell
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q
```

要验证 Java 到 Python 的交接，先将 `.env.example` 复制为 `.env`，把所有占位值
替换为已获授权的本地值，并确保 Redis 可用，然后运行：

```powershell
powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1
```

当服务或凭据缺失时，启动器默认明确输出 `SKIP`。在持续集成（CI）中如需将其视为失败，
请使用 `-RequireLive`（或 `MVP_REQUIRE_LIVE=1`）。实时运行器使用临时的
回环地址模拟模型服务，并将其凭据保存在内存中；它绝不会打印 JWT、API 密钥、
回调令牌或简历内容。Playwright 生命周期检查需要显式启用：

```powershell
$env:E2E_LIVE = '1'
$env:E2E_API_BASE_URL = 'http://127.0.0.1:8080'
$env:E2E_PROVIDER_URL = 'http://127.0.0.1:<fake-provider-port>'
pnpm --dir front exec playwright test e2e/resume-lifecycle.spec.ts
```

覆盖的状态和前置条件行为请参阅
[`tests/integration/task-10-report.md`](tests/integration/task-10-report.md)。
