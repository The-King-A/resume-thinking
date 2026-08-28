# 任务 10 集成证据

## 范围

本阶段新增确定性的 TXT、DOCX 以及明确不支持的 PDF 输入、离线断言模块、受控
PowerShell 启动器和可选的 Playwright 生命周期测试。这些检查调用公共 Java API，
并将 Java 视为唯一的持久化和授权权威。

## 默认模式与实时模式

即使没有启动服务，也可以安全运行 `assert_mvp_flow.py`。默认的 pytest 路径只校验
仓库中的测试样例以及证据/评分不变量。实时流程需要显式启用（`--live`），并在内存中
启动只允许回环地址的 OpenAI 兼容模拟模型服务。它为该服务生成临时凭据，从不写入磁盘，
也不会打印凭据。

`run_mvp_flow.ps1` 在不回显值的情况下加载已有的 `.env`，复用健康的服务，只有在配置
齐全时才启动 Maven/Python 进程。缺少凭据、Docker/Redis 或服务不健康时，默认明确输出
`SKIP`。如果缺少前置条件时需要让持续集成失败，请添加 `-RequireLive`（或设置
`MVP_REQUIRE_LIVE=1`）。启动器只转发包含 ID、枚举状态和 HTTP 状态码的允许列表行。

除非提供 `E2E_LIVE=1` 和 `E2E_PROVIDER_URL`，Playwright 测试同样保持禁用。前端启动时
必须将 `VITE_API_BASE_URL` 指向 Java API。非回环地址的模型服务还需要
`E2E_PROVIDER_API_KEY`；回环地址测试使用内存中的临时密钥。

## 覆盖的断言

- TXT 和 DOCX 上传会被接受，并保持为 `ACTIVE`。
- 实时模式下，PDF 上传会以 HTTP 415 和 `UNSUPPORTED_FILE` 被拒绝。
- 完成的任务状态为 `SUCCEEDED`，每条返回的要求都有非空证据摘录，且来源范围非负并按顺序递增。
- 用户软删除会移除活动可见性，恢复列表会列出该记录，显式恢复后重新回到活动可见状态。
- 删除后才放行的受控模型服务回调会被拒绝，并返回 `TASK_GONE`；不会重新创建结果，恢复元数据仍然保留。
- 管理员软删除会对所有者隐藏记录，但管理员恢复范围可以恢复该记录。
- 缓存到期推进会报告为 `SKIP`，因为生产环境没有公开的时钟修改接口；确定性的时钟测试属于 Java 集成配置。

## 验证运行

```powershell
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest tests/integration/assert_mvp_flow.py -q
$tokens=$null; $errors=$null
[System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path tests/integration/run_mvp_flow.ps1),[ref]$tokens,[ref]$errors) | Out-Null
```

测试样例和契约检查可以离线通过。实时运行只能在明确准备好且凭据已获授权的环境中执行；
本阶段不会猜测、嵌入或提交 MySQL、JWT、加密密钥或模型服务密钥。
