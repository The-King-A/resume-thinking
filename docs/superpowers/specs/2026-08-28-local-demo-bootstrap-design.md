# 本地演示数据与运行环境设计

**状态：** 用户已授权在本机 `resume_thinking` 数据库中执行

**产品来源：** `E:\resume_thinking\ai-resume-job-matching-project.md.docx`

**契约来源：** `contracts/openapi/v1/openapi.yaml` 与 `contracts/fixtures/v1/`

## 目标

为当前已经手工创建结构的本地 MySQL 环境提供一条可重复、可审计的启动路径，并生成不含真实个人信息的 Java 后端岗位演示数据。用户应能用两个本地演示账号查看正常简历、软删除简历和缓存归档简历，并验证普通用户与管理员的恢复权限。

## 范围与非目标

- Java 是用户、简历、审计和 Redis 缓存的唯一写入口；Python 不新增业务写入路径。
- 不修改 OpenAPI、内部 JSON Schema、数据库迁移或用户可见 API。
- 不创建 `llm_profiles`、模型密钥、分析任务、分析结果、证据或回调收据。
- 不使用真实姓名、邮箱、电话、地址、学校、项目或模型凭据。
- 不提供通过 HTTP 触发初始化、清空库、重置密码或覆盖既有数据的接口。

## 本地启动边界

当前数据库是 V1 至 V6 的手工结构快照，已确认包含八张业务表和 V2.1、V3.1、V4、V5、V6 的最终列。它没有 `flyway_schema_history`，因此首次 Java 本地启动必须仅一次地使用：

```text
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
SPRING_FLYWAY_BASELINE_VERSION=6
```

Flyway 必须自行创建基线记录；不手写历史表，也不重新执行 V1 至 V6。后续版本大于 V6 的迁移仍由 Flyway 执行。

本地 `.env` 仅保存数据库连接、Redis 地址、JWT、AES-GCM 密钥、内部服务令牌和演示账号密码。它必须保持 Git 忽略，日志、测试输出和 API 响应均不得显示这些值。

## 演示数据设计

初始化器仅在 `local` profile 与 `APP_DEMO_SEED_ENABLED=true` 同时成立时装配。`DEMO_USER_PASSWORD` 与 `DEMO_ADMIN_PASSWORD` 必须非空且至少 12 个字符；缺失或不符合要求时启动失败，不产生半套数据。

创建账号 `demo_user`（`USER`）与 `demo_admin`（`ADMIN`）。密码由现有 `PasswordEncoder` 生成 BCrypt 哈希，永不明文入库。每份匿名 TXT 简历由 `AesGcmCryptoService` 使用当前本地 AES-GCM 密钥加密；禁止裸 SQL 伪造密文、nonce、密码哈希或审计记录。

| 所有者 | 简历状态 | 预期用途 |
| --- | --- | --- |
| `demo_user` | `ACTIVE` | 普通用户当前可见简历，Redis 中存在派生视图。 |
| `demo_user` | `USER_SOFT_DELETED` | 普通用户可恢复。 |
| `demo_user` | `USER_CACHE_ARCHIVED` | 7 天后归档，普通用户可恢复。 |
| `demo_user` | `ADMIN_SOFT_DELETED` | 所有者不可见、不可恢复；管理员可恢复。 |
| `demo_admin` | `ACTIVE` | 管理员当前可见简历，Redis 中存在派生视图。 |
| `demo_admin` | `ADMIN_CACHE_ARCHIVED` | 30 天后归档，管理员可恢复。 |

软删除记录的 `status=1`；活动和归档记录的 `status=0`。每个删除或归档状态都写入一条 `resume_recovery_audit` 记录。仅两条 `ACTIVE` 简历在成功提交后写入 Redis；其他记录明确清理缓存键。

初始化使用固定简历 UUID 作为幂等锚点。重复启动时，只接受匹配的演示账号、角色、简历所有者、标题和生命周期状态并跳过；遇到同名账号、相同 UUID 或邮箱但身份不匹配的记录时立即失败，不覆盖、删除、重置或重新加密任何既有数据。

## 验证与失败行为

- 默认关闭时不触碰用户、简历、审计或缓存仓储。
- 启用后测试六条简历的状态、保留期、审计、缓存写入边界、BCrypt 密码和 AES-GCM 明文可恢复性。
- 重复执行不得新增记录、审计行或更改现有密文。
- 真实本机验证依次检查 Flyway 基线、Redis PING、Java/Python 健康检查、两个账号登录、普通用户和管理员恢复列表的可见性，以及活动/隐藏简历对应的 Redis 键。
- 外部模型尚未配置；匹配任务与模型连接测试不属于本次验收。
