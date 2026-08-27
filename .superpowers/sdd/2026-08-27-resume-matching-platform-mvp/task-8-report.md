# Task 8 Report: Frontend Authentication and Model Profiles

## Scope

Implemented typed authentication, route guards, registration/login views, safe per-user OpenAI-compatible model profile configuration, and the required role/secret tests under `front/src`.

## TDD evidence

RED command (before implementation):

```text
pnpm --dir front exec vitest run src/views/RegisterView.spec.ts src/components/ModelProfileForm.spec.ts
```

Result: both suites failed during import because the shell had no `@vue/test-utils` dependency (components/stores/views were also absent at that point).

GREEN command:

```text
pnpm --dir front exec vitest run src/views/RegisterView.spec.ts src/components/ModelProfileForm.spec.ts
```

Result:

```text
Test Files  2 passed (2)
Tests       2 passed (2)
```

The role test mounts registration, selects the ADMIN control, submits, and asserts `role: 'ADMIN'`. The profile test mounts a saved profile and asserts the saved key literal is absent from rendered text.

## Build evidence

```text
pnpm --dir front run build
```

Result: `vue-tsc -b` passed and Vite produced the production bundle (`vite v8.2.2`, 92 modules transformed).

## Secret scan

```text
rg -n -i "secret-api-key|ciphertext|nonce" front/src front/dist -g '!*.map'
```

No provider key, ciphertext, or nonce value exists in source or built assets. The only `secret-api-key` match is the negative assertion in the test fixture. `apiKey` appears only as a write-only form field/request property; no value is persisted, rendered from a saved profile, logged, or bundled.

## Concerns

- This lane intentionally does not add resume lifecycle views; those belong to Task 9.
- The API base URL defaults to the contract's local server and can be overridden with `VITE_API_BASE_URL`.
- ADMIN registration is exposed because the product decision permits it; the UI explicitly discloses its cross-owner recovery scope and deployment risk.
