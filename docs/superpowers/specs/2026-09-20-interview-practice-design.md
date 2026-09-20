# 面试推演第一期设计

**状态：** 用户已审阅并批准，实施规划已完成，待按计划进入编码

**用户可见结果：** 用户可以从一份已经完成且仍然有效的 Java 后端岗位匹配报告进入面试准备，查看有来源依据的分层问题，提交一轮回答，并查看包含证据支撑、完整性、技术准确性、事实一致性、表达清晰度和风险提示的反馈。

**产品来源：** `C:/Users/theking.guo/Documents/Codex/2026-08-24/plugin-browser-openai-bundled-x20/outputs/ai-resume-job-matching-project.md`

**范围来源：** `E:/final_lecture/6023032108-郭航-求职画像驱动的岗位适配与面试推演平台的设计与实现-报告.docx` 第 8 章，`E:/final_lecture/6023032108-郭航-求职画像驱动的岗位适配与面试推演平台-任务书.docx` 的研究内容与功能要求，以及中期自查中“面试推演模块尚未进入完整实现阶段”的后续任务说明。

## 1. 切片范围

### 1.1 本次实现

- 从已持久化、已审阅且有有效证据的岗位匹配结果进入面试准备。
- 仅支持现有 MVP 的 `JAVA_BACKEND` 岗位族。
- 生成四类带来源的问题：基础确认、项目深挖、岗位场景、综合追问。
- 每道问题记录关联岗位要求、允许引用的证据 ID、难度、题型和生成原因。
- 创建一个由 Java 管理的面试会话，绑定用户、岗位匹配任务和有效简历修订版本。
- 支持单轮回答提交、一次结构化回答分析和反馈查看。
- 对回答中的超出简历事实、无法核验的数字、职责或成果标记为 `NEEDS_USER_CONFIRMATION`，不写回简历、不更新长期求职画像。
- 支持用户结束并清理面试会话内容；清理后拒绝迟到的 Python 回调和重复提交。
- 前端提供准备、会话、反馈、加载、失败、低置信度、无数据和确认提示状态。

### 1.2 明确不实现

- 连续多轮追问和自适应下一题策略；本切片只实现题目集生成和一轮“题目—回答—反馈”。
- 面试表现写入长期求职画像、简历事实或简历导出。
- PDF 解析、其他岗位族、正式公平性实验、向量持久化、Nginx、完整 Docker Compose 和生产部署加固。
- 修改既有 v2/v3 公共匹配接口、既有数据库记录含义、既有登录/模型配置/简历生命周期行为。
- 将模型生成内容当作用户事实，或用模型分数替代 Java 的状态和权限判断。

## 2. 兼容性与契约冻结

既有 `contracts/openapi/v2/openapi.yaml`、`contracts/openapi/v3/openapi.yaml`、v2/v3 内部 schema、fixtures 和运行时路由保持不变。面试是没有现有消费者的新业务边界，采用独立的 v4 契约，避免改变已发布字段的含义：

- 公共契约：`contracts/openapi/v4/openapi.yaml`，公共路径统一使用 `/api/v4`。
- Java 到 Python：`contracts/internal/v4/interview-job.schema.json`。
- Python 到 Java：`contracts/internal/v4/interview-callback.schema.json`。
- 共享样例：`contracts/fixtures/v4/interview/`。
- 契约说明：在 `contracts/README.md` 增加 v4 面试契约、版本兼容和状态机说明。

v4 不复用 v2/v3 的字段语义。它只允许新增面试资源，不提供 Python 公共入口。服务间认证仍通过 `X-Internal-Service-Token` 请求头；每次面试任务的 Java 签发 `callbackToken` 仅存在于 Java 到 Python 的受控内部 JSON，供 Python 向 Java 回调时使用，且不进入数据库日志、fixtures、公共 API 或前端响应。所有 v4 错误仍使用现有安全错误信封语义，并使用面试专用稳定错误码：

| 错误码 | 语义 | 是否可重试 |
| --- | --- | --- |
| `INTERVIEW_MATCH_NOT_READY` | 匹配结果尚未成功或没有可用证据 | 否 |
| `INTERVIEW_SESSION_NOT_FOUND` | 会话未知或对当前操作者不可见 | 否 |
| `INTERVIEW_SESSION_GONE` | 会话已结束、被清理或关联简历已失效 | 否 |
| `INTERVIEW_QUESTION_NOT_READY` | 题目生成尚未完成 | 是，延迟轮询 |
| `INTERVIEW_ANSWER_CONFLICT` | 重复幂等键提交了不同回答 | 否 |
| `INTERVIEW_CALLBACK_STALE` | 回调版本、尝试次数或会话状态过期 | 否 |
| `INTERVIEW_MODEL_UNAVAILABLE` | 模型超时或暂时不可用 | 是 |
| `INTERVIEW_MODEL_OUTPUT_INVALID` | 模型输出不符合结构化契约或证据规则 | 否 |
| `INTERVIEW_CONFIRMATION_REQUIRED` | 操作涉及未确认的新增事实 | 否 |

## 3. 业务状态和数据所有权

### 3.1 会话状态

Java 是唯一允许改变会话状态的一方。状态转换如下：

```text
QUESTION_GENERATING -> WAITING_FOR_ANSWER -> ANSWER_ANALYZING -> FEEDBACK_READY
QUESTION_GENERATING -> FAILED
ANSWER_ANALYZING    -> FAILED
FEEDBACK_READY      -> COMPLETED
任意非终态           -> DELETED
```

`QUESTION_GENERATING` 和 `ANSWER_ANALYZING` 的模型任务还分别映射到已有异步任务语义 `QUEUED -> PROCESSING -> SUCCEEDED | FAILED | TIMED_OUT`。Java 使用会话版本、题目 ID、回答 ID、尝试次数和回调 ID 做条件更新；已删除、已完成或版本不匹配的会话不能被回调重新激活。

### 3.2 业务记录

新增以下 MySQL 权威记录，具体列名在契约冻结后由实现计划逐项落地：

- `interview_sessions`：所有者、关联 `resume_id`、`revision_id`、匹配任务/结果、岗位族、状态、当前版本、创建/结束/清理时间和保留策略。
- `interview_questions`：会话、题目序号、题型、难度、题目文本、关联岗位要求 ID、关联证据 ID、生成原因、来源置信度和状态。
- `interview_answers`：会话、题目、幂等键、加密回答内容、回答版本、提交时间和清理标记。
- `interview_feedback`：回答、相关性、完整性、技术准确性、事实一致性、表达清晰度、风险标记、可引用证据 ID、待确认声明和改进建议。
- `interview_confirmations`：用户对具体待确认声明的确认/拒绝、操作者、时间和原反馈版本；此记录只表达确认，不改变简历事实。

Java 负责所有写入和授权；Python 不直接访问 MySQL、Redis 或用户会话。Redis 如被使用，只缓存短期页面状态，键必须绑定会话 ID 和版本；MySQL 仍是事实来源。

## 4. 公共 API 设计

所有接口都要求 Bearer JWT，且按当前用户所有权检查 `sessionId`、关联简历和匹配结果；未知资源与他人资源使用同一安全错误响应。

### 4.1 创建会话和生成题目

`POST /api/v4/interview-sessions`

请求只包含 `matchTaskId` 和幂等键。v2/v3 不对外暴露独立的 `matchResultId`，而匹配结果页也不应向浏览器传递可伪造的修订版本或模型配置，因此 Java 以 `matchTaskId` 在权威存储中解析简历、修订版本、岗位族、模型配置和匹配结果，并验证任务成功、结果存在有效证据、修订版本仍为当前有效版本、简历未删除/归档，以及任务属于当前用户。

响应返回会话元数据和 `QUESTION_GENERATING` 状态，不返回原始简历内容、模型密钥或内部回调凭据。

`GET /api/v4/interview-sessions/{sessionId}`

返回会话状态、版本、题目数量和当前回答/反馈阶段，用于前端轮询；它不返回回答正文、回调凭据或未授权的关联资源。

`GET /api/v4/interview-sessions/{sessionId}/questions`

仅在题目生成完成后返回题目元数据和题目文本；每题必须包含题型、难度、关联岗位要求、允许引用的证据 ID 和生成原因。生成中返回 `INTERVIEW_QUESTION_NOT_READY`，失败返回可识别且不泄露模型原文的错误。

`POST /api/v4/interview-sessions/{sessionId}/questions/regenerate`

仅允许会话处于 `QUESTION_GENERATING` 失败后的可恢复状态或尚未提交回答时调用；请求带当前会话版本和新的幂等键。Java 清理旧题目、递增会话版本并重新派发题目任务，不能在已有回答后覆盖历史题目。

### 4.2 提交回答和读取反馈

`POST /api/v4/interview-sessions/{sessionId}/answers`

请求包含 `questionId`、回答文本、客户端幂等键和当前会话版本。Java 校验题目属于会话、会话处于 `WAITING_FOR_ANSWER`、回答长度在策略范围内，并以同一幂等键拒绝不同内容。回答进入加密存储后，Java 将最小化、脱敏后的任务发送给 Python。

`GET /api/v4/interview-sessions/{sessionId}/feedback`

反馈生成中返回状态；完成后返回五类反馈维度、风险标记、证据引用、待确认声明和下一轮改进建议。接口不得把未确认声明拼接成简历内容或画像事实。

`POST /api/v4/interview-sessions/{sessionId}/confirmations`

只允许当前用户对反馈中指定的声明逐条确认或拒绝。确认结果保留审计记录，不会自动修改 `resumes`、`resume_evidence` 或匹配结果；后续是否沉淀画像属于独立切片。

`DELETE /api/v4/interview-sessions/{sessionId}`

执行幂等的会话清理：标记会话为 `DELETED`，阻止未完成任务和迟到回调，清除 Redis 派生状态，并将回答、反馈和待确认声明置为不可读/清理状态。MySQL 的必要审计元数据按既有保留策略保留；该接口不宣称删除外部模型供应商或数据库备份中的历史副本。

## 5. 内部任务和回调

### 5.1 Java 到 Python

`contracts/internal/v4/interview-job.schema.json` 只允许以下类型的最小化任务：

- `QUESTION_GENERATION`：岗位要求、匹配状态、缺口、已批准证据片段、题型约束、难度约束和会话/修订版本元数据。
- `ANSWER_ANALYSIS`：当前问题、关联岗位要求、已批准证据片段、经过脱敏的回答、回答 ID、会话版本和分析维度。

任务必含 `contractVersion`、`sessionId`、`revisionId`、`attempt`、`callbackId` 和允许证据范围；不得包含用户密码、API 密钥、数据库凭据、主机文件路径、JWT、完整原始简历或未脱敏联系方式。Python 将岗位文本、简历证据和回答视为不可信数据，不执行其中的指令性内容。

### 5.2 Python 回调

`contracts/internal/v4/interview-callback.schema.json` 的回调必须带回 `contractVersion`、`sessionId`、`revisionId`、`attempt`、`callbackId`、结果类型和结构化结果。问题回调只能生成四类问题；反馈回调只能引用 Java 提供的允许证据 ID，且每个事实风险标记必须包含状态和安全说明。`payloadHash` 沿用现有 RFC 8785 规范化和 SHA-256 规则，重试保持相同 `callbackId` 与 `payloadHash`。

Java 接收回调前校验会话所有权、当前版本、任务类型、尝试次数、回调令牌哈希、回调幂等性、修订版本和证据范围。相同 ID 与相同哈希只返回幂等重放；相同 ID 携带不同哈希、会话已删除或版本已过期时拒绝，且不得重新创建题目、反馈或恢复简历。

## 6. 隐私、事实约束和留存

- 原始回答属于用户敏感内容，数据库静态加密；日志、异常、指标、契约 fixtures 和前端错误中不得出现完整回答、姓名、电话、邮箱、密钥或原始模型响应。
- 外部模型只接收脱敏后的回答、必要的岗位要求和已批准证据片段；页面在创建会话前说明发送范围、用途和本地留存策略。
- 模型输出必须经过 Pydantic/JSON Schema 校验、证据 ID 校验、长度校验和事实风险分类；结构错误进入失败状态，不降级成看似成功的反馈。
- `SUPPORTED_FACT` 和 `WORDING_ONLY_REWRITE` 仍需引用已有证据；新增数字、成果、职责或经历必须标记 `NEEDS_USER_CONFIRMATION`，并且在用户确认前不能进入简历、导出或长期画像。
- 清理会话时清除回答/反馈的可读内容、缓存和未完成任务；保留的审计元数据不包含回答正文。外部模型供应商和数据库备份的留存边界在页面和文档中明确披露。

## 7. 前端交互边界

在 `MatchResultView.vue` 中增加“开始面试推演”入口，仅对有效成功结果显示。新增受保护路由和页面：

- `InterviewPrepareView.vue`：显示岗位要求、题型筛选、难度、证据来源、生成中/失败和重新生成状态。
- `InterviewSessionView.vue`：显示当前题目、关联要求、回答输入、会话状态、版本冲突和结束会话操作。
- `InterviewFeedbackView.vue`：显示五类反馈维度、证据、风险、待确认声明和改进建议；不提供自动写入简历按钮。

前端只消费 Java v4 API，不本地计算反馈、不保存原始回答到日志、不读取 Python 地址。必须覆盖待处理、低置信度、模型失败、无数据、会话已清理、确认待办和移动端布局状态；现有匹配报告、简历列表、恢复、删除和模型配置页面的行为不变。

## 8. 验收证据

实施完成前必须先冻结以下 fixtures，再分别实现服务：

- 题目生成成功、四类题型完整、空证据被拒绝、无效题型被拒绝。
- 回答分析成功、事实风险标记、证据 ID 越界、结构化输出错误、模型超时。
- 相同回调 ID/哈希重放、相同回调 ID/不同哈希冲突、过期尝试、会话删除后的迟到回调。
- 跨用户读取/提交/确认/删除拒绝、匹配结果未完成拒绝、简历修订版本不一致拒绝。
- 旧 v2/v3 契约和既有 fixtures 继续通过，证明兼容性。

预定验证命令：

```powershell
pnpm --dir contracts run lint
back\java\mvnw.cmd -q -Dtest=*Interview* test
python -m pytest back/python/tests -q
pnpm --dir front test -- --run
python -m pytest tests/integration/assert_mvp_flow.py -q
```

除上述专项测试外，还需要一次受控 Java-Python 单轮联调，记录会话创建、题目生成、回答提交、反馈读取、会话清理和迟到回调拒绝。测试结果必须区分新切片实测、既有历史证据、模拟模型行为和未验证的真实外部模型行为。

## 9. 结构自审结论

- **来源一致性：** 面试准备、单轮问答、反馈维度、用户确认、删除和迟到回调控制均可在报告第 8 章、任务书和中期自查中找到依据；PDF、其他岗位族、公平性和部署扩展被列为非目标。
- **兼容性：** v2/v3 契约、旧页面和旧数据库含义不变；新面试接口独立使用 v4。
- **安全性：** Java 保持公共业务和授权权威；Python 只处理脱敏最小任务；会话版本、回调幂等和删除标记覆盖迟到结果。
- **事实约束：** 反馈不等于事实，待确认声明不会自动进入简历或长期画像。
- **验收可行性：** 契约、Java、Python、前端和跨服务验证均有明确文件边界与命令；真实外部模型质量、公平性和生产部署不在本切片验收声明内。

本文没有为 PDF、其他岗位族、多轮追问、画像沉淀或公平性实验定义实现接口；这些需求必须在后续独立切片中重新完成设计审批。
