# Final Theme Fix Report

- Added explicit white text overrides for `.button-link:hover` and `.button-link:active` so dark accent controls retain readable labels.
- Added a graphite-safe `.global-brand:hover` color override.
- Darkened `--muted` from `#687780` to `#52616a`; existing muted and evidence metadata selectors inherit the stronger contrast on cool-white and semantic surfaces.
- Added `useRoute()`-based `global-nav-link-context-active` bindings for `/matches/:taskId` and `/resumes/:resumeId/rematch` context, without changing navigation destinations or route behavior.

Checks:

- `pnpm build` from `front/` passed.
- `git diff --check` passed for the changed shell files.
- Full test suite was intentionally not run.
