# 字符串业务 ID v2 与登录修复实施计划

> **面向智能体执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或
> `superpowers:executing-plans`，按任务逐项实施本计划。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 将所有持久化业务主键和外键迁移为 `user001`/`resume001` 等前缀字符串，发布
v2 公共与内部契约，并修复注册后用户名/邮箱登录失败的链路。

**架构：** MySQL 通过 `id_sequences` 行锁提供事务序列，Java 生成并拥有所有业务 ID；
Spring Boot 是唯一的授权、状态和持久化入口。v2 公共 API 和 Java-Python 内部信封使用
字符串业务 ID，v1 契约保留为历史快照。Redis 使用新的 v2 简历键空间并在启动时清理旧键。

**技术栈：** JDK 21、Spring Boot 3.4、Spring Data JPA、Flyway、MySQL 8.4、Redis 7、
FastAPI/Pydantic、Vue 3、TypeScript、Vitest、pytest、OpenAPI 3.1、JSON Schema 2020-12。

**规范：** `docs/superpowers/specs/2026-08-31-string-id-v2-and-auth-design.md`

## 全局约束

- `users.id`、`llm_profiles.id`、`resumes.id`、`analysis_tasks.id`、`analysis_evidence.id`、
  `analysis_results.id`、`analysis_callback_receipts.callback_id` 和
  `resume_recovery_audit.id` 均为 `VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin`，
  格式为各自前缀加至少三位数字。
- `correlationId` 保持 UUID；`idempotencyKey`、回调令牌、密码、API 密钥和简历正文不改语义。
- v2 公共路由使用 `/api/v2/...`，内部路由使用 `/internal/v2/...`；v1 制品不被 v2 客户端调用。
- Java 是唯一公共业务、授权、MySQL 和 Redis 写入口；Python 不登录、不写数据库、不写 Redis。
- Java 运行 JDK 21；Python 测试和服务使用 Python 3.11；前端 API 默认指向
  `http://127.0.0.1:8080`。
- 密码继续使用 BCrypt；API 密钥和简历内容继续 AES-GCM 加密，响应和日志不得回显秘密。
- 简历 7/30 天归档、软删除 `status`、恢复权限、回调幂等和迟到回调阻断规则保持不变。
- 真实 MySQL V8 是不可自动回滚的 DDL 操作；执行前必须备份并停止写入，计划执行阶段只生成
  并测试脚本，未经单独确认不得对用户数据库运行破坏性迁移。

---

### 任务 1：冻结 v2 公共/内部契约与 fixtures

**依赖：** 无。此任务完成并审查通过后，其他实现任务才可开始。

**文件：**
- 新建：`contracts/openapi/v2/openapi.yaml`
- 新建：`contracts/internal/v2/analysis-job.schema.json`
- 新建：`contracts/internal/v2/analysis-callback.schema.json`
- 新建：`contracts/fixtures/v2/auth-register-valid.json`
- 新建：`contracts/fixtures/v2/llm-profile-valid.json`
- 新建：`contracts/fixtures/v2/match-request-valid.json`
- 新建：`contracts/fixtures/v2/match-request-invalid.json`
- 新建：`contracts/fixtures/v2/analysis-job-valid.json`
- 新建：`contracts/fixtures/v2/callback-valid.json`
- 新建：`contracts/fixtures/v2/callback-duplicate.json`
- 新建：`contracts/fixtures/v2/callback-stale.json`
- 新建：`contracts/fixtures/v2/callback-after-soft-delete.json`
- 新建：`contracts/fixtures/v2/error-envelope.json`
- 修改：`contracts/scripts/validate-contracts.mjs`
- 修改：`contracts/package.json`
- 修改：`contracts/README.md`

**接口：**
- 产生 `/api/v2` 的公共路径和字符串 ID schema；产生 `/internal/v2` 的任务/回调 schema。
- 所有业务 ID 使用精确前缀正则：`user[0-9]{3,}`、`profile[0-9]{3,}`、`resume[0-9]{3,}`、
  `task[0-9]{3,}`、`evidence[0-9]{3,}`、`callback[0-9]{3,}`；结果/审计使用
  `result[0-9]{3,}`/`audit[0-9]{3,}`。追踪 `correlationId` 仍是 UUID。
- v2 `analysis-job` 新增必填 `callbackId`，Python 必须原样回传。

- [ ] **步骤 1：先编写会失败的 v2 校验断言。**

在 `contracts/scripts/validate-contracts.mjs` 中先加入对 v2 路径和 fixture 的读取，加入以下
断言目标：

~~~js
const v2User = validateV2('User')
if (!v2User({ id: 'user001', username: 'u', email: 'u@example.test', role: 'USER', createdAt: new Date().toISOString() })) {
  throw new Error('v2 user001 must be valid')
}
if (v2User({ id: '550e8400-e29b-41d4-a716-446655440000', username: 'u', email: 'u@example.test', role: 'USER', createdAt: new Date().toISOString() })) {
  throw new Error('UUID user id must be rejected by v2')
}
~~~

- [ ] **步骤 2：运行校验并确认按预期失败。**

运行：`corepack pnpm --dir contracts run validate`

预期：失败，原因是 v2 schema 和 `validateV2` 尚不存在，而不是 Node 语法错误。

- [ ] **步骤 3：复制并修改 OpenAPI。**

从 v1 复制完整路径和组件到 v2，所有路径前缀改为 `/api/v2`，`info.version` 改为 `2.0.0`。
将 `ProfileId`、`ResumeId`、`TaskId`、用户/简历/任务/证据/结果对象中的业务 ID 改为对应正则；
`ownerId` 使用 `user[0-9]{3,}`；`correlationId` 保持 `format: uuid`。除 ID 类型和版本路径外，
状态枚举、删除确认短语、错误码、权限和字段必填性逐字保持 v1 语义。

- [ ] **步骤 4：创建内部 v2 schema。**

从 internal v1 复制并把 `taskId`、`callbackId`、`evidenceId`、`evidenceIds`、
`requirementId`、`suggestionId` 改为字符串模式；`analysis-job` 增加：

~~~json
"callbackId": { "type": "string", "pattern": "^callback[0-9]{3,}$" }
~~~

保留 `correlationId` 的 UUID 格式、额外字段拒绝、脱敏和回调状态约束。

- [ ] **步骤 5：新增 v2 fixtures 并补齐无效样例。**

将 v1 fixtures 复制到 `fixtures/v2`，把所有持久化 ID 替换为匹配前缀的值；在
`match-request-invalid.json` 与一个新增的 callback invalid 分支中分别使用 UUID/错误前缀，
确保验证器确实拒绝旧格式。 `callback-duplicate` 保持相同 `callbackId`/`payloadHash`，
`callback-after-soft-delete` 保持 `TASK_GONE` 场景。

- [ ] **步骤 6：实现双版本静态校验并运行。**

验证器同时检查 v1 历史 fixtures 和 v2 fixtures，且对 v2 运行 OpenAPI bundle、内部 schema、
有效/无效回调、归档状态和删除上下文断言。运行：

~~~powershell
corepack pnpm --dir contracts run lint
corepack pnpm --dir contracts run validate
~~~

预期：两条命令均退出 0，并打印 v1/v2 各 fixture 的验证结果。

- [ ] **步骤 7：更新契约 README 并提交。**

写明 v1 为历史快照、v2 为运行时权威、ID 正则目录、`callbackId` 生成责任和迁移后的旧 JWT
失效行为。提交：

~~~powershell
git add contracts
git commit -m "feat(contract): publish string-id v2 schemas"
~~~

---

### 任务 2：数据库 v2 快照与 V8 映射迁移

**依赖：** 任务 1 的 ID 目录已冻结。

**文件：**
- 新建：`back/java/src/main/resources/db/migration/V8__string_business_ids.sql`
- 修改：`database/resume_thinking_schema.sql`
- 修改：`README.md`
- 修改：`docs/项目文件说明.md`
- 新建：`back/java/src/test/java/com/resumethinking/platform/ids/StringIdMigrationSchemaTest.java`

**接口：**
- 产出字符串主键、字符串外键和 `id_sequences` 的 MySQL 结构。
- V8 将当前 V1-V7 最终结构中的所有行确定性映射到 v2 编号，保留密文、状态和业务内容。

- [ ] **步骤 1：写迁移结构检查测试。**

新增 `StringIdMigrationSchemaTest`，读取 V8 和手工 schema 文本，断言包含全部八个前缀、
`id_sequences`、`ascii_bin`、旧外键删除/重建语句，并断言没有将 `password_hash`、
`raw_content_ciphertext` 或 `payload_json` 写入日志/临时映射。测试先失败，因为 V8 尚不存在。

- [ ] **步骤 2：运行测试确认失败原因。**

运行：`back\\java\\mvnw.cmd -Dtest=StringIdMigrationSchemaTest test`

预期：只因缺少 V8/新 schema 的断言失败。

- [ ] **步骤 3：编写 V8 映射表和临时列。**

按设计文档实现以下固定顺序：

1. 建立旧二进制/数字 ID 到新字符串的临时映射表，使用
   `ROW_NUMBER() OVER (ORDER BY created_at, old_id)`（结果/审计按原数字 ID，回调按
   `received_at, callback_id`）。
2. 为 users、profiles、resumes、tasks、evidence、results、receipts、audit 添加 v2 临时 ID
   与全部关联临时列，并以映射表更新；任何映射缺失都使迁移失败。
3. 删除已命名外键、唯一约束和旧索引，删除旧主键列/外键列，重命名临时列，重建主键、外键、
   唯一约束及 `ix_*` 查询索引。
4. 保留 `resume_recovery_audit.correlation_id BINARY(16)`，其余用户/简历/任务关联列全部为
   `VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin`。
5. 创建并回填 `id_sequences`，每行的 `next_value` 等于对应前缀最大数字后缀加一；删除临时
   映射表。

迁移脚本必须显式列出当前已知的约束名（`fk_*`、`uq_analysis_task_owner_key`、`ix_*`），
不使用未验证的动态 SQL 或宽泛 `DROP DATABASE`。

- [ ] **步骤 4：同步新安装快照。**

将 `database/resume_thinking_schema.sql` 改为直接创建 v2 字符串列、`id_sequences` 和中文
字段备注；去除 UUID_TO_BIN/BINARY(16) 业务主键。保持加密列、生命周期列、CHECK 约束和审计
字段完整。

- [ ] **步骤 5：运行结构测试和 SQL 静态检查。**

运行：

~~~powershell
back\\java\\mvnw.cmd -Dtest=StringIdMigrationSchemaTest test
git diff --check
~~~

预期：测试通过；脚本中没有宽泛删除数据库/表的命令。此任务不连接用户数据库。

- [ ] **步骤 6：更新迁移说明并提交。**

README 明确区分新库直接执行 v2 快照与旧库由 Flyway V8 迁移，写出备份、停写、旧 JWT 失效和
失败恢复要求。提交：

~~~powershell
git add back/java/src/main/resources/db/migration/V8__string_business_ids.sql database/resume_thinking_schema.sql README.md docs/项目文件说明.md back/java/src/test/java/com/resumethinking/platform/ids/StringIdMigrationSchemaTest.java
git commit -m "feat(db): add string business id migration"
~~~

---

### 任务 3：Java ID 生成器、实体和仓储类型迁移

**依赖：** 任务 1、任务 2；不修改 Controller 路由或 Python 文件。

**文件：**
- 新建：`back/java/src/main/java/com/resumethinking/platform/ids/BusinessIdType.java`
- 新建：`back/java/src/main/java/com/resumethinking/platform/ids/ReadableIdGenerator.java`
- 新建：`back/java/src/main/java/com/resumethinking/platform/ids/JdbcReadableIdGenerator.java`
- 新建：`back/java/src/main/java/com/resumethinking/platform/ids/InMemoryReadableIdGenerator.java`
- 新建：`back/java/src/test/java/com/resumethinking/platform/ids/ReadableIdGeneratorTest.java`
- 修改：所有 auth、profiles、resumes、matching 实体/仓储的业务 ID 类型和内存测试存储。

**接口：**

~~~java
public enum BusinessIdType {
    USER("user"), PROFILE("profile"), RESUME("resume"), TASK("task"),
    EVIDENCE("evidence"), RESULT("result"), CALLBACK("callback"), AUDIT("audit");
    public final String prefix;
}
public interface ReadableIdGenerator { String next(BusinessIdType type); }
~~~

- [ ] **步骤 1：写生成器失败测试。**

测试首个值、补零、超过 999、每种前缀独立计数、非法序列值和并发调用唯一性；断言
`InMemoryReadableIdGenerator.next(USER)` 依次为 `user001`、`user002`、并在 1000 后为
`user1000`。先运行并确认缺少类导致失败。

- [ ] **步骤 2：实现内存生成器和 ID 格式校验。**

内存实现只用于测试，使用每种类型独立的 `AtomicLong`；公共静态校验方法拒绝错误前缀、
小于三位数字、空值和超过 64 字符。

- [ ] **步骤 3：实现 JDBC 事务生成器。**

使用 `JdbcTemplate`：在当前写事务中插入缺省 sequence 行（`next_value=1`），对行执行
`SELECT next_value ... FOR UPDATE`，更新加一并返回前缀+三位补零。找不到事务或序列值小于 1
时抛出明确异常；不允许生产路径退回随机 UUID。

- [ ] **步骤 4：迁移实体和仓储泛型。**

将业务 ID 字段、JPA `@Id`、外键、Repository 泛型、锁查询参数和内存仓储 Map 全部改为
`String`；去除生产构造器中的 `UUID.randomUUID()`。保留 `correlationId` 为 UUID。为 JPA
字符串列设置 `length=64`、`columnDefinition` 和非空约束，保持原有领域状态方法不变。

- [ ] **步骤 5：让生成器测试和全量编译通过。**

运行：

~~~powershell
back\\java\\mvnw.cmd -Dtest=ReadableIdGeneratorTest test
back\\java\\mvnw.cmd -q -DskipTests compile
~~~

预期：生成器测试通过；若旧测试仍传 UUID，必须在本任务内改为合法前缀字符串或显式内存
生成器，不得把 UUID 转字符串伪装成 v2 业务 ID。

- [ ] **步骤 6：提交实体/生成器基础。**

~~~powershell
git add back/java/src/main/java/com/resumethinking/platform/ids back/java/src/main/java/com/resumethinking/platform/auth back/java/src/main/java/com/resumethinking/platform/profiles back/java/src/main/java/com/resumethinking/platform/resumes back/java/src/main/java/com/resumethinking/platform/matching back/java/src/test/java/com/resumethinking/platform/ids
git commit -m "refactor(java): migrate domain ids to strings"
~~~

---

### 任务 4：Java 认证、模型配置、简历生命周期与 Redis v2

**依赖：** 任务 3；不修改 matching 包内部回调 DTO（任务 5 负责）。

**文件：**
- 修改：auth、profiles、resumes、config 包的服务/Controller/测试
- 新建：`back/java/src/test/java/com/resumethinking/platform/auth/AuthLoginRegressionTest.java`

**接口：**
- 公共路由统一 `/api/v2`；所有权参数和路径变量为 `String`。
- `AuthService` 注册/登录在查询前执行 `trim`，邮箱再执行 `Locale.ROOT` 小写；密码不 trim。
- `JwtService.Claims.subject` 为 `String`，仅接受 `user[0-9]{3,}`；旧 UUID token 解析失败。
- `ResumeCache` 键为 `resume:v2:view:<resumeId>`，提供一次性 `clearLegacyKeys()`。

- [ ] **步骤 1：先写登录回归测试。**

在 `AuthLoginRegressionTest` 覆盖：注册后用户名登录、邮箱登录、大小写邮箱、标识符前后空格、
错误密码、旧 UUID JWT 被拒绝；断言注册保存的是 BCrypt 哈希且返回 `user001` 形式 ID。运行：

~~~powershell
back\\java\\mvnw.cmd -Dtest=AuthLoginRegressionTest test
~~~

预期：登录规范化和字符串 JWT 尚未实现，测试失败。

- [ ] **步骤 2：实现认证规范化和字符串 JWT。**

为 AuthService 注入 `ReadableIdGenerator`，注册先规范化并检查唯一性，再分配 USER ID；登录
按规范化标识符查找并使用 BCrypt `matches`。JWT JSON 的 `sub` 写入字符串，解析时验证用户
前缀和过期时间；错误信封仍使用 UUID `correlationId`。

- [ ] **步骤 3：切换模型配置与生命周期服务。**

为 profile/resume 创建路径注入生成器并在事务内分配 `profile`/`resume` ID；改写所有
Controller 的 `@RequestAttribute`、`@PathVariable` 和请求 DTO 为 String；保持管理员/普通用户
恢复与删除状态机、版本检查、审计和 7/30 天期限不变。

- [ ] **步骤 4：切换 Redis 键并清理旧键。**

Redis 适配器只写 `resume:v2:view:<id>`；应用启动后执行一次受控 `SCAN resume:view:*` 删除
旧键，捕获 Redis 不可用异常而不回滚 MySQL。补充测试断言活动记录只出现 v2 键，软删除/归档
删除 v2 键，旧命名空间不会被读取。

- [ ] **步骤 5：运行 Java 认证/生命周期测试和编译。**

运行：

~~~powershell
back\\java\\mvnw.cmd -Dtest=AuthServiceTest,AuthLoginRegressionTest,ResumeLifecycleServiceTest,ResumeControllerTest test
back\\java\\mvnw.cmd -q -DskipTests compile
~~~

预期：全部通过，且失败响应不包含密码、token 或旧 UUID 业务 ID。

- [ ] **步骤 6：提交本任务。**

~~~powershell
git add back/java/src/main/java/com/resumethinking/platform/auth back/java/src/main/java/com/resumethinking/platform/config back/java/src/main/java/com/resumethinking/platform/profiles back/java/src/main/java/com/resumethinking/platform/resumes back/java/src/test/java/com/resumethinking/platform/auth back/java/src/test/java/com/resumethinking/platform/resumes
git commit -m "feat(java): add v2 auth and lifecycle ids"
~~~

---

### 任务 5：Java 匹配任务、回调和结果的 v2 字符串 ID

**依赖：** 任务 3、任务 4；仅修改 matching 包、内部安全路径和对应测试。

**文件：**
- 修改：matching 包全部服务、DTO、JPA 适配器和测试
- 修改：`back/java/src/main/java/com/resumethinking/platform/config/SecurityConfig.java`

**接口：**
- `/api/v2/match-tasks` 和 `/internal/v2/analysis-results` 使用字符串 task/resume/profile/
  evidence/callback ID。
- Java 在创建任务事务内生成 task/evidence/callback ID，并将 callback ID 放入 v2 job；Python
  回传相同 callback ID。
- RFC 8785 payload hash 的字段名/排序/数字规则不变，只把业务 ID 序列化为字符串。

- [ ] **步骤 1：写字符串回调失败测试。**

将 callback 测试 fixture 改为 `task001`/`callback001`/`evidence001`，先增加断言：旧 UUID
回调被 schema/服务拒绝；同 callback ID 同 hash 仍返回 replay；删除/归档任务仍返回 TASK_GONE。

- [ ] **步骤 2：实现 DTO、hash 和任务生成。**

把 `AnalysisCallbackRequest`、证据集合、任务命令和响应改为 String；`CallbackPayloadHash`
对字符串 ID 调用原值，不再 `.toString()` UUID。 `MatchTaskService` 注入生成器，按顺序分配
task/evidence/callback，保持现有锁、幂等和回调接受条件。

- [ ] **步骤 3：实现结果/收据/审计 ID 持久化。**

结果和收据 JPA 适配器使用字符串主键；审计适配器从生成器分配 `auditNNN`。不得改变结果
JSON 中的证据验证、脱敏和状态转换。

- [ ] **步骤 4：切换内部路径和任务客户端。**

PythonAnalysisClient 发往 `/internal/v2/analysis-jobs`，回调 URL 默认
`/internal/v2/analysis-results`；SecurityConfig 只把 v2 回调作为内部服务路径。旧 v1 路径
返回未授权或不支持，不得把旧 UUID payload 当作 v2 接受。

- [ ] **步骤 5：运行 matching 测试、契约校验和编译。**

运行：

~~~powershell
back\\java\\mvnw.cmd -Dtest="*Match*Test,*Callback*Test,InternalCallbackSecurityTest" test
corepack pnpm --dir contracts run validate
back\\java\\mvnw.cmd -q -DskipTests compile
~~~

预期：任务幂等、删除竞态、证据边界和字符串格式测试通过。

- [ ] **步骤 6：提交本任务。**

~~~powershell
git add back/java/src/main/java/com/resumethinking/platform/matching back/java/src/main/java/com/resumethinking/platform/config/SecurityConfig.java back/java/src/test/java/com/resumethinking/platform/matching back/java/src/test/java/com/resumethinking/platform/config/InternalCallbackSecurityTest.java
git commit -m "feat(java): migrate matching callbacks to v2 ids"
~~~

---

### 任务 6：Python v2 内部模型、路由与回调 ID

**依赖：** 任务 1、任务 5 的 Java v2 内部字段定义。

**文件：**
- 修改：`back/python/app/models.py`
- 修改：`back/python/app/main.py`
- 修改：`back/python/app/analysis_service.py`
- 修改：`back/python/app/callback_client.py`
- 修改：`back/python/app/settings.py`
- 修改：`back/python/tests/test_contract_fixtures.py`
- 修改：`back/python/tests/test_analysis_service.py`
- 修改：`back/python/tests/test_callback_client.py`
- 新建：`back/python/tests/test_v2_ids.py`

**接口：**
- Pydantic v2 模型对业务 ID 使用前缀正则字符串，对 `correlationId` 仍使用 UUID。
- `/internal/v2/analysis-jobs` 接收 Java 生成的 callbackId；分析回调原样携带它。
- 岗位要求/建议编号在单个结果内按 `requirement001`/`suggestion001` 生成，不作为数据库主键。

- [ ] **步骤 1：写 v2 ID 校验失败测试。**

`test_v2_ids.py` 用 `task001`、`callback001`、`evidence001` 验证成功，用 UUID 和错误前缀验证
`ValidationError`；用一个 job 断言 callbackId 未被分析服务替换。先运行确认当前 UUID 模型使
测试失败。

- [ ] **步骤 2：实现模型和路由。**

将相关字段类型改为 `str` 并添加 `StringConstraints(pattern=...)` 或等价 field validator；
把 FastAPI 路由改为 `/internal/v2/analysis-jobs`，错误信封仍输出 UUID correlationId。

- [ ] **步骤 3：实现 callbackId 透传与任务内编号。**

`analyze_job` 的 callback 基础对象使用 `job.callback_id`，不再 `uuid4()`；模型输出经校验后
按结果顺序重写 requirement/suggestion IDs 为任务内前缀编号，同时保持 evidence IDs 必须
属于 Java allowedEvidence。重试客户端保留原 callbackId 和 payloadHash。

- [ ] **步骤 4：运行 Python 测试。**

运行：

~~~powershell
C:\\Users\\theking.guo\\AppData\\Local\\Programs\\Python\\Python311\\python.exe -m pytest back/python/tests -q
corepack pnpm --dir contracts run validate
~~~

预期：现有脱敏、解析、匹配、模型失败和回调重试测试全部通过，新增 v2 ID 测试通过。

- [ ] **步骤 5：提交本任务。**

~~~powershell
git add back/python/app back/python/tests
git commit -m "feat(python): support internal v2 string ids"
~~~

---

### 任务 7：Vue v2 API、可读编号显示与登录体验

**依赖：** 任务 1、任务 4/5 的公共字段和路径。

**文件：**
- 修改：`front/src/api/http.ts`
- 修改：`front/src/api/contracts.ts`
- 修改：`front/src/api/lifecycle.ts`
- 修改：`front/src/stores/auth.ts`
- 修改：`front/src/views/LoginView.vue`
- 修改：`front/src/views/RegisterView.vue`
- 修改：`front/src/views/ModelProfilesView.vue`
- 修改：`front/src/views/ResumeListView.vue`
- 修改：`front/src/views/RecoveryView.vue`
- 修改：`front/src/views/AdminRecoveryView.vue`
- 修改：`front/src/views/UploadMatchView.vue`
- 修改：`front/src/views/MatchResultView.vue`
- 修改：`front/src/router/index.ts`
- 修改：`front/src/api/*.spec.ts`、相关 view/component spec
- 修改：`front/.env.example`、`front/README.md`

**接口：**
- 所有 API URL 使用 `/api/v2`；TypeScript 业务 ID 是受限字符串类型（运行时错误仍由后端
  返回安全信封）。页面直接显示 `user001`/`resume001` 等 `id`。
- Pinia 注册/登录发送前规范化 identifier；新增明确的退出登录控件并清理 token/身份缓存。

- [ ] **步骤 1：写前端失败测试。**

在 `http.spec.ts`/`lifecycle.spec.ts`/`LoginView.spec.ts` 增加断言：请求 URL 为 `/api/v2`；
登录 payload 将 `\"  USER@Example.COM  \"` 规范化为 `user@example.com`；成功登录保存字符串
用户 ID；退出后 token 和 identity 均为空。先运行确认失败。

- [ ] **步骤 2：切换 API 路径和类型。**

更新 lifecycle、profile、auth store 的所有 URL 和接口类型；保持错误码映射、401 会话清理和
上传/删除/恢复请求体不变。为 `UserId`、`ResumeId`、`TaskId` 等类型提供前缀别名，避免任意
字符串误传到 UI 关键操作。

- [ ] **步骤 3：实现登录规范化与退出入口。**

store 和 LoginView 双重规范化（store 是权威）；密码原样发送。导航栏/工作区加入“退出登录”
按钮，调用 `auth.logout()` 后跳转 `/login`。不在页面显示 JWT 内容。

- [ ] **步骤 4：显示可读 ID 和 v2 状态。**

列表、恢复、匹配结果和模型配置页面将 ID 作为普通可读文本显示；删除确认仍要求精确短语
`确认删除简历`；归档、软删除、无数据、失败状态文案继续中文化且不泄露其他用户信息。

- [ ] **步骤 5：运行前端测试和构建。**

运行：

~~~powershell
corepack pnpm --dir front exec vitest run
corepack pnpm --dir front run build
~~~

预期：全部测试通过，生产构建退出 0。

- [ ] **步骤 6：提交本任务。**

~~~powershell
git add front
git commit -m "feat(web): switch to v2 readable ids and login flow"
~~~

---

### 任务 8：跨服务联调、环境文档与最终验证

**依赖：** 任务 1-7 全部完成并通过各自审查。

**文件：**
- 修改：`.env.example`
- 修改：`back/java/src/main/resources/application-local.yml.example`
- 修改：`back/java/src/main/resources/application-local.yml`
- 修改：`tests/integration/assert_mvp_flow.py`
- 修改：`tests/integration/run_mvp_flow.ps1`
- 修改：`tests/integration/task-10-report.md`
- 修改：`front/e2e/resume-lifecycle.spec.ts`
- 修改：`README.md`
- 修改：`docs/verification/mvp-evidence.md`
- 修改：`docs/项目文件说明.md`

**接口：**
- 本地环境使用 v2 callback URL；集成脚本执行注册 -> 退出 -> 用户名/邮箱登录 -> 上传/匹配
  -> 删除/归档/恢复，并只打印可读 ID 和状态。
- 产出不含密钥、JWT、简历正文或 API key 的验证证据。

- [ ] **步骤 1：先更新离线集成断言。**

把 `assert_mvp_flow.py`、fixtures 和 Playwright 请求路径切换到 v2；加入旧 UUID 业务 ID 被拒绝、
新 ID 正则通过、登录两种 identifier 均成功的断言。运行并确认在实现未整合前失败。

- [ ] **步骤 2：更新环境与启动说明。**

将示例中的 `MATCHING_CALLBACK_URL` 改为 `/internal/v2/analysis-results`，说明迁移后需重新
登录、先备份再执行 V8，并保留用户现有 `.env` 的秘密值不变。只更新本地 `.env` 中相关非秘密
URL 行，不打印或重置密码、JWT、AES key、内部 token。

- [ ] **步骤 3：运行完整离线验证。**

运行：

~~~powershell
corepack pnpm --dir contracts run lint
corepack pnpm --dir contracts run validate
back\\java\\mvnw.cmd test
C:\\Users\\theking.guo\\AppData\\Local\\Programs\\Python\\Python311\\python.exe -m pytest back/python/tests tests/integration -q
corepack pnpm --dir front exec vitest run
corepack pnpm --dir front run build
~~~

预期：所有命令退出 0；若环境缺少依赖，记录实际 SKIP 原因，不把未运行的联调写成通过。

- [ ] **步骤 4：在用户明确允许后执行真实数据库迁移。**

先让用户确认已停止 Java/Python 写入并完成 `resume_thinking` 备份；随后由运维执行 Flyway V8，
检查八张表行数、主键/外键类型、序列最大值、中文备注、Redis 旧键清理和两种登录。迁移失败
立即停止，不在半套结构上手工补列，按备份恢复并记录证据。

- [ ] **步骤 5：更新验证报告并提交。**

报告明确分开已实现、已测试、真实迁移是否执行、外部模型是否模拟、旧 token 失效和残余风险。
提交：

~~~powershell
git add .env.example back/java/src/main/resources/application-local.yml.example back/java/src/main/resources/application-local.yml tests/integration front/e2e README.md docs/verification docs/项目文件说明.md
git commit -m "test: verify string-id v2 integration"
~~~

---

## 任务依赖与审查门

~~~text
任务1 契约冻结
   |
   +--> 任务2 数据库迁移
   |
   +--> 任务3 Java ID 基础
             |
             +--> 任务4 Java 认证/生命周期/Redis
             |          |
             |          +--> 任务5 Java matching/callback
             |                         |
             +-------------------------+--> 任务6 Python
             +------------------------------> 任务7 前端
                                               |
                                               +--> 任务8 联调/发布
~~~

每个任务必须经过：实现者自测 -> 独立任务审查（规范符合性和代码质量）-> 必要修复与复审，
才能进入下一任务。所有审查意见和决定写入该计划的 SDD ledger；不得以“编译通过”替代契约、
授权、迁移和登录证据。

## 计划自审

- **规范覆盖：** 设计文档的 ID 目录、事务序列、V8 映射、v2 契约、Redis 命名空间、登录规范化、
  旧 JWT 失效、Python callbackId 透传和前端退出入口分别由任务 1-8 覆盖。
- **文件边界：** 契约只由任务 1 修改；数据库脚本只由任务 2 修改；Java 基础/认证/matching
  按包拆分；Python、前端和联调文件不与前述任务重叠。
- **安全检查：** 没有任务要求明文密码/API key/简历正文；真实 DDL 被单独设为需确认的发布步骤。
- **无占位项：** 任务包含具体文件、ID 字符串、测试命令和预期结果，没有 TBD/TODO 或“以后补充”。
- **类型一致性：** v2 所有持久化业务 ID 在契约、Java、Python、TypeScript 和数据库均为字符串；
  correlationId 始终 UUID；callbackId 由 Java 生成并由 Python 原样回传。

