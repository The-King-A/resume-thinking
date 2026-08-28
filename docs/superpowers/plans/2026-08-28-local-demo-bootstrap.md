# 本地演示数据与运行环境实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变公开接口或数据库结构的前提下，为本地 MySQL 和 Redis 环境安全创建可重复的匿名演示数据。

**Architecture:** Java 本地 profile 中的条件启动任务调用事务性演示数据服务。该服务使用现有 BCrypt、AES-GCM、JPA 仓储、审计仓储和 Redis 缓存接口，拒绝任何冲突而不覆盖现有数据。Flyway 通过版本 6 的一次性基线接管手工建表数据库。

**Tech Stack:** JDK 21、Spring Boot 3.4、Spring Data JPA、Flyway、Redis、MySQL 8.4、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-08-28-local-demo-bootstrap-design.md`

## Global Constraints

- 仅 Java 可以写入 `users`、`resumes`、`resume_recovery_audit` 和 Redis 简历视图。
- 仅 `local` profile 且 `APP_DEMO_SEED_ENABLED=true` 可启用演示初始化；默认关闭。
- 禁止公开 HTTP 初始化接口、数据库硬删除、SQL 伪造密码哈希/AES-GCM 密文/nonce、真实 PII、模型密钥和分析任务。
- 默认和重复运行均不得覆盖用户、重置密码、删除数据、重新加密内容或添加重复审计记录。
- 活动记录只能在事务提交后写入 Redis；软删除和归档记录不得保留 Redis 视图。
- 手工 V1 至 V6 结构只通过 Flyway baseline `6` 接管；禁止手写 `flyway_schema_history`。

---

### Task 1: 事务性演示数据服务与测试

**Files:**
- Create: `back/java/src/main/java/com/resumethinking/platform/dev/DemoDataSeedService.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/dev/DemoDataSeedServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository`、`PasswordEncoder`、`AesGcmCryptoService`、`ResumeRepository`、`ResumeAuditRepository`、`ResumeCache`、`Clock`。
- Produces: `DemoDataSeedService.seed(DemoCredentials)`，其中 `DemoCredentials` 仅包含两个本地演示密码。

- [ ] **Step 1: 编写失败测试，覆盖默认数据矩阵与加密边界。**

```java
@Test
void seedsTwoHashedUsersSixAnonymousResumesAndOnlyTwoActiveCacheEntries() {
    var result = service.seed(new DemoCredentials("user-password-123", "admin-password-123"));
    assertThat(users.findByUsername("demo_user").orElseThrow().getPasswordHash())
        .doesNotContain("user-password-123");
    assertThat(result.createdResumeCount()).isEqualTo(6);
    assertThat(resumes.findByVisibilityStateIn(List.of(VisibilityState.ACTIVE), PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
    assertThat(audit.all()).hasSize(4);
}
```

- [ ] **Step 2: 运行失败测试。**

Run: `back\java\mvnw.cmd -Dtest=DemoDataSeedServiceTest test`

Expected: FAIL，因为 `DemoDataSeedService` 和 `DemoCredentials` 尚不存在。

- [ ] **Step 3: 实现最小事务性服务。**

```java
@Transactional
public SeedResult seed(DemoCredentials credentials) {
    User user = ensureUser(USER, credentials.userPassword());
    User admin = ensureUser(ADMIN, credentials.adminPassword());
    seedResume(USER_ACTIVE_ID, user, ACTIVE, now);
    seedResume(USER_DELETED_ID, user, USER_SOFT_DELETED, now);
    seedResume(USER_ARCHIVED_ID, user, USER_CACHE_ARCHIVED, now.minus(Duration.ofDays(8)));
    seedResume(USER_ADMIN_DELETED_ID, user, ADMIN_SOFT_DELETED, now);
    seedResume(ADMIN_ACTIVE_ID, admin, ACTIVE, now);
    seedResume(ADMIN_ARCHIVED_ID, admin, ADMIN_CACHE_ARCHIVED, now.minus(Duration.ofDays(31)));
    return result;
}
```

`ensureUser` 必须验证 username、email、role 和 BCrypt 密码；`seedResume` 必须以 AES-GCM 加密 ASCII 合成 TXT，写入状态审计并在提交后才更新 Redis。状态或身份冲突必须抛出安全错误。

- [ ] **Step 4: 补充失败测试，覆盖重复执行与冲突拒绝。**

```java
@Test
void rerunIsIdempotentAndConflictingDemoIdentityFailsWithoutMutation() {
    service.seed(credentials);
    var rerun = service.seed(credentials);
    assertThat(rerun.createdResumeCount()).isZero();
    assertThatThrownBy(() -> service.seed(new DemoCredentials("different-password-123", "admin-password-123")))
        .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 5: 运行测试并提交。**

Run: `back\java\mvnw.cmd -Dtest=DemoDataSeedServiceTest test`

Expected: PASS。

```bash
git add back/java/src/main/java/com/resumethinking/platform/dev back/java/src/test/java/com/resumethinking/platform/dev
git commit -m "feat: add guarded local demo data seed"
```

### Task 2: 条件启动任务与本地配置说明

**Files:**
- Create: `back/java/src/main/java/com/resumethinking/platform/dev/DemoDataInitializer.java`
- Create: `back/java/src/test/java/com/resumethinking/platform/dev/DemoDataInitializerTest.java`
- Modify: `back/java/src/main/resources/application-local.yml`
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `database/resume_thinking_schema.sql`

**Interfaces:**
- Consumes: `DemoDataSeedService.seed(DemoCredentials)` 与本地环境变量。
- Produces: 只在 local + `APP_DEMO_SEED_ENABLED=true` 时调用服务的 `ApplicationRunner`。

- [ ] **Step 1: 编写失败测试，覆盖初始化器的密码前置条件。**

```java
@Test
void rejectsMissingOrTooShortDemoPasswordsBeforeSeeding() {
    assertThatThrownBy(() -> initializer.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("demo seed password");
    verifyNoInteractions(seedService);
}
```

- [ ] **Step 2: 运行失败测试。**

Run: `back\java\mvnw.cmd -Dtest=DemoDataInitializerTest test`

Expected: FAIL，因为条件初始化器尚不存在。

- [ ] **Step 3: 实现条件初始化器和属性映射。**

```java
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "app.demo-seed", name = "enabled", havingValue = "true")
final class DemoDataInitializer implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        seedService.seed(new DemoCredentials(userPassword, adminPassword));
    }
}
```

`application-local.yml` 将 `APP_DEMO_SEED_ENABLED`、`DEMO_USER_PASSWORD` 与 `DEMO_ADMIN_PASSWORD` 映射为 `app.demo-seed` 属性，默认关闭。`.env.example` 仅提供空占位，不包含实际密码。README 与 SQL 脚本说明二选一的 Flyway 路径和基线命令。

- [ ] **Step 4: 运行测试与编译。**

Run: `back\java\mvnw.cmd -Dtest=DemoDataSeedServiceTest,DemoDataInitializerTest test`

Run: `back\java\mvnw.cmd -q -DskipTests compile`

Expected: 两条命令均 PASS。

- [ ] **Step 5: 提交。**

```bash
git add back/java/src/main/java/com/resumethinking/platform/dev back/java/src/test/java/com/resumethinking/platform/dev back/java/src/main/resources/application-local.yml .env.example README.md database/resume_thinking_schema.sql
git commit -m "docs: document local demo bootstrap"
```

### Task 3: 受控本机基线化与运行验证

**Files:**
- Modify: `docs/verification/mvp-evidence.md`

**Interfaces:**
- Consumes: 本地 Git 忽略 `.env`、当前手工 MySQL 结构、Redis 6379、Java 8080、Python 8000。
- Produces: 不含密钥或简历正文的本机启动和数据验证证据。

- [ ] **Step 1: 导入 `.env` 并以 baseline `6` 启动 Java。**

```powershell
Get-Content .env | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  Set-Item "Env:$name" $value
}
Push-Location back\java
try { .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local' }
finally { Pop-Location }
```

Expected: Flyway 写入 V6 baseline，随后 Java 健康检查返回 200；不得重跑 V1 至 V6。

- [ ] **Step 2: 启动 Python 并检查服务健康。**

```powershell
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pip install -e "back/python[test]"
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m uvicorn app.main:app --app-dir back/python --port 8000
```

Expected: `http://127.0.0.1:8000/health` 返回 200，且不调用外部模型。

- [ ] **Step 3: 验证数据库、缓存和身份边界。**

```text
检查 Flyway V6 baseline、2 个用户、6 条简历、4 条审计记录和 2 个 Redis resume:view 键；
登录 demo_user/demo_admin，验证普通用户只恢复 USER_* 条目，管理员可看到全部可恢复条目。
```

- [ ] **Step 4: 记录证据并提交。**

```bash
git add docs/verification/mvp-evidence.md
git commit -m "test: verify local demo bootstrap"
```
