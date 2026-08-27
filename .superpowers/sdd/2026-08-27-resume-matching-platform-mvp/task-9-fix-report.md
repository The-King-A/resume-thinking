# Task 9 Fix Round Report

## Status

Completed in `feature/resume-front-lane`.

- Code commit: `c2192b5` (`fix(web): close lifecycle review gaps`)
- Contract: frozen `contracts/openapi/v1/openapi.yaml` 1.0.0; no contract files changed.

## Review Fixes

- `MatchResultView` watches the reactive route taskId, clears stale state/timers, and reloads the new task.
- Result view now shows the Java job description, while evidence shows requirement ID, evidence ID, source type, stable location, and source character range.
- 409 `TASK_NOT_READY` and 410 `TASK_GONE` retain explicit safe terminal states.
- Added administrator soft-delete API/UI at `/api/v1/admin/recovery/resumes/{resumeId}`; USER deletion remains `/api/v1/resumes/{resumeId}`.
- ADMIN navigation replaces the USER recovery link with administrator recovery; `RecoveryView` also redirects administrators to the admin workflow.
- Profile selection exposes every saved profile and defaults to the selected profile.
- Resume, user recovery, and admin recovery lists use contract pagination metadata with previous/next controls.

## Verification

- Review regression tests: 11/11 passed.
- Full suite: `pnpm --dir front exec vitest run --maxWorkers=2`, 14 files and 40 tests passed.
- Build: `pnpm --dir front run build`, TypeScript and Vite production build passed.
- Previous required lifecycle tests remain 5/5.

## Attention

- No live Java integration was available in this lane. Merge validation should exercise admin delete/restore, reactive task navigation, pagination, `Evidence.id`, optional `evidenceId` compatibility normalization, 409 `TASK_NOT_READY`, and 410 `TASK_GONE` against the Java service.
- Full Vitest uses two workers on this Windows host because unrestricted worker fan-out previously exited unexpectedly; all tests pass with the bounded worker count.

## Fix Round 1

Status: completed in `feature/resume-front-lane`.

Review fixes:

- `MatchResultView` now tags each task load and poll with the current route generation. Late responses from a previous `taskId` cannot write task/result/error state or schedule polling. A regression test covers a delayed old-task response after navigation.
- The result boundary now follows frozen OpenAPI v1 `Evidence`: required `id`, with no `evidenceId` compatibility normalization and no unknown evidence properties accepted. Malformed evidence is rejected; tests cover both `evidenceId` and missing `id`.
- `AdminRecoveryView` derives its role reactively and checks the current authenticated role at load and restore action time. A regression test covers demotion after mount.
- A polled `TASK_GONE` clears the stale task and result before showing the archived state, so processing cannot remain visible beside the terminal error. A focused polling test covers this transition.

Verification:

- Focused regressions: `pnpm exec vitest run src/api/lifecycle.spec.ts src/views/MatchResultView.spec.ts src/views/AdminRecoveryView.spec.ts --maxWorkers=2` (3 files, 13 tests passed).
- Full frontend suite: `pnpm exec vitest run --maxWorkers=2` (14 files, 45 tests passed).
- Build: `pnpm run build` (TypeScript and Vite production build passed).
- `git diff --check` passed; only the repository's existing LF/CRLF conversion notices were emitted.
