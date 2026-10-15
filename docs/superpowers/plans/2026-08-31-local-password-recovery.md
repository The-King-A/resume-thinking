# Local Password Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a loopback-only local password reset route, registration password confirmation, and authentication top notifications.

**Architecture:** Java remains the sole authentication and persistence boundary. The additive v2 reset route writes a BCrypt replacement hash only when enabled in the local profile and called from loopback. Vue owns confirmation-password ergonomics and invokes an Element Plus top notification through the authentication store.

**Tech Stack:** Spring Boot 3 / Java 21 / Spring Security BCrypt, Vue 3 / TypeScript / Pinia / Element Plus, MySQL, OpenAPI 3.1.

**Spec:** `docs/superpowers/specs/2026-08-31-local-password-recovery-design.md`

## Global Constraints

- Public contract authority: `contracts/openapi/v2/openapi.yaml`; valid and invalid fixtures are under `contracts/fixtures/v2`.
- Route: `POST /api/v2/auth/password-reset`; body fields are exactly `identifier` and `newPassword`; success is `204 No Content`.
- `confirmPassword` is a client-only field and must be rejected if supplied to the API.
- Direct reset must be enabled only in the `local` profile and only for loopback callers. It returns no password, hash, token, or account data.
- Preserve BCrypt password storage. Do not create an external-model, email, token, or database-storage dependency for this local slice.
- Normal user/admin role selection remains a single radio group.

---

### Task 1: Local Java Password Reset Boundary

**Files:**
- Modify: `back/java/src/main/java/com/resumethinking/platform/auth/AuthController.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/auth/AuthService.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/auth/User.java`
- Modify: `back/java/src/main/java/com/resumethinking/platform/config/SecurityConfig.java`
- Modify: `back/java/src/main/resources/application-local.yml`
- Modify: `.env.example`
- Test: `back/java/src/test/java/com/resumethinking/platform/auth/AuthLoginRegressionTest.java`
- Test: `back/java/src/test/java/com/resumethinking/platform/config/AuthenticationHttpBoundaryTest.java`

**Interfaces:**
- Consumes: `PasswordResetRequest(identifier, newPassword)` in the frozen v2 contract.
- Produces: `AuthService.resetLocalPassword(ResetPasswordCommand)` and an unauthenticated controller route returning `204`.

- [ ] **Step 1: Write failing Java tests**

```java
assertThatThrownBy(() -> service.login(new LoginCommand("alice", "old-password")))
    .isInstanceOf(AuthenticationException.class);
assertThat(service.login(new LoginCommand("alice", "replacement-password"))).isNotNull();
```

Add MVC tests for `204`, disabled/non-loopback safe failure, unknown identifier `404`, and request bodies containing `confirmPassword` returning `400`.

- [ ] **Step 2: Run the focused tests and observe the missing route/reset failure**

Run: `mvn -q -Dtest=AuthLoginRegressionTest,AuthenticationHttpBoundaryTest test`

- [ ] **Step 3: Implement the minimum boundary**

```java
@PostMapping("/password-reset")
@ResponseStatus(HttpStatus.NO_CONTENT)
void resetPassword(@Valid @RequestBody PasswordResetRequest request, HttpServletRequest servletRequest) {
    service.resetLocalPassword(new ResetPasswordCommand(request.identifier(), request.newPassword()), servletRequest.getRemoteAddr());
}
```

Require both `app.local-password-reset-enabled` and a loopback remote address. Replace the stored BCrypt hash through a domain method; do not expose it.

- [ ] **Step 4: Re-run focused Java tests and then `mvn test -q`**

- [ ] **Step 5: Record the configuration key in `.env.example` without a secret value**

### Task 2: Authentication UI, Confirmation, And Top Notices

**Files:**
- Modify: `front/src/api/contracts.ts`
- Modify: `front/src/stores/auth.ts`
- Create: `front/src/ui/notifications.ts`
- Modify: `front/src/views/RegisterView.vue`
- Modify: `front/src/views/LoginView.vue`
- Create: `front/src/views/ForgotPasswordView.vue`
- Modify: `front/src/router/index.ts`
- Modify: `front/src/i18n/messages.ts`
- Test: `front/src/views/RegisterView.spec.ts`
- Test: `front/src/views/LoginView.spec.ts`
- Create: `front/src/views/ForgotPasswordView.spec.ts`
- Create: `front/src/stores/auth.spec.ts`

**Interfaces:**
- Consumes: `POST /api/v2/auth/password-reset` with `{ identifier, newPassword }`.
- Produces: `/forgot-password` route and consistent top notification calls for login/register outcomes.

- [ ] **Step 1: Write failing Vue tests**

```ts
expect(mockRegister).not.toHaveBeenCalled()
expect(wrapper.text()).toContain('两次输入的密码不一致')
expect(request).toHaveBeenCalledWith(expect.objectContaining({
  url: '/api/v2/auth/password-reset',
  data: { identifier: 'alice', newPassword: 'replacement-password' },
}))
```

Mock the notification wrapper and assert success on authentication success and a safe failure notice on API rejection.

- [ ] **Step 2: Run focused tests and observe expected failure**

Run: `corepack pnpm exec vitest run src/views/RegisterView.spec.ts src/views/LoginView.spec.ts src/views/ForgotPasswordView.spec.ts src/stores/auth.spec.ts`

- [ ] **Step 3: Implement client-only confirmation and recovery form**

Use `ElMessage` via one small wrapper, keep confirmation out of HTTP payloads, clear any local token on reset success, and route to `/login` after notifying.

- [ ] **Step 4: Run focused tests, then `corepack pnpm exec vitest run` and `corepack pnpm build`**

### Task 3: Integration And Documentation Evidence

**Files:**
- Modify: `README.md`
- Modify: `docs/verification/mvp-evidence.md`
- Test: `tests/integration/run_mvp_flow.ps1` if it can safely cover the new local route without credentials in source control.

- [x] **Step 1: Add a no-secret startup note for `APP_LOCAL_PASSWORD_RESET_ENABLED=true` in local development**
- [ ] **Step 2: Verify contract validation, Java tests, frontend tests/build, and a local reset followed by username login**
- [x] **Step 3: Record that direct reset is local-only and that email/token recovery is intentionally not implemented**
