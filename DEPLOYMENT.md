# v2.1.0 本地部署指南

本文档面向从 GitHub 下载 `resume-thinking` v2.1.0 的用户。项目是本地开发版，包含三个独立进程：

- Java 21 / Spring Boot：公共 API、认证、数据库和任务编排
- Python 3.11 / FastAPI：简历解析、岗位匹配和面试推演分析
- Node.js + pnpm / Vue：网页界面

当前版本还需要本机运行 MySQL 8.0+ 和 Redis 7.x。除明确配置的回环地址外，不要把服务暴露到公网。

## 1. 获取代码与准备依赖

可以下载 GitHub Release 的 `ai-resume-thinkingv2.1.0.zip`，也可以克隆仓库：

```powershell
git clone https://github.com/The-King-A/resume-thinking.git
cd resume-thinking
git checkout v2.1.0
```

安装以下工具并确保它们已经加入 PATH：

- JDK 21
- Python 3.11（不要使用 3.12 或 3.13）
- Node.js 20.19+ 或 22.12+
- pnpm 10（可使用 `corepack enable`）
- MySQL 8.0+
- Redis 7.x，或 Docker Desktop

验证版本：

```powershell
java -version
py -3.11 --version
node --version
pnpm --version
mysql --version
```

Linux/macOS 用户将下面命令中的 `py -3.11`、反斜杠路径和 `.venv\Scripts\python.exe` 替换为对应系统写法。

## 2. 安装项目依赖

在仓库根目录执行：

```powershell
corepack enable
pnpm --dir front install --frozen-lockfile
pnpm --dir contracts install --frozen-lockfile

py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -e "back/python[test]"
```

Java 使用仓库内的 Maven Wrapper，不需要单独安装 Maven。

## 3. 准备 MySQL 和 Redis

### MySQL

创建一个空数据库用户，并确保该用户对 `resume_thinking` 具有建表和迁移权限。对于全新的数据库，只执行一次：

```powershell
Get-Content -Raw -Encoding utf8 database\resume_thinking_schema.sql | mysql -u root -p
```

然后在 `.env` 中设置以下两项，供首次 Java 启动完成 Flyway 基线。首次成功启动后可以将它们清空：

```text
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
SPRING_FLYWAY_BASELINE_VERSION=8
```

已有 V1-V7 数据库不要执行上面的快照；请先备份、停止写入，再让 Java/Flyway 按 README 中的迁移顺序执行 V8 及后续迁移。两种路径不能混用，也不要手工写入 `flyway_schema_history`。

### Redis

有 Docker Desktop 时，在仓库根目录执行：

```powershell
docker compose -f docker-compose.redis.yml up -d
```

也可以使用本机 Redis，默认地址为 `127.0.0.1:6379`。确认端口已经监听后再启动 Java。

## 4. 配置本地环境

复制示例配置：

```powershell
Copy-Item .env.example .env
Copy-Item front\.env.example front\.env.local
```

编辑根目录 `.env`，至少填写 MySQL 账号密码、两个 32 字节 Base64 密钥和 Java/Python 共享令牌。可以用下面的 PowerShell 生成安全随机值：

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower()
```

依次将三行结果填入：

1. `JWT_SIGNING_KEY_BASE64`
2. `APP_ENCRYPTION_KEY_BASE64`
3. `PYTHON_INTERNAL_SERVICE_TOKEN`

不要提交 `.env`、真实模型 API Key 或真实密码。演示数据默认关闭；如需本机演示账号，设置 `APP_DEMO_SEED_ENABLED=true`，并为 `DEMO_USER_PASSWORD` 和 `DEMO_ADMIN_PASSWORD` 设置至少 12 位的本地密码。

`front/.env.local` 默认使用 `http://127.0.0.1:8080`，通常无需修改。使用 `localhost` 时，要同步把该来源加入 `APP_CORS_ALLOWED_ORIGINS`。

## 5. 按顺序启动三个服务

每个服务都在独立的 PowerShell 窗口中运行。启动顺序为 Redis、Python、Java、前端。

### Python 分析服务

在仓库根目录运行：

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir back\python --host 127.0.0.1 --port 8000
```

另开窗口检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

应返回 `status` 为 `ok`。Python 只读取 `.env` 中允许的模型超时、共享令牌和回调配置，不会读取数据库密码或 JWT 密钥。

### Java 公共 API

在仓库根目录运行：

```powershell
Push-Location back\java
try { .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local' }
finally { Pop-Location }
```

检查 Java 健康状态：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

应返回 `status` 为 `UP`。如果使用 IntelliJ，也必须选择 `local` profile，并确保运行目录能够读取仓库根目录 `.env`。

### Vue 前端

在第三个窗口运行：

```powershell
Push-Location front
try { pnpm run dev -- --host 127.0.0.1 }
finally { Pop-Location }
```

浏览器打开 <http://127.0.0.1:5173>，注册账号后即可进入工作台。

## 6. 首次使用

1. 注册并登录普通用户。
2. 在模型配置中填写一个已获授权的 OpenAI 兼容接口、模型名和 API Key，并完成连接测试。
3. 上传 TXT 或 DOCX 简历；当前 v2 切片不支持 PDF 上传。
4. 提交岗位描述，等待匹配任务完成。
5. 在匹配报告中选择“开始面试推演”。面试问题会根据已验证的岗位要求和简历证据生成。

模型配置的 API Key 由 Java 加密保存，不会出现在接口响应中。发送到外部模型前，Python 会对简历和回答做脱敏；模型返回的新增事实不会自动写入简历。

## 7. 本地验证

不依赖数据库和外部模型的检查：

```powershell
.\.venv\Scripts\python.exe -m pytest back\python\tests -q
.\.venv\Scripts\python.exe -m pytest tests\integration\assert_mvp_flow.py -q
pnpm --dir front test
pnpm --dir front run build
pnpm --dir contracts run validate
```

需要已经启动 Java、Python、MySQL 和 Redis 的完整本地流程：

```powershell
powershell -ExecutionPolicy Bypass -File tests\integration\run_mvp_flow.ps1
```

脚本发现配置或服务不完整时会输出 `SKIP`；使用 `-RequireLive` 才会将这些情况视为失败。

## 8. 常见问题

- **Java 启动后立即退出：** 检查 MySQL 凭据、Flyway 基线是否只执行了一次，以及 Redis 是否可连接。
- **Python 持续重试：** 确认 Java 和 Python 使用相同的 `PYTHON_INTERNAL_SERVICE_TOKEN`，并确认 `JAVA_CALLBACK_BASE_URL` 指向 `http://127.0.0.1:8080`。
- **前端跳回登录页：** 确认 Java 已启动、`front/.env.local` 的 API 来源与 Java 端口一致，并清理浏览器旧会话后重新登录。
- **端口被占用：** 先停止占用 5173、8000 或 8080 的旧进程，再重新启动对应服务；如果修改端口，也要同步修改 `.env`、前端配置和 CORS。
- **上传失败：** v2 当前只接受 TXT/DOCX；文件大小还受 Java 的 multipart 限制。

## 9. 安全边界

这是本地开发版，不是生产部署方案。默认只绑定回环地址，禁止通过公网反向代理、端口映射或隧道暴露本地密码重置接口、内部回调接口或 Redis。请自行保管 `.env`、数据库凭据、JWT 密钥、加密密钥和模型 API Key。
