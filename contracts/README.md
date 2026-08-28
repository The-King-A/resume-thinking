# 简历匹配契约 v1

本目录是首个 Java 后端岗位匹配切片的接口依据。产品需求仍记录在
`ai-resume-job-matching-project.md.docx`；批准的本地设计位于
`docs/superpowers/specs/2026-08-27-resume-matching-platform-design.md`。
这里定义的字段、路径、枚举或传输行为，Java、Python 和 Vue 都必须直接采用，
不得再各自解释产品文档。

## 权威制品

| 制品 | 作用 |
| --- | --- |
| `openapi/v1/openapi.yaml` | 公共 Java API，包括面向用户的请求、响应、错误和授权约定。 |
| `internal/v1/analysis-job.schema.json` | Java 到 Python 的短时分析派发。 |
| `internal/v1/analysis-callback.schema.json` | Python 到 Java 的回调，由 Java 校验并持久化。 |
| `fixtures/v1/` | 任务 2 添加的共享有效、无效、重复、过期、删除和归档示例。 |

公共 API 与内部模式独立版本化，但本切片均保持在 `v1`。新增可选字段属于兼容变更。
删除字段，或改变字段类型、含义、必填性、枚举值语义或授权行为，都必须创建新的版本化
制品并补充代表性的兼容示例。`v1` 之前没有旧版使用方，因此暂时不需要旧版示例。

## 所有权与信任边界

Spring Boot 是唯一的公共业务接口、授权决策方、任务状态所有者以及 MySQL/Redis 写入方。
除注册和登录外，所有公共路由都要求 Bearer JWT 令牌。对于受保护的资源路由，外部 ID
与未知 ID 都必须返回相同的 `RESOURCE_NOT_FOUND` 错误信封；应用不得泄露资源是否属于
其他操作者。

FastAPI 没有登录、用户授权、MySQL/Redis 凭据、直接数据库写入或公共结果写入路径。
Java 向它发送带临时回调令牌的窄范围内部任务。Python 在调用用户选择的外部模型服务前
会脱敏敏感值，校验模型服务输出，并向 Java 发送符合模式的回调。它绝不能记录原始简历、
模型服务 API 密钥、回调令牌、HTTP 授权标头或原始模型服务响应。

### 模型服务接口地址安全

在连接测试或分析派发之前，Java 只接受 HTTPS 接口地址。唯一的本地开发例外是受控测试
明确配置的 `http://127.0.0.1` 地址。Java 会在请求前立即解析每个主机，并拒绝私有、
回环、链路本地、多播及保留地址；它拒绝重定向到新主机，并对每次重定向或
新解析地址重新执行检查。Python 会在请求模型服务前重复校验，形成纵深防御。被拒绝的
地址不会发起模型服务请求，并返回带清理错误信息的 `MODEL_ENDPOINT_REJECTED`。这是
运行时网络策略；JSON 模式无法安全推断 DNS 解析结果或地址类别。

任务和回调模式会拒绝意外的顶层字段，并明确禁止 `ownerId`、`userId`、
`databaseCredentials`、`persistenceCommand` 等身份与存储权威字段，以及主机文件系统
路径。内部文档是受控负载，绝不是服务之间共享的未文档化主机路径。

`analysis-job` JSON 结构特意不包含服务凭据字段。传输认证通过
`X-Internal-Service-Token` HTTP 标头在带外提供，并由不提交的
`PYTHON_INTERNAL_SERVICE_TOKEN` 环境变量配置。缺少该变量时，Python
工作进程会拒绝继续运行。发送回调前，Python 只接受回环目标或精确配置的 Java
回调基址（`JAVA_CALLBACK_BASE_URL`，可选再加逗号分隔的
`PYTHON_CALLBACK_ALLOWED_BASE_URLS`）。

## 公共授权规则

- 经批准的首个切片有意允许注册时选择 `USER` 或 `ADMIN`。这是已知的部署安全风险，
  并不是省略服务器端角色检查的理由。
- `USER` 只能读取、删除和恢复自己有权限的简历。
- `ADMIN` 可以跨所有者查看和恢复符合条件的记录，也可以发起管理员软删除。
- 管理员软删除的记录不会出现在所有者的活动列表或恢复列表中，只有管理员可以恢复它。
- API 密钥仅在写入 LLM 配置的请求中接收，由 Java 加密存储，并从所有响应中省略。
  读取响应只暴露 `hasApiKey` 和安全的连接元数据。
- 删除请求必须提供精确的 `confirmationText` 值 `确认删除简历` 以及当前的
  `expectedVersion`。两项检查由服务器执行，而不是依赖浏览器。

## 简历可见性生命周期

`resumes.status` 仅表示软删除标记：

| `status` | 含义 |
| --- | --- |
| `0` | 简历未被软删除。 |
| `1` | 软删除已成功完成。 |

`visibilityState` 记录当前是否可展示：

| 起始状态 | 事件 | 目标状态 | 操作者/规则 |
| --- | --- | --- | --- |
| `ACTIVE` | 所有者软删除 | `USER_SOFT_DELETED` | 所有者 `USER` 或符合条件的所有者操作。 |
| `ACTIVE` | 管理员软删除 | `ADMIN_SOFT_DELETED` | `ADMIN`；所有者无法恢复。 |
| `ACTIVE` | 所有者创建的缓存到期 | `USER_CACHE_ARCHIVED` | Java 调度器在七天后执行。 |
| `ACTIVE` | 管理员创建的缓存到期 | `ADMIN_CACHE_ARCHIVED` | Java 调度器在三十天后执行。 |
| `USER_SOFT_DELETED` | 所有者恢复 | `ACTIVE` | 同一所有者，使用当前 `version`。 |
| `USER_CACHE_ARCHIVED` | 所有者恢复 | `ACTIVE` | 同一所有者，使用当前 `version`。 |
| 任意符合条件的软删除/归档状态 | 管理员恢复 | `ACTIVE` | `ADMIN`，使用当前 `version`。 |

调度器使用持久化的 MySQL `visible_until`，迁移符合条件的活动记录，删除相关 Redis
键，并记录审计事件。它不会物理删除 MySQL 记录。普通活动列表/读取路由不会从
MySQL 重新加载已归档数据。恢复是显式的、带索引的、按所有者或管理员范围查询。物理
数据库删除没有公共 API，只能由数据库管理员通过 MySQL 直接操作流程执行。

`USER_CACHE_ARCHIVED` 和 `ADMIN_CACHE_ARCHIVED` 是缓存到期归档状态，不是软删除状态。
处于任一状态的记录都没有 Redis 视图，保持 `status = 0`，也不能通过任何页面/API 删除
路由再次软删除。必须先按上述授权规则由其所有者（`USER`）或管理员（`ADMIN`）显式恢复；
只有恢复将其返回 `ACTIVE` 后，才能再次请求删除。

每次成功恢复都会根据恢复时间和原始创建者角色重新计算 `visible_until`：`USER` 创建者
为七天，`ADMIN` 创建者为三十天。因此恢复后的记录不会因保留旧期限而立即再次归档。

## 任务生命周期与回调规则

持久化任务状态机为：

```text
QUEUED -> PROCESSING -> SUCCEEDED | FAILED | TIMED_OUT
活动任务 -> BLOCKED（关联简历被软删除或缓存归档）
```

只有 Java 可以执行这些状态转换。只有在以下条件全部与当前持久化任务匹配时，回调才会
被接受：

1. `taskId`、`attempt`（尝试次数）和回调令牌哈希；
2. 当前简历版本和 `ACTIVE` 可见性状态；
3. 全新的 `callbackId`，或相同回调 ID 与相同 `payloadHash`；
4. 返回的每个 `evidenceId` 都属于原始分析任务提供的 `allowedEvidence`，
   且对当前简历版本具有有效偏移量。

`payloadHash` 是以下内容生成的 UTF-8 字节的小写 SHA-256 摘要：
[RFC 8785 JSON 规范化方案](https://www.rfc-editor.org/rfc/rfc8785)
完整回调对象在省略 `payloadHash` 成员后，按 RFC 8785 生成规范
JSON。该哈希除 RFC 8785 规定外不再规范化文本，不添加空白，并使用 RFC 8785 的数字
序列化方式。Java 和 Python 在创建或比较回调收据前都必须使用这套完全相同的
算法。

使用相同回调 ID 和负载哈希的重复传输会返回已接受的幂等重放。复用回调
ID 但负载改变时返回 `IDEMPOTENCY_CONFLICT`。针对已删除或已归档简历的回调
返回 `TASK_GONE`；针对较早 `attempt`（尝试次数）的回调返回 `STALE_ATTEMPT`。这些结果会让
Python 停止重试，并防止迟到的结果恢复隐藏简历或重新创建结果。

Python 只对传输失败和 Java 5xx 响应重试，并保留原始 `callbackId` 和
`payloadHash`。收到成功响应、`TASK_GONE`、`STALE_ATTEMPT` 或 `IDEMPOTENCY_CONFLICT`
时停止。固定的连接/读取超时和格式错误的结构化模型输出会成为可观测的任务失败，而不是
伪造匹配结果。

## 匹配语义

固定评分公式为：

```text
0.40 skills + 0.25 project experience + 0.15 work content
+ 0.10 education/experience + 0.10 soft skills
```

每个岗位要求结果都包含岗位要求文本和类型、引用的简历证据/位置、匹配状态/类型、
分项得分、证据强度、差距和建议状态。`RELATED_BUT_EVIDENCE_INSUFFICIENT` 与
`UNMET` 不属于正向匹配。建议分为 `SUPPORTED_FACT`、`WORDING_ONLY_REWRITE`、
`NEEDS_USER_CONFIRMATION` 或 `RISKY_OR_UNSUPPORTED`；未经确认或不受支持的事实不会
写入简历内容。

## 错误信封与稳定代码

每个错误响应都遵循 `ApiError`：

```json
{
  "code": "RESUME_ARCHIVED",
  "message": "简历已归档，请先恢复。",
  "correlationId": "00000000-0000-4000-8000-000000000001",
  "retryable": false,
  "details": [{"field": "expectedVersion", "reason": "必须是当前版本"}]
}
```

`details` 可选，只能包含安全的校验信息，绝不能包含简历文本、模型服务响应、秘密或其他
所有者的身份信息。

| 代码 | 含义 | 可重试 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 请求结构或安全字段校验失败。 | 否 |
| `AUTHENTICATION_REQUIRED` | JWT 缺失、过期或无效。 | 否 |
| `FORBIDDEN` | 已认证操作者不具备管理员能力。 | 否 |
| `DUPLICATE_RESOURCE` | 用户名、邮箱或其他唯一资源已存在。 | 否 |
| `INVALID_CONFIRMATION` | 删除确认短语不完全匹配。 | 否 |
| `VERSION_CONFLICT` | 乐观锁版本已过期。 | 否；请先刷新 |
| `RESOURCE_NOT_FOUND` | 资源未知或对当前操作者不可见。 | 否 |
| `RESUME_ARCHIVED` | 执行仅限活动状态的操作前必须先恢复简历。 | 否 |
| `RESUME_SOFT_DELETED` | 简历已软删除，不能接受请求的操作。 | 否 |
| `TASK_GONE` | 任务已删除、归档或以其他方式被阻止。 | 否 |
| `STALE_ATTEMPT` | 回调与任务的当前 `attempt`（尝试次数）不匹配。 | 否 |
| `IDEMPOTENCY_CONFLICT` | 相同幂等/回调键携带了不同数据。 | 否 |
| `MODEL_UNAVAILABLE` | 模型服务/网络超时或不可用。 | 是 |
| `MODEL_OUTPUT_INVALID` | 模型服务返回了无效或无法解析的结构化输出。 | 否 |
| `MODEL_ENDPOINT_REJECTED` | 配置的接口地址违反接口安全策略。 | 否 |
| `UNSUPPORTED_FILE` | 文件不是 TXT 或 DOCX；PDF 在 v1 中明确不支持。 | 否 |
| `PAYLOAD_TOO_LARGE` | 上传内容超过配置策略。 | 否 |
| `TASK_NOT_READY` | 任务尚未产生结果。 | 延迟轮询后可以 |

## 校验与测试样例

运行当前契约检查：

```powershell
pnpm --dir contracts run lint
```

任务 2 会添加校验器和共享示例，覆盖有效的注册/配置/任务请求、
有效的内部分析任务、无效的匹配输入、有效/重复/过期/删除后的回调、
两种归档策略以及错误信封。Java 和 Python 测试使用同一组示例，不再手工维护
相互竞争的示例。
