# MVP 验证证据

原始记录日期：2026-08-28
最近更新：2026-09-02（有效简历生命周期跨层复核）

本报告记录首个简历与岗位匹配切片的证据，并分别说明已实现行为、确定性的离线检查、
尚未验证的实时集成和残余风险。报告不宣称已达到生产可用性，不评价真实模型服务的质量、
公平性，也不宣称会物理删除 MySQL 数据。文中明确标注的 2026-08-28 数字属于迁移前历史
证据；当前本机数据库状态以 2026-08-31 的 V7/V8/V9 登记核验为准。

## 契约与范围

- `contracts/openapi/v2/openapi.yaml` 以及 `contracts/internal/v2/` 下的 JSON 模式
  是当前运行时的接口权威来源；历史契约快照仅用于迁移对照，不参与服务路由。
- 公共 API 统一使用 `/api/v2`，Java 到 Python 的任务和回调统一使用 `/internal/v2`。
- 用户、简历、任务、配置、证据、要求、结果和回调等业务主键使用带前缀的可读编号，
  例如 `user001`、`resume001`、`task001`、`evidence001`；`correlationId` 仅作为链路追踪值。
- 发布的岗位族目前仅为 Java 后端开发。保留 `role` 字段，以便后续添加更多岗位族。
- Java/Spring Boot 是唯一的公共业务、授权、持久化和任务状态权威。Python/FastAPI 负责
  提取、脱敏、受控模型调用和回调投递。Vue 是操作客户端。
- 本地目标运行环境为 JDK 21、Maven/Spring Boot 3、Python 3.11、Vue 3、MySQL 8.4 和
  Redis 7。
- MVP 边界：Redis 当前只保存派生的 `resume:v2:view:<resumeId>` 页面缓存。任务进度、幂等
  数据、回调凭据和匹配结果均由 MySQL 权威保存；Redis 任务状态只是文档记录的未来优化，
  当前尚未实现。

## 已实现行为

### 身份与模型配置

- 注册支持 `USER` 和 `ADMIN`；按本阶段决策，暂不限制管理员账号的创建。
- 本机直接密码重置由 `APP_LOCAL_PASSWORD_RESET_ENABLED=true` 显式开启，且仅在 `local`
  profile 与回环地址（loopback）请求同时满足时可用。已注册用户名或邮箱可设置新密码后登录；
  接口不发送邮件、不创建恢复令牌，也不回显密码、哈希、令牌或账号数据。该能力不可用于生产、
  共享环境或非回环部署。
- 每个账号拥有自己的 OpenAI 兼容接口地址和模型配置。模型服务密钥会加密保存，只在 Java
  内存中用于派发，从不通过 API 响应或前端表单返回。更新其他配置字段时，如果没有提供新
  密钥，会保留已有密钥。
- 接口地址校验会拒绝不安全的目标，明确允许的本地开发例外除外。连接测试只返回状态。

### 简历与匹配流程

- TXT 和 DOCX 简历可以上传，并以加密形式保存在 MySQL 中（V6 升级后使用 `MEDIUMBLOB`，
  与 5 MB 上传上限匹配）。Java 写入带保留期限的派生 Redis 视图，并在删除或归档时清除；
  公共列表/读取授权仍由 Java 的持久化状态支撑。MVP 明确拒绝 PDF。
- Java 后端岗位描述会创建异步任务。Python 接收脱敏材料和允许使用的证据集合，然后返回
  结构化评分、需求匹配、来源范围和建议状态。
- 证据引用会针对任务允许的来源范围校验；不受支持或未经确认的声明与生成的简历内容分开
  保存。迟到、过期、重复或未授权的回调不能重新创建已删除的结果。读取任务和结果时，还
  要求关联简历在任务记录的版本上保持 `ACTIVE`；恢复简历后会先生成新版本，才能读取新任务。
- 结果页面遇到临时的 `TASK_NOT_READY` 响应会自动重试，不要求手动刷新。

### 可见性、删除与恢复

- `USER` 创建的简历可见七天，`ADMIN` 创建的简历可见三十天。过期会归档页面可见性并移除
  Redis 键，但不会删除 MySQL 记录。
- 手动删除要求完成身份认证、通过所有权/管理员范围校验、输入确认短语并提供预期版本。操作
  会移除 Redis 数据，并在 MySQL 中记录软删除状态。没有公共硬删除接口。
- 用户恢复按所有者范围执行。管理员恢复覆盖所有符合条件的记录；管理员软删除会隐藏该记录，
  直到管理员恢复。MySQL 的物理删除只能由数据库管理员按手工数据库流程执行。
- Java 到 Python 的内部路由要求配置 `PYTHON_INTERNAL_SERVICE_TOKEN`；缺失或仍为占位值时
  会拒绝处理（默认拒绝）。

## 离线与静态证据

以下检查针对集成工作树（`feature/resume-matching-mvp`）执行：

| 检查 | 结果 | 证据边界 |
| --- | --- | --- |
| `pnpm --dir contracts run lint` | 通过 | OpenAPI v2 语法和代码检查规则（同时保留历史快照检查） |
| `pnpm --dir contracts run validate` | 通过 | 13 个有效示例，加上预期无效的匹配示例 |
| `E:\maven\...\mvn.cmd test`（在 `back/java` 中） | 通过，120 项测试 | Java 单元测试和 HTTP 边界测试；未连接真实 DB/Redis |
| Python 3.11 `pytest back/python/tests tests/integration -q` | 通过，82 项测试 | 脱敏、解析、回调规则、示例和离线流程断言 |
| `pnpm --dir front exec vitest run` | 通过，56 项测试 | jsdom 下的 Vue/API 行为 |
| `pnpm --dir front run build` | 通过 | `vue-tsc` 和 Vite 生产构建 |
| 对 `run_mvp_flow.ps1` 进行 PowerShell AST 解析 | 通过，0 个解析错误 | 仅验证启动器语法 |
| 使用临时模拟令牌执行启动器预检 | 通过 | 输出为通用 `FAIL preflight=CONFIGURATION_OR_SERVICE`；输出中没有模拟令牌 |

离线集成断言覆盖受控 TXT/DOCX/PDF 示例、证据与评分不变量、保留期限计算、回调竞态
示例及已清理的错误行为。它们不会连接 Java、MySQL、Redis 或外部模型服务。

## 本地受控基线、迁移与运行时验证

### 迁移前历史基线（2026-08-28）

以下运行时数字是 2026-08-28 记录的迁移前本地基线，用于保留可追溯证据；它们不代表当前
数据库中的行数，也不代表 v2 字符串业务 ID 迁移前后的最终状态。当日 `V8__string_business_ids.sql`
尚未执行；该历史记录只覆盖 V6 baseline 和 V8 执行前的运行行为。

当日验证于 2026-08-28 在已授权的本地 MySQL 和 `127.0.0.1:6379` Redis 上完成。未跟踪的
`.env` 仅被逐行加载到当前进程；只确认了必需变量名存在，未输出、记录或提交任何值。

启动前，MySQL 服务处于运行状态，Redis `PING` 返回 `PONG`。使用
`E:\mysql\bin\mysql.exe` 执行了仅含 `COUNT(*)`、状态分组和 Flyway 基线计数的查询；
密码只通过该查询子进程的 `MYSQL_PWD` 传入，并在查询后从进程环境清除。启动前的安全聚合
结果为 0 个用户、0 份简历、0 条恢复审计记录，并且没有 Flyway 历史表。

随后以 `local` profile 隐藏启动 Java 服务，并轮询
`http://127.0.0.1:8080/actuator/health` 至 `UP`。Flyway 自行创建历史表并写入版本 `6` 的
`BASELINE` 记录；没有执行任何手工建表、Flyway 历史写入、重置或删除。条件化演示初始化器
随后完成一次种子写入。使用指定 Python 3.11 解释器隐藏启动 FastAPI，并轮询
`http://127.0.0.1:8000/health` 至 `ok`。两个服务在验证结束后继续分别监听 8080 和 8000。

| 运行时断言 | 实际结果 |
| --- | --- |
| Flyway 创建的 version `6` baseline | 通过，1 条 `BASELINE` 记录 |
| MySQL 用户数 | 2 |
| MySQL 简历数 | 6 |
| `resume_recovery_audit` 数 | 4 |
| `ACTIVE` | 2，`status=0` |
| `USER_SOFT_DELETED` | 1，`status=1` |
| `USER_CACHE_ARCHIVED` | 1，`status=0` |
| `ADMIN_SOFT_DELETED` | 1，`status=1` |
| `ADMIN_CACHE_ARCHIVED` | 1，`status=0` |
| Redis `resume:v2:view:*` 键数 | 2 |
| Java 健康端点 | `UP` |
| Python 健康端点 | `ok` |

本地登录仅在内存中用于授权检查，未输出或保存密码、访问令牌或原始响应。普通用户恢复列表
恰有 `USER_SOFT_DELETED` 和 `USER_CACHE_ARCHIVED` 两个状态；管理员恢复列表恰有四个可恢复
状态：`USER_SOFT_DELETED`、`USER_CACHE_ARCHIVED`、`ADMIN_SOFT_DELETED` 和
`ADMIN_CACHE_ARCHIVED`。验证没有调用恢复、删除或匹配任务接口。

随后在同一份本地演示数据上执行第二次受控运行时校验：仅移除了两个 `ACTIVE` 派生缓存视图，
并仅为一个隐藏演示记录写入短暂的陈旧缓存键，再以 `local` profile 重启 Java 并触发幂等种子。
校验确认用户、简历和审计计数仍分别为 2、6 和 4；五种既有可见性/状态组合保持不变；两条缓存
归档审计的 actor 仍为 null，两条软删除审计的 actor 均已设置；Redis 恰有两个
`resume:v2:view:*` 键，且均属于 `ACTIVE` 记录。该校验未读取或输出任何账号、简历、加密内容或
缓存值；外部模型、任务派发和回调行为仍未验证。

该运行证明缓存仅保留两个 `ACTIVE` 页面视图；软删除和缓存归档均保留 MySQL 中的加密记录及
审计，不构成物理删除。外部模型集成没有被调用或验证，也没有声称真实模型质量、延迟、成本、
安全性或可用性。

上述启动、种子和 Redis 结果均属于 V8 执行前的历史运行，不能用来判断当前 v2 数据库是否仍
处于相同计数或缓存状态。

### V8 后本机登记核验（2026-08-31）

在 2026-08-31 的本机数据库核验中，Flyway 已成功登记 V8；查询只读取迁移元数据、字段类型和
聚合计数，不输出账号、简历正文、密码、令牌或任何密钥。`flyway_schema_history` 的成功记录为：

| 迁移 | 数据库记录时间（本机） | 结果 |
| --- | --- | --- |
| `6` baseline | 2026-08-28 15:54:27 | `BASELINE`，成功 |
| `7` `column comments zh` | 2026-08-31 09:35:53 | SQL，成功 |
| `8` `string business ids` | 2026-08-31 13:02:20 | SQL，成功 |
| `9` `repair evidence sequence and comments` | 2026-08-31 14:53:39 | SQL，成功 |

V8 后的安全聚合结果为：`users` 3 行、`resumes` 6 行、`resume_recovery_audit` 4 行；
`analysis_tasks`、`analysis_results` 和 `llm_profiles` 当前均为 0 行。信息架构元数据确认已
查询到的业务标识列统一为 `VARCHAR(64)`；已有用户、简历和恢复审计记录分别有 3/3、6/6 和
4/4 个 ID 符合 `userNNN`、`resumeNNN` 和 `auditNNN` 前缀规则。空表没有可供格式计数的行。

这次登记核验确认 V8/V9 已在本机数据库完成并成功记录。V9 只修复 evidence 序列和
`id_sequences` 字段备注，不改变业务数据或 V8 checksum。

### 迁移后端到端受控冒烟（2026-08-31）

在隔离端口 `8081`/`8001` 启动最新 Java/Python 服务，使用本地内存 OpenAI 兼容模拟器，
并复用本机 MySQL/Redis。流程通过：注册 USER/ADMIN、TXT 与 DOCX 上传、PDF 415 拒绝、
匹配任务 `PROCESSING` 到 `SUCCEEDED`、结果证据校验、用户软删除/恢复、迟到回调返回
`TASK_GONE` 且不重建结果、管理员软删除/恢复。Java 内部 HTTP 客户端固定 HTTP/1.1，避免
明文回环服务的 h2c 升级丢失请求体。该验证使用模拟模型，不代表真实外部模型质量或可用性。

## 数据库字段备注补充（V7）

`V7__column_comments_zh.sql` 已于 2026-08-31 成功登记，为 8 张业务表的全部字段补齐中文
`COMMENT`；`database/resume_thinking_schema.sql` 也已同步更新。该迁移只修改字段备注，不改
变现有数据、索引、约束或加密逻辑。`ResumeSchemaTest` 已增加覆盖并通过。

## 有效简历生命周期跨层复核（2026-09-02）

本次复核新增的是浏览器和受控集成证据，不改变 Java、Python、Vue、OpenAPI、样例或安全
实现。受控 Python 流程使用进程内本地模拟服务和临时内存凭据；它不会输出、记录或持久化
模型服务密钥、访问令牌、原始简历内容或模型诊断。浏览器用例只断言安全的中文可见状态。

| 日期 | 命令 | 受控样例/范围 | 退出码 | 可观察结果 | 限制 |
| --- | --- | --- | ---: | --- | --- |
| 2026-09-02 | `corepack pnpm --dir contracts run lint` | v1/v2/v3 OpenAPI | 0 | 三份契约均通过语法与规则检查；v3 有 4 条既有未使用组件警告 | 只验证描述文件，不连接服务 |
| 2026-09-02 | `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs` | `contracts/fixtures/v3/`，含初始/重新匹配、重复标题、软删除标题复用、恢复冲突、无有效匹配版本和 v2 兼容样例 | 0 | 有效样例通过，预期无效样例被拒绝 | 契约样例不是实时服务调用 |
| 2026-09-02 | `mvn -q test`（`back/java`） | 全部 Java 测试 | 0 | Java 单元与 HTTP 边界套件完成 | Mockito/JDK 动态 agent 警告不影响退出码；不构成实时 MySQL/Redis 证明 |
| 2026-09-02 | `py -3.11 -m pytest tests -q`（`back/python`） | 102 项 Python 测试 | 0 | 102 通过 | 1 条第三方 Starlette/httpx 弃用警告；不调用外部模型 |
| 2026-09-02 | `py -3.11 -m pytest tests/integration/assert_mvp_flow.py -q` | TXT/DOCX/PDF 与本地模拟服务单元断言 | 0 | 16 通过；模拟服务可接受受控匹配结果和连接探测 | `--live` 流程未在该命令中执行 |
| 2026-09-02 | `py -3.11 tests/integration/assert_mvp_flow.py` | 默认离线入口 | 0 | 输出 `SKIP live` | 该入口必须显式 `--live`，跳过不是实时生命周期证明 |
| 2026-09-02 | `powershell -NoProfile -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1 -NoStart` | 已运行的本地 Java/Python/MySQL/Redis | 0 | 输出 `SKIP services=EXISTING_JAVA_LOCAL_PROVIDER_UNVERIFIED`，在创建实时流程数据前退出 | 运行中的 Java 未声明本地测试服务商支持；未验证 v3 实时发布 |
| 2026-09-02 | 隔离端口执行 `run_mvp_flow.ps1 -RequireLive` | `student-resume.txt`、`student-resume.docx`、本地内存测试服务商 | 1 | Java/Python/MySQL/Redis 健康；模型配置探测通过；初始 v3 提交从 `PENDING` 发布为 `PUBLISHED` | 旧断言错误地要求受控 `MODEL_UNAVAILABLE` 为 `FAILED`；实际 worker 使用 `TIMED_OUT`，所以完整序列未在该退出码中证明 |
| 2026-09-02 | `corepack pnpm --dir front test` | Vue/API 单元测试 | 0 | 23 个文件、126 项通过 | jsdom，不是浏览器服务联调 |
| 2026-09-02 | `corepack pnpm --dir front build` | Vue 类型检查与生产构建 | 0 | 构建完成 | 依赖 `@iarna/toml` 的既有 direct-eval 警告；不影响退出码 |
| 2026-09-02 | `corepack pnpm --dir front test:browser -- login-profiles-rendering.spec.ts` | 登录到 `/profiles` 回归 | 0 | 两项登录/页面恢复用例通过；成功登录后 `/profiles` 无 `pageerror` 或浏览器 console error | 同次 Playwright 运行中，受环境保护的生命周期用例跳过 |
| 2026-09-02 | `corepack pnpm --dir front test:browser -- resume-lifecycle.spec.ts login-profiles-rendering.spec.ts` | `student-resume.txt`、`student-resume.docx`、`invalid-resume.pdf` 与 v3 UI 路径 | 0 | 登录回归两项通过；v3 浏览器用例由 `E2E_LIVE`/服务商环境保护而跳过 | 没有已启动的 Java API、Python worker、前端和许可测试服务商组合，因此不把 skip 视为浏览器生命周期证明 |

受控实时流程在其前置条件可用时会验证如下状态转换：初始 v3 提交
`PENDING -> PUBLISHED`；带不同标题和 DOCX 替换文件的重新匹配被模拟服务强制失败后，旧的
有效版本和其 v2 报告保持可读；另一份同标题候选在 v2 任务轮询返回
`409 DUPLICATE_RESOURCE` 后仍可由所有者读取 v2 结果；原有效简历软删除后，同标题新提交可
发布；恢复旧记录返回重复标题冲突，若候选没有任何有效匹配版本则返回 `RESUME_NOT_EFFECTIVE`。浏览器用例对应断言“报告有效，但该候选简历未成为有效
简历”的中文路径，且不读取或显示 API 密钥、访问令牌或服务商诊断。

本次已记录一次受控实时初始发布，但没有取得完整 v3 替换、重复报告、标题复用和恢复冲突的
同一次成功运行。受控运行修正后仍需重新取得并保留完整成功输出；浏览器端还需提供
`E2E_LIVE` 和许可测试服务商。跳过仅证明保护条件生效。

### 2026-09-02 安全保护补充

`assert_mvp_flow.py` 和 `run_mvp_flow.ps1` 现在在任何健康检查、子进程启动、注册或实时写入
之前，结构化限制 Java、Python、MySQL、Redis 和本地测试服务商为精确的
`http://127.0.0.1:<port>`/`127.0.0.1` 目标。`127.0.0.1.example.test`、远程数据库和远程
Redis 均被安全拒绝。新增 15 项集成断言通过；直接脚本的欺骗性目标运行以安全失败退出，
PowerShell 启动器以 `SKIP targets=UNSAFE_LOCAL_TARGET` 退出。该证据不代表实时生命周期通过。

### 2026-09-02 安全保护第二轮

第二轮发现并修复了运行时 Redis 目标分裂：预检原先只读取 `REDIS_HOST`，而迟到结果清理探针
可读取未校验的 `MVP_REDIS_HOST`。现在预检一次性解析并返回 Java/Python/Redis 的规范目标，
Redis 探针只接收该已验证的 host/port。`MVP_REDIS_HOST=redis.example.test` 的回归首先失败，
显示旧实现会先启动模拟服务和构造 API client；修复后在任何 provider、client 或网络连接前以
`UNSAFE_LOCAL_TARGET` 拒绝。

PowerShell 启动器也不再用 JDBC 前缀正则提取 MySQL。它先移除 `jdbc:` 再使用 `[Uri]` 解析，
拒绝 userinfo、query、fragment、非精确回环主机和非法端口；同时校验并统一 `REDIS_*` 与
`MVP_REDIS_*`，使依赖探测、启动的 Java 和子 Python 断言使用同一已验证的回环 Redis 端点。
带 query 的 `jdbc:mysql://127.0.0.1:3306/resume?target=mysql.example.test` 回归在旧实现中会进入
服务检查，在修复后于健康检查/启动/写入之前输出 `SKIP targets=UNSAFE_LOCAL_TARGET`。

Playwright 的 test-only Vite server 现在把通过精确回环校验的 `E2E_API_BASE_URL` 作为
`VITE_API_BASE_URL` 传给实际前端流量。一个真实渲染的注册表单请求证明：使用 `:18080` 时，
旧配置错误发送到 `:8080`，修复后发送到 `:18080/api/v2/auth/register`。受保护浏览器生命周期
新增第二个精确回环 `E2E_FAILURE_PROVIDER_URL` 前置条件，并覆盖 UI 可见的初始发布、变更标题的
DOCX 失败重新匹配、旧报告/有效行保留、重复候选报告、软删除、标题复用和恢复冲突；没有这些
许可的回环服务商时，它在注册前跳过，不将 skip 记为实时生命周期证据。

| 日期 | 命令 | 退出码 | 可观察结果 | 限制 |
| --- | --- | ---: | --- | --- |
| 2026-09-02 | `py -3.11 -m pytest tests/integration/assert_mvp_flow.py -q` | 0 | 17 passed；包括 `MVP_REDIS_HOST` 与 JDBC runner 回归 | 不执行 `--live` 服务流程 |
| 2026-09-02 | `E2E_API_BASE_URL=http://127.0.0.1:18080 corepack pnpm exec playwright test e2e/resume-lifecycle.spec.ts e2e/login-profiles-rendering.spec.ts --workers=1` | 0 | 4 passed，真实注册请求到规范 `:18080`；生命周期在注册前跳过 | 未提供许可成功/失败服务商，未证明完整 UI 生命周期 |
| 2026-09-02 | `corepack pnpm build`（`front`） | 0 | Vue 类型检查与生产构建完成 | 既有 `@iarna/toml` direct-eval 警告 |
| 2026-09-02 | 欺骗 `MVP_API_BASE_URL=http://127.0.0.1.example.test:8080` 执行 `run_mvp_flow.ps1 -NoStart` | 0 | `SKIP targets=UNSAFE_LOCAL_TARGET` | 仅证明预检拒绝，不是实时流程 |

### 2026-09-02 安全保护第三轮

Python JDBC 预检现在拒绝带 query、fragment 或 userinfo 的字符串、非精确回环主机以及显式
端口 `0`；显式 `0` 不再被错误地当作缺省 `3306`。运行时回归先证明旧实现会对带 query、fragment
和端口 `0` 的 URL 启动模拟 provider，修复后它们在 provider、API client 或 socket 活动之前
返回 `UNSAFE_LOCAL_TARGET`。

Redis 预检不再让安全的 `MVP_REDIS_*` 覆盖并隐藏 `REDIS_*`。两个变量对只要被提供都会各自校验；
不安全或相互冲突的备用目标会拒绝，唯一规范端点才会传入迟到结果的 Redis 清理探针。`REDIS_HOST`
设为远程、而 `MVP_REDIS_HOST` 保持 `127.0.0.1` 的红测已证明旧绕过；修复后无 provider、client
或网络连接发生。

浏览器生命周期的有效简历行不再以标题子串定位。回归用只包含“原始标题 + replacement”的可见行
先失败；修复后的选择器要求 `resumeNNN` 业务 ID 与完整原始标题同时匹配。受保护的实时路径现在
在失败替换后断言原始精确行和最初报告路由仍存在，替换标题的精确行不存在，避免将错误发布的替换
版本当成保留的原始版本。

| 日期 | 命令 | 退出码 | 可观察结果 | 限制 |
| --- | --- | ---: | --- | --- |
| 2026-09-02 | `py -3.11 -m pytest tests/integration/assert_mvp_flow.py -q` | 0 | 22 passed；含 JDBC 和 Redis 运行时绕过回归 | 不执行 `--live` 服务流程 |
| 2026-09-02 | `E2E_API_BASE_URL=http://127.0.0.1:18080 corepack pnpm exec playwright test e2e/resume-lifecycle.spec.ts e2e/login-profiles-rendering.spec.ts --workers=1` | 0 | 5 passed；原始/替换标题行回归及实际 API 目标通过 | 完整生命周期在注册前跳过，因未提供许可回环服务商 |
| 2026-09-02 | `corepack pnpm build`（`front`） | 0 | Vue 类型检查与生产构建完成 | 既有 `@iarna/toml` direct-eval 警告 |

### 2026-09-02 最终复验证

以下命令在完成第 6 轮跨层测试保护修正后重新执行。所有实时写入入口仍由显式
`--live`、精确回环目标和许可测试服务商保护；本表中的跳过不代表实时生命周期已通过。

| 日期 | 命令 | 退出码 | 可观察结果 | 限制 |
| --- | --- | ---: | --- | --- |
| 2026-09-02 | `corepack pnpm --dir contracts run lint` | 0 | v1/v2/v3 OpenAPI 均通过；v3 保留 4 条既有未使用组件警告 | 只验证契约描述，不连接服务 |
| 2026-09-02 | `corepack pnpm --dir contracts exec node scripts/validate-contracts.mjs` | 0 | v1、v2 和 v3 有效样例通过，预期无效样例被拒绝 | 契约样例不是实时服务调用 |
| 2026-09-02 | `mvn -q test`（`back/java`） | 0 | Java 单元测试与 HTTP 边界测试通过 | Mockito/JDK 动态 agent 警告不影响退出码；不构成真实 MySQL/Redis 证明 |
| 2026-09-02 | `py -3.11 -m pytest tests -q`（`back/python`） | 0 | 102 项通过 | 1 条第三方 Starlette/httpx 弃用警告；不调用外部模型 |
| 2026-09-02 | `py -3.11 -m pytest tests/integration -q` | 0 | 6 项本地受控集成守卫通过 | 不执行实时服务流程 |
| 2026-09-02 | `py -3.11 tests/integration/assert_mvp_flow.py` | 0 | 输出 `SKIP live` | 必须显式传入完整受控运行时配置才会进入实时流程 |
| 2026-09-02 | `corepack pnpm --dir front test` | 0 | 23 个测试文件、126 项通过 | jsdom，不是浏览器服务联调 |
| 2026-09-02 | `corepack pnpm --dir front build` | 0 | Vue 类型检查与生产构建完成 | 既有 `@iarna/toml` direct-eval 警告不影响退出码 |
| 2026-09-02 | `corepack pnpm --dir front test:browser -- resume-lifecycle.spec.ts login-profiles-rendering.spec.ts` | 0 | 5 项通过；登录后模型配置页与 v3 选择器/受控 API 目标回归均通过 | 1 项完整生命周期用例因没有提供许可的回环 Java、Python、MySQL、Redis、成功服务商和失败服务商而安全跳过 |

因此，当前实现已具备完整的离线、边界和受保护浏览器证据；完整实时 v3 生命周期仍需要在
明确配置的本机回环测试环境中重新执行，才能证明初始发布、失败替换、重复标题报告、删除后标题
复用和恢复冲突处于同一次成功运行中。

### 2026-09-03 DeepSeek 结构化解析复验证

本轮针对真实中文岗位描述的解析失败，使用数据库中已有任务做了脱敏直连回放。回放只读取任务、
加密简历和模型配置，不发送 Java 回调，也不修改 MySQL 或 Redis：

| 任务 | 岗位描述 | 简历输入 | 模型结果 |
| --- | ---: | ---: | --- |
| `task058` | 160 字 | DOCX，32104 字节，80 条证据 | `SUCCEEDED`，3 条要求、3 条建议 |
| `task057` | 351 字 | DOCX，32104 字节 | `SUCCEEDED`，9 条要求、8 条建议 |

回放使用 `response_format=json_object`、明确 JSON system prompt、DeepSeek v4 思考模式自动关闭、
100000 输出 token 配置和本地证据/综合分校验。当前 `8000` 端口上的旧 Python 进程早于本轮代码修改
启动；要让修复进入真实请求，必须先停止旧进程并重新启动 Python，同时重启已运行的 Java 服务。

### 2026-09-04 软删除恢复回显修复（用户验证待完成）

本次实现补齐了历史简历恢复的有效性边界。Flyway V15 只为同时具备成功任务、持久化结果和
至少一条证据的历史记录回填有效修订指针；没有有效匹配版本的记录保持不可展示，恢复接口返回
`409/RESUME_NOT_EFFECTIVE`。恢复成功后仍按 `status=0`、`ACTIVE`、有效修订指针和缓存截止时间
进入 `/api/v3/resumes`；同一用户已有有效标题时返回 `409/DUPLICATE_RESUME_TITLE`，前端保留
恢复行和弹窗并显示明确提示。

已完成结构性检查：后端恢复路径、JPA/内存仓储过滤条件、v2/v3 OpenAPI 枚举与 fixtures、前端
错误映射和 V15 迁移文件保持一致。按用户约定，本轮未运行完整单元、集成、端到端或真实模型测试；
这些测试状态为 `pending user validation`。

## 残余风险与后续工作

- 根据产品决策，管理员注册仍然开放；在共享或公共部署前必须加以限制。
- 2026-08-28 的真实本地 MySQL/Redis 冒烟是 V8 前的历史证据。2026-08-31 已确认 V7/V8/V9
  成功登记、可读 ID、任务 JPA 合并竞态修复、HTTP/1.1 内部交接以及受控任务/回调流程；真实
  DeepSeek 脱敏回放之外的其他真实外部模型、定时过期时钟推进和多实例并发压力仍未验证。
- 缓存过期和软删除会有意保留 MySQL 中的加密简历数据。数据库管理员的删除流程、访问控制、
  备份和审计保留不属于本应用切片。
- 其他真实模型服务的延迟、格式错误、速率限制、成本、事实质量、公平性和安全审查尚未验证。
  外部模型流量必须使用已授权的模型服务，并遵循文档规定的脱敏边界。
- PDF 解析、其他岗位族、导出、向量检索、面试流程、集群化和
  生产部署加固不在本 MVP 范围内。
