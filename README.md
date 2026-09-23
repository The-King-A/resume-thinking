# ai-resume-thinking：简历驱动岗位匹配与模拟面试平台

本仓库包含简历匹配平台的首个混合运行环境：Spring Boot 公共 API、FastAPI
分析服务和 Vue 网页应用。版本化 API 与内部消息契约位于
[`contracts/`](contracts/README.md)，它们是各服务共同遵循的接口依据。

从 GitHub 下载 v2.0.0 的用户请先阅读 [`DEPLOYMENT.md`](DEPLOYMENT.md)，其中包含依赖安装、
数据库初始化、服务启动顺序和本地验证步骤。

当前运行时契约为 v2：公共 API 使用 `/api/v2`，Java 与 Python 的内部任务/回调使用
`/internal/v2`。业务主键采用可读编号（例如 `user001`、`resume001`、`task001`）；Redis
页面缓存使用 `resume:v2:view:<resumeId>` 命名空间。链路追踪值单独使用 `correlationId`，
不会替代业务主键。

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
环境变量引用；启用 `local` 配置后会加载它。该配置会自动尝试读取项目根目录的
`.env`，显式进程环境变量优先。

### 本机密码重置

本机需要直接重置已注册用户名或邮箱的密码时，在 `.env` 中设置
`APP_LOCAL_PASSWORD_RESET_ENABLED=true`，并使用 `local` profile 启动服务。该接口仅接受
本机回环地址（loopback）请求；它直接更新密码后即可使用原用户名或邮箱和新密码登录。
接口不会发送邮件、不会创建令牌，也不会在响应中回显密码、密码哈希、令牌或账号数据。
此开关仅供本机开发使用，生产环境、共享环境或任何非回环部署均不可启用。

使用 IntelliJ 运行时，`local` 配置会自动尝试读取项目根目录的 `.env`；也可以在
Run Configuration 的 **Environment variables** 中显式设置
`APP_LOCAL_PASSWORD_RESET_ENABLED=true`。显式设置的环境变量优先于 `.env` 文件。
修改该环境变量或本机绑定配置后，必须停止并重新启动 IntelliJ 中的 Java 运行进程；已运行的
进程会继续使用旧的类和环境变量。

不要通过反向代理、端口映射或隧道把此 `local` 服务暴露给其他设备。本机开关不是公网、
远程或跨设备的密码恢复方案。

### 数据库初始化（二选一）

对于空数据库，选择以下其中一条路径，绝不要同时执行两条路径。

1. **已有 V1-V7 数据库：** 备份并停止所有写入后，由 Flyway 执行
   `V8__string_business_ids.sql`，将旧版 UUID/数字业务 ID 确定性迁移为 v2 可读字符串 ID。
   随后会自动执行 `V9__repair_evidence_sequence_and_comments.sql`，修复 evidence 序列并补齐
   `id_sequences` 的字段备注。迁移包含隐式提交，失败时必须从备份恢复；迁移后旧格式 JWT
   预期失效，要求重新登录。
2. **新库：** 仅对空数据库执行
   [`database/resume_thinking_schema.sql`](database/resume_thinking_schema.sql)，它直接创建 v2
   字符串列和 `id_sequences`。随后在 Java 服务首次启动前设置
   `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` 和 `SPRING_FLYWAY_BASELINE_VERSION=8`，由 Flyway
   写入自己的基线记录；后续启动会继续执行 V9。

已有 V10-V14 数据库在更新恢复逻辑后，必须重新编译并重启 Java 服务，Flyway 才会执行
`V15__repair_historical_effective_resume_revisions`。该迁移只为确实存在成功任务、持久化结果和
证据的历史简历补齐有效修订；没有完成证据匹配的简历仍不可恢复到有效简历列表。

不要把新库快照与 V1-V7 迁移混用，也不要在未备份、未停写时执行 V8。

绝不要手工创建或写入 `flyway_schema_history`。

### 本地演示数据

演示数据默认关闭，只会在 `local` 配置下同时满足
`APP_DEMO_SEED_ENABLED=true` 时运行。设置该标志前，必须在本地选择
`DEMO_USER_PASSWORD` 和 `DEMO_ADMIN_PASSWORD`，且每个密码至少 12 个字符。启动器
不会打印这些密码；不要在文档或版本库中记录实际值。

FastAPI 工作进程的内部分析路由由 `X-Internal-Service-Token` HTTP 请求标头保护。
请为 Java 调用方和 Python 工作进程配置同一个本地生成的
`PYTHON_INTERNAL_SERVICE_TOKEN`（至少 32 个非空白可打印字符）；该值有意不放入
JSON 任务契约，且不得提交。
回调目标默认仅允许回环地址，以及 `JAVA_CALLBACK_BASE_URL` 配置的来源/路径。
还可以通过逗号分隔的 `PYTHON_CALLBACK_ALLOWED_BASE_URLS` 添加额外的精确回调基址。
无效或未配置的目标会在发起网络请求前被拒绝。

### 启动 Python 分析服务

Java 服务、前端和 Python 分析服务是三个独立进程。仅启动 Java 和前端时，创建的
匹配任务无法投递到分析服务。请在仓库根目录另开一个 PowerShell 窗口，使用 Python
3.11 启动 FastAPI：

FastAPI 在未显式设置进程环境变量时，会从仓库根目录 `.env` 中仅读取
`PYTHON_INTERNAL_SERVICE_TOKEN`、`PYTHON_MODEL_READ_TIMEOUT`、
`PYTHON_MODEL_MAX_TOKENS`、`PYTHON_MODEL_THINKING`、`JAVA_CALLBACK_BASE_URL` 和
`PYTHON_CALLBACK_ALLOWED_BASE_URLS`。显式进程环境变量优先；数据库密码、JWT
密钥和其他 `.env` 内容不会被 Python 导入。修改这些值后必须重启 Python 进程；
如果 Java 服务已经在运行，也要重启 Java 服务，使新的任务交接和回调代码生效。

对于 DeepSeek 官方 `deepseek-flash`、`deepseek-v4-flash`（兼容别名）和
`deepseek-v4-pro` 模型，Python 会保留配置中的精确模型名，不会把 Pro 静默降级为
Flash。`PYTHON_MODEL_THINKING=auto` 会让 Flash 与旧版 v4-flash 别名使用非思考结构化请求，
而让 `deepseek-v4-pro` 使用官方的高强度思考模式（`thinking: {"type":"enabled"}` 与
`reasoning_effort: "high"`），并保留配置的输出预算。这样 Pro 的报告、面试题和回答分析都会真正走 Pro 模型。
如需显式覆盖思考策略，请设置 `PYTHON_MODEL_THINKING=enabled` 或 `disabled`，并让
`PYTHON_MODEL_MAX_TOKENS` 覆盖思考和最终 JSON 的总输出预算，按所选模型上限调小。
Python 日志只记录请求模型、响应模型、完成原因和 usage 等元数据，可据此核对实际调用与计费模型，
不会记录 API Key、简历正文或回答内容。客户端会拒绝 `finish_reason=length` 的不完整响应，
不会把截断 JSON 当成成功结果。

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir back\python --host 127.0.0.1 --port 8000
```

启动完成后访问 `http://127.0.0.1:8000/health`，返回 `{"status":"ok"}` 即表示分析服务
已就绪。不要使用默认的 Python 3.13；本项目的 Python 依赖约束为 `>=3.11,<3.12`。

如果 Docker Desktop 可用，请使用 Docker Compose 启动仅包含 Redis 的本地依赖：

```powershell
docker compose -f docker-compose.redis.yml up -d
```

构建并测试各服务根目录：

```powershell
back\java\mvnw.cmd -q -DskipTests compile
.\.venv\Scripts\python.exe -m pip install -e "back/python[test]"
.\.venv\Scripts\python.exe -m pytest back/python/tests -q
pnpm --dir front install
pnpm --dir front run build
```

## 受控 MVP 流程

### 面试推演第一期

在成功的岗位匹配报告页面选择“开始面试推演”后，系统会基于已验证的岗位要求和简历证据生成四类问题：基础确认、项目深挖、岗位场景和综合追问。当前版本只支持一次回答和一次反馈；回答会加密保存，发送到模型前进行脱敏，未确认的新增事实不会写入简历或长期画像。

面试会话仅接受报告任务编号，由 Java 服务端确认任务所有权、有效简历修订和证据范围。结束会话或删除/归档关联简历会清理可读回答与反馈，并拒绝迟到回调。

跨服务测试样例检查位于 [`tests/integration/`](tests/integration)。这些检查无需
数据库或模型服务即可运行，并会验证仓库中已提交的 TXT/DOCX/PDF 输入：

```powershell
.\.venv\Scripts\python.exe -m pytest tests/integration/assert_mvp_flow.py -q
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

