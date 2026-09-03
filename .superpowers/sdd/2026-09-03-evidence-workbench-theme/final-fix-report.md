# Final Theme Fix Report

- Added explicit white text overrides for `.button-link:hover` and `.button-link:active` so dark accent controls retain readable labels.
- Added a graphite-safe `.global-brand:hover` color override.
- Darkened `--muted` from `#687780` to `#52616a`; existing muted and evidence metadata selectors inherit the stronger contrast on cool-white and semantic surfaces.
- Added `useRoute()`-based `global-nav-link-context-active` bindings for `/matches/:taskId` and `/resumes/:resumeId/rematch` context, without changing navigation destinations or route behavior.

Checks:

- `pnpm build` from `front/` passed.
- `git diff --check` passed for the changed shell files.
- Full test suite was intentionally not run.

Additional accessibility polish:

- Replaced the alternative-directions hardcoded muted text color with `var(--muted)` for stronger contrast on review-plan surfaces.
- Added `.global-brand:active { color: #fff; }` alongside the hover state to prevent the generic warning active color on graphite.
- Added `.global-nav-link:active { color: #fff; }` so navigation text remains readable against teal during activation.
