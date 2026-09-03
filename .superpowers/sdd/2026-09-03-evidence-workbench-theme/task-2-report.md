# Task 2 Report: Authenticated Application Shell

## Changed markup and selectors

- Added an authenticated `nav` labeled `工作区导航` in `front/src/App.vue`.
- Added `RouterLink` entries for `/resumes`, `/match`, `/profiles`, and the role-appropriate recovery route: `/recovery` for regular users and `/admin/recovery` for administrators.
- Added the `global-nav-links`, `global-nav-link`, and `global-nav-link-active` shell selectors. Existing RouterLink behavior supplies active-route state through `active-class`.
- Reworked the global header shell rules in `front/src/style.css` to use graphite header surfaces, cool-white controls, and the deep teal active state while retaining visible keyboard focus styling and the existing 600px responsive breakpoint.

## Compatibility notes

- `useAuthStore`, `logout()`, the logout redirect to `/login`, guest/authenticated conditional rendering, `RouterView`, `RouteErrorBoundary`, route paths, form fields, and `data-test` attributes are unchanged in behavior.
- No router definitions, guards, API calls, stores, or view business logic were modified.
- The brand remains a `RouterLink` to `/resumes`; recovery navigation is hidden behind the same role check used by the existing recovery views.

## Lightweight checks

- `pnpm build` from `front/` passed: TypeScript project build and Vite production build completed successfully.
- Build emitted the existing third-party `@iarna/toml` direct `eval` warning; no new compile or bundling errors were reported.
