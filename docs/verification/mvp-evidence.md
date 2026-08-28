# MVP 验证证据

日期：2026-08-28

本报告记录首个简历与岗位匹配切片的证据，并分别说明已实现行为、确定性的离线检查、
尚未验证的实时集成和残余风险。报告不宣称已达到生产可用性，不评价真实模型服务的质量、
公平性，也不宣称会物理删除 MySQL 数据。

## 契约与范围

- `contracts/openapi/v1/openapi.yaml` 以及 `contracts/internal/v1/` 下的 JSON 模式
  是版本化的接口权威来源。
- 发布的岗位族目前仅为 Java 后端开发。保留 `role` 字段，以便后续添加更多岗位族。
- Java/Spring Boot 是唯一的公共业务、授权、持久化和任务状态权威。Python/FastAPI 负责
  提取、脱敏、受控模型调用和回调投递。Vue 是操作客户端。
- 本地目标运行环境为 JDK 21、Maven/Spring Boot 3、Python 3.11、Vue 3、MySQL 8.4 和
  Redis 7。
- MVP 边界：Redis 当前只保存派生的 `resume:view:{resumeId}` 页面缓存。任务进度、幂等
  数据、回调凭据和匹配结果均由 MySQL 权威保存；Redis 任务状态只是文档记录的未来优化，
  当前尚未实现。

## 已实现行为

### 身份与模型配置

- 注册支持 `USER` 和 `ADMIN`；按本阶段决策，暂不限制管理员账号的创建。
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
| `pnpm --dir contracts run lint` | 通过 | OpenAPI v1 语法和代码检查规则 |
| `pnpm --dir contracts run validate` | 通过 | 13 个有效示例，加上预期无效的匹配示例 |
| `E:\maven\...\mvn.cmd clean test`（在 `back/java` 中） | 通过，100 项测试 | Java 单元测试和 HTTP 边界测试；未连接真实 DB/Redis |
| Python 3.11 `pytest back/python/tests tests/integration -q` | 通过，67 项测试 | 脱敏、解析、回调规则、示例和离线流程断言 |
| `pnpm --dir front exec vitest run` | 通过，56 项测试 | jsdom 下的 Vue/API 行为 |
| `pnpm --dir front run build` | 通过 | `vue-tsc` 和 Vite 生产构建 |
| 对 `run_mvp_flow.ps1` 进行 PowerShell AST 解析 | 通过，0 个解析错误 | 仅验证启动器语法 |
| 使用临时模拟令牌执行启动器预检 | 通过 | 输出为通用 `FAIL preflight=CONFIGURATION_OR_SERVICE`；输出中没有模拟令牌 |

离线集成断言覆盖受控 TXT/DOCX/PDF 示例、证据与评分不变量、保留期限计算、回调竞态
示例及已清理的错误行为。它们不会连接 Java、MySQL、Redis 或外部模型服务。

## 本地受控基线、迁移与运行时验证

验证于 2026-08-28 在已授权的本地 MySQL 和 `127.0.0.1:6379` Redis 上完成。未跟踪的
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
| Redis `resume:view:*` 键数 | 2 |
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
`resume:view:*` 键，且均属于 `ACTIVE` 记录。该校验未读取或输出任何账号、简历、加密内容或
缓存值；外部模型、任务派发和回调行为仍未验证。

该运行证明缓存仅保留两个 `ACTIVE` 页面视图；软删除和缓存归档均保留 MySQL 中的加密记录及
审计，不构成物理删除。外部模型集成没有被调用或验证，也没有声称真实模型质量、延迟、成本、
安全性或可用性。

## 残余风险与后续工作

- 根据产品决策，管理员注册仍然开放；在共享或公共部署前必须加以限制。
- 本次真实本地 MySQL/Redis 冒烟已覆盖 Flyway version `6` 基线、条件化种子、JPA 对种子
  数据的持久化读取、两个派生 Redis 页面视图、Java/Python 健康检查及恢复列表授权范围。
  尚未验证定时过期后的归档/缓存驱逐、恢复或删除的持久化与缓存转换、删除与任务回调的并发
  竞态，以及任务派发和回调的端到端行为。
- 缓存过期和软删除会有意保留 MySQL 中的加密简历数据。数据库管理员的删除流程、访问控制、
  备份和审计保留不属于本应用切片。
- 真实模型服务的延迟、格式错误、速率限制、成本、事实质量、公平性和安全审查尚未验证。
  外部模型流量必须使用已授权的模型服务，并遵循文档规定的脱敏边界。
- PDF 解析、其他岗位族、导出、向量检索、面试流程、集群化和
  生产部署加固不在本 MVP 范围内。
