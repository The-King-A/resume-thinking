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
| `E:\maven\...\mvn.cmd clean test`（在 `back/java` 中） | 通过，88 项测试 | Java 单元测试和 HTTP 边界测试；未连接真实 DB/Redis |
| Python 3.11 `pytest back/python/tests tests/integration -q` | 通过，67 项测试 | 脱敏、解析、回调规则、示例和离线流程断言 |
| `pnpm --dir front exec vitest run` | 通过，56 项测试 | jsdom 下的 Vue/API 行为 |
| `pnpm --dir front run build` | 通过 | `vue-tsc` 和 Vite 生产构建 |
| 对 `run_mvp_flow.ps1` 进行 PowerShell AST 解析 | 通过，0 个解析错误 | 仅验证启动器语法 |
| 使用临时模拟令牌执行启动器预检 | 通过 | 输出为通用 `FAIL preflight=CONFIGURATION_OR_SERVICE`；输出中没有模拟令牌 |

离线集成断言覆盖受控 TXT/DOCX/PDF 示例、证据与评分不变量、保留期限计算、回调竞态
示例及已清理的错误行为。它们不会连接 Java、MySQL、Redis 或外部模型服务。

## 受控模拟

`tests/integration/assert_mvp_flow.py --live` 包含仅在内存中运行、只允许回环地址的
OpenAI 兼容模型服务以及临时凭据。这是确定性的交接模拟器，不能证明真实模型服务的质量或
可用性。PowerShell 启动器只转发允许列表中的 ID、状态、HTTP 状态码和稳定的错误
类别。它将 `PYTHON_INTERNAL_SERVICE_TOKEN` 作为进程配置加载，在实时尝试前（连同其他服务
凭据）要求该值存在，并且绝不回显或将其作为命令参数传递。

## 未验证真实集成

本报告没有声称执行过实时跨服务运行。验证时的情况如下：

- 工作树中没有可用的、已授权的本地 `.env`；
- MySQL Windows 服务正在运行，但无法连接 `127.0.0.1:6379` 上的 Redis；
- 因此启动器在预检阶段停止，没有启动 Java 或 Python、针对 MySQL 运行 Flyway，
  也没有执行回调流程；
- 没有联系真实的 OpenAI 兼容模型服务，也没有捕获 Playwright 浏览器截图。

如需获得实时证据，请在未跟踪的 `.env` 中配置已授权的值，启动 Redis，然后运行：

```powershell
powershell -ExecutionPolicy Bypass -File tests/integration/run_mvp_flow.ps1 -RequireLive
```

只有当命令报告 Java/Python 健康状态、MySQL TCP 就绪、Redis PING 以及受控流程均成功后，
结果才能被视为实时证据。

## 残余风险与后续工作

- 根据产品决策，管理员注册仍然开放；在共享或公共部署前必须加以限制。
- 调度器、Flyway 迁移、JPA 映射、Redis 序列化以及删除竞态仍需要真实
  MySQL/Redis 冒烟运行。通过单元测试不能替代该环境证据。
- 缓存过期和软删除会有意保留 MySQL 中的加密简历数据。数据库管理员的删除流程、访问控制、
  备份和审计保留不属于本应用切片。
- 真实模型服务的延迟、格式错误、速率限制、成本、事实质量、公平性和安全审查尚未验证。
  外部模型流量必须使用已授权的模型服务，并遵循文档规定的脱敏边界。
- PDF 解析、其他岗位族、导出、向量检索、面试流程、集群化和
  生产部署加固不在本 MVP 范围内。
