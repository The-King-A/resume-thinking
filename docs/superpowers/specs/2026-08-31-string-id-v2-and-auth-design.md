# 字符串业务 ID v2 与登录修复设计

**状态：** 已获用户批准方案 B，待用户审阅本设计后进入实施规划

**产品来源：** `E:\resume_thinking\ai-resume-job-matching-project.md.docx`

**现有契约来源：** `contracts/openapi/v1/`、`contracts/internal/v1/`、`contracts/fixtures/v1/`

## 1. 目标与非目标

### 目标

1. 将数据库中所有持久化业务主键改为可读、可排序的前缀字符串，例如 `user001`、
   `resume001`、`task001`；查询表的 `id`（或 `callback_id`）时直接得到该值。
2. 将所有引用这些主键的外键、Java/JPA 类型、Python 内部模型、前端类型、Redis 键和
   接口路径同步到同一套字符串 ID 规则。
3. 以独立的 v2 公共契约和内部契约发布破坏性类型变更；v1 文件保留为历史制品，不再作为
   运行时业务接口。
4. 修复注册后无法登录的常见链路问题：标识符首尾空格、邮箱大小写、会话残留和缺少
   登录回归覆盖，并保留 BCrypt 密码校验。
5. 为已有 UUID 数据提供一次可审计的 Flyway 迁移，并在迁移后继续安全生成新编号。

### 非目标

- 不把密码、API 密钥或简历正文改为明文；ID 可读不等于敏感数据可读。
- 不把 `correlationId`、`idempotencyKey`、回调令牌等非业务主键强行改成数据库序号。
- 不提供数据库物理删除 API；既有简历生命周期和权限规则保持不变。
- 不同时维护 v1 和 v2 两套业务写入逻辑；避免两套 ID 语义长期分叉。

## 2. 兼容性决策

这是破坏性变更，v1 中 `id` 的 `format: uuid` 含义不能被静默改写。因此：

- 对外 Java 路由从 `/api/v1/...` 切换为 `/api/v2/...`，响应中的业务 ID 为前缀字符串。
- Java 到 Python 的任务路由从 `/internal/v1/...` 切换为 `/internal/v2/...`，并发布
  `contracts/internal/v2/` 两个 JSON Schema。
- `contracts/openapi/v1/`、`contracts/internal/v1/` 和 `fixtures/v1/` 作为历史快照保留，
  不再由前端或 Python v2 客户端调用。
- 迁移后签发的 JWT `sub` 是 `userNNN`。迁移前签发的 UUID JWT 不再解析，前端收到 401
  后清理本地会话并要求重新登录。
- 版本切换不改变 Java 作为唯一公共授权、任务状态、MySQL 和 Redis 写入方的边界。

## 3. ID 目录与格式

### 3.1 持久化业务 ID

| 表/字段 | 前缀 | 示例 | v2 外部字段 |
| --- | --- | --- | --- |
| `users.id` | `user` | `user001` | `User.id` |
| `llm_profiles.id` | `profile` | `profile001` | `LlmProfile.id` |
| `resumes.id` | `resume` | `resume001` | `Resume.id` |
| `analysis_tasks.id` | `task` | `task001` | `MatchTask.id` |
| `analysis_evidence.id` | `evidence` | `evidence001` | `Evidence.id` |
| `analysis_results.id` | `result` | `result001` | 结果内部主键 |
| `analysis_callback_receipts.callback_id` | `callback` | `callback001` | 内部回调 ID |
| `resume_recovery_audit.id` | `audit` | `audit001` | 审计内部主键 |

每个值匹配 `^prefix[0-9]{3,}$`。前三位用于满足从 `001` 开始的展示要求，超过 999 后
自然扩展为 `1000`、`1001`，不截断也不复用已分配编号。所有 ID 列使用
`VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin`，从而保证外键类型、大小写和排序
完全一致。

### 3.2 非持久化标识

`correlationId` 继续为 UUID，用于日志关联和错误信封；`idempotencyKey`、`callbackToken`
和模型提供商的临时值继续使用原有字符串规则。岗位要求和建议只存在于结构化结果 JSON
中，不新增表或数据库序列；Python 为单个结果生成 `requirement001`、`suggestion001`
这类任务内编号，并在 v2 schema 中校验前缀格式。它们不是跨请求的数据库主键。

## 4. 编号生成与事务语义

MySQL 不支持对带前缀的 `VARCHAR` 列直接使用原生 `AUTO_INCREMENT`。采用数据库持久化的
序列表和 Java 事务生成器：

```sql
CREATE TABLE id_sequences (
    sequence_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    next_value BIGINT NOT NULL
);
```

`ReadableIdGenerator` 在写事务中对对应行执行 `SELECT ... FOR UPDATE`，读取当前值、加一
并返回 `prefix + LPAD(current, 3, '0')`。首次初始化为 1；迁移后按现有数据最大后缀加一。
序列更新与业务写入位于同一事务中，写入回滚时不会留下不可解释的孤立编号；并发请求由行锁
串行化。生产 bean 只允许使用 JDBC 实现，单元测试使用显式的内存实现，不能静默退回随机 ID。

需要生成 ID 的位置：注册用户、模型配置、简历、匹配任务、分析证据、Python 回调 ID、
分析结果实体和生命周期审计实体。Java 在派发任务前生成 `callbackId` 并放入 v2 任务信封，
Python 只回传该值，确保回调收据也遵循数据库序列。

## 5. 数据库迁移

### 5.1 Flyway V8 迁移原则

`V8__string_business_ids.sql` 面向当前 V1-V7 最终结构，按以下顺序执行：

1. 创建临时旧 ID 到新 ID 的映射表。用户、配置、简历、任务、证据和回调按
   `created_at`/`received_at` 加旧 ID 稳定排序；结果和审计按原数字主键排序。映射结果
   固定为 `user001`、`profile001` 等，确保同一数据库重复演练得到同一结果。
2. 创建 `id_sequences`，并为所有表添加临时 v2 ID/外键列；使用映射表回填，不读取或改写
   密码哈希、AES-GCM 密文、nonce、岗位正文或匹配 JSON。
3. 在删除外键、唯一约束和旧索引后，删除旧 `BINARY(16)`/`BIGINT` ID 列，将临时列重命名
   为正式列，重新创建主键、外键、唯一约束和查询索引。
4. 将 `id_sequences.next_value` 设置为每个前缀的最大后缀加一；删除临时映射表。
5. 保留 `resume_recovery_audit.correlation_id` 为 `BINARY(16)`，因为它是追踪号而非业务
   主键；`status`、可见性状态、版本、加密字段和所有时间戳保持原值。

Flyway 执行前必须备份 `resume_thinking` 数据库并停止 Java/Python 写入。MySQL DDL 具有
隐式提交，迁移脚本不宣称可自动回滚；失败时按备份恢复，修正后重新执行。迁移完成后旧 UUID
JWT 失效属于预期行为。

### 5.2 新安装快照

`database/resume_thinking_schema.sql` 同步改为直接创建 v2 字符串列和 `id_sequences`，
不再包含 UUID 主键。手工建表后不需要再执行 V1-V7；README 明确说明新建库和已有库分别
使用快照或 V8 迁移，不得混用。

### 5.3 Redis 迁移

v2 使用键空间 `resume:v2:view:<resumeId>`。Java 启动时由缓存适配器执行一次受控 `SCAN`
并删除旧 `resume:view:*` 键；该操作只影响简历派生视图，不触碰任务或用户数据。所有新建、
恢复、软删除和归档路径只读写 v2 键，Redis 不作为持久化权威。

## 6. Java 服务改造

- 所有实体、命令、仓储泛型、查询参数、锁定查询和 Controller 路径变量的业务 ID 类型改为
  `String`；`UUID` 仅保留 `correlationId` 等追踪值。
- `JwtService.Claims.subject` 改为 String，并只接受 `user` 前缀编号；`SecurityConfig` 的
  `actorId` request attribute、所有权判断和异常处理同步改为 String。
- `MatchTaskService` 为任务、证据和回调分配编号；`AnalysisResult`/`ResumeAudit` JPA
  适配器为结果/审计分配编号。所有写操作必须在现有事务边界内调用生成器。
- `ResumeCache`、JPA 查询和内存测试实现改用 String；活动简历缓存只接受 v2 编号和 ACTIVE
  状态，保持原有 7/30 天归档、软删除、恢复及迟到回调阻断规则。
- 公共 Controller 路径改为 `/api/v2`，请求体中的 `resumeId`、`llmProfileId`、`ownerId`
  和 `taskId` 使用前缀字符串；未知或越权编号仍返回相同 `RESOURCE_NOT_FOUND` 信封。
- 增加 `ReadableIdGenerator`、ID 格式校验和迁移后的启动自检；启动自检发现序列小于已有
  最大后缀时拒绝启动，避免重复 ID。

## 7. Python 服务与内部契约改造

- `AnalysisJob`、`Callback`、证据引用、任务 ID、回调 ID 和相关结果标识改为受限字符串；
  `correlationId` 保持 UUID。
- Java v2 任务信封新增必填 `callbackId`；Python 不自行生成数据库回调 ID，只原样回传。
- Python 内部路由切换到 `/internal/v2/analysis-jobs`，回调默认地址切换到
  `/internal/v2/analysis-results`；原有脱敏、模型端点校验、结构化输出和回调重试策略不变。
- 结果中的岗位要求/建议编号按任务内计数生成，证据 ID 必须属于 Java 发送的允许集合；所有
  v2 JSON Schema 和 fixtures 拒绝 UUID 形式的业务 ID。

## 8. 前端与登录修复

- Axios 路径、生命周期 API、类型定义和测试全部切换到 `/api/v2`，页面显示 `id` 即
  `user001`/`resume001` 等可读编号。
- 注册成功后仍建立会话并跳转；登录页面增加用户名或邮箱的 trim 处理。Pinia store 在
  发请求前对 `identifier` 去首尾空格，含 `@` 的标识符转为小写；密码原样发送。
- Java `AuthService` 对注册用户名/邮箱执行同样的规范化，在 BCrypt `matches` 前不修改
  密码；登录成功后签发包含字符串用户 ID 的 v2 JWT。
- 增加明确的退出登录操作，清理 token 和身份缓存，避免注册后的旧会话遮蔽登录页。
- 401、API 地址不可达和 CORS 错误继续转换为安全中文提示；前端 `.env.example` 与本地
  README 使用 v2 API/回调地址。真实 `.env` 只更新相关非秘密 URL，不输出或重置任何密钥。

## 9. 测试与验收证据

### 契约

- v2 OpenAPI、内部 JSON Schema 和 fixtures 覆盖有效/无效前缀 ID、未知 ID、归档/删除回调、
  重复回调、任务幂等和错误信封；验证器同时确保 v1 历史快照未被修改。

### Java

- 生成器覆盖首个编号、三位补零、超过 999、并发锁和事务回滚。
- 迁移静态检查覆盖每张表的映射、外键重建、序列回填和中文字段备注。
- 认证覆盖注册后用户名登录、邮箱登录、大小写邮箱、带空格标识符、错误密码和旧 UUID
  JWT 拒绝。
- 生命周期、所有权、回调幂等、Redis v2 键和删除/归档竞态测试继续全部通过。

### Python 与前端

- Python 覆盖 v2 字符串 ID 校验、回调 ID 原样传递、脱敏和模型失败。
- Vue 覆盖 v2 URL、编号显示、登录请求规范化、退出登录和 401 会话清理。
- 运行 Java 编译/测试、Python pytest、契约校验、Vitest 和前端生产构建；具备本机服务时
  追加真实注册 -> 退出 -> 用户名/邮箱登录 -> `/auth/me` 流程。

## 10. 发布和失败行为

1. 停止写入服务、备份数据库、确认 Redis 可访问。
2. 执行 Flyway V8，检查每张表的主键类型、外键一致性、序列最大值和行数不变。
3. 启动 Java v2，完成 Redis 旧键清理和启动自检；再启动 Python v2 与前端。
4. 发现迁移失败、重复编号、外键不一致或登录回归失败时，停止发布并从数据库备份恢复，
   不在生产库手工修改半套列。

验收必须分别报告：已实现的字符串主键、已验证的登录路径、已迁移的数据范围、模拟/未配置
的外部模型行为，以及仍需用户重新登录和手工备份的残余风险。
