# Evidence Workbench Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 Vue 简历匹配平台改造成具有“证据工作台”辨识度的操作型界面，同时保持所有现有业务行为和前端测试定位不变。

**Architecture:** 使用现有 Vue 组件和 class 结构，集中重构 `style.css` 的设计令牌、布局、状态和响应式规则；只在 `App.vue` 增加必要的导航语义包装。页面组件继续负责业务状态、路由和 API 调用，视觉层不新增数据依赖。

**Tech Stack:** Vue 3, TypeScript, Element Plus, CSS custom properties, Vite.

**Spec:** `docs/superpowers/specs/2026-09-03-evidence-workbench-theme-design.md`

## Global Constraints

- 模式保持 `Operate`，优先保证扫描、输入、比较和恢复操作效率。
- 主色为石墨、冷白、深青绿；琥珀、珊瑚仅表示警告、缺口、失败和危险操作。
- 不使用大面积渐变、紫色主色或嵌套卡片；等宽字体只用于业务 ID、字符范围和配置内容。
- 不修改 Vue Router、Java/Python API、OpenAPI/JSON 字段、Pinia 状态、权限判断、任务轮询、`data-test` 属性或表单字段。
- 桌面目标宽度为 1440px，移动目标宽度为 390px；正文对比度至少 4.5:1，大字号至少 3:1。
- 用户已声明后续完整项目测试由其执行；本实现只做结构性审查、构建可运行性和一次 Impeccable detector 检查，不代替用户运行完整测试套件。

## File Map

- Modify `front/src/style.css`: 全局令牌、导航、认证、工作台、报告、模型配置、对话框、状态、响应式和焦点样式。
- Modify `front/src/App.vue`: 为当前导航添加可识别的工作区语义和活动路由状态，不改变退出逻辑或路由地址。
- Do not modify API/store/view business logic files unless a minimal class hook is required and the existing behavior is preserved.

### Task 1: Replace the global visual system

**Files:**
- Modify: `front/src/style.css`

**Interfaces:**
- Consumes: Existing semantic classes such as `.workspace`, `.workspace-header`, `.button-link`, `.button-secondary`, `.error`, `.success`, `.record-id`.
- Produces: Stable CSS variables and shared control states consumed by every existing view.

- [ ] Step 1: Define the evidence-workbench palette and spacing tokens in `:root`, including surface, ink, muted, accent, warning, danger, success, border, focus and shadow values.
- [ ] Step 2: Replace the starter Vite typography and dark-mode defaults with a single readable system sans stack, fixed rem type scale, tabular numerals for metrics, and monospace only for identifiers/configuration.
- [ ] Step 3: Add global body, selection, scrollbar, anchor, button, input, textarea, select and focus-visible rules with explicit hover, active, disabled and invalid states.
- [ ] Step 4: Keep the existing class names and append scoped overrides for old starter selectors so no view loses its layout.
- [ ] Step 5: Inspect the resulting CSS for conflicting duplicate rules and remove only obsolete starter declarations that are superseded by the new token system.

### Task 2: Reframe the authenticated application shell

**Files:**
- Modify: `front/src/App.vue`
- Modify: `front/src/style.css`

**Interfaces:**
- Consumes: `useAuthStore`, existing `RouterLink`, `RouterView`, `RouteErrorBoundary` and logout handler.
- Produces: A stable top bar with brand, workspace navigation, user identity and logout action; no route or auth contract changes.

- [ ] Step 1: Add a compact navigation group linking to `/resumes`, `/match`, `/profiles` and the role-appropriate recovery route while preserving the current user identity and logout button.
- [ ] Step 2: Use `RouterLink` active classes and `aria-label` values so the current workspace is visible without changing route guards.
- [ ] Step 3: Style the shell as a quiet graphite header with a thin status rule, responsive collapse, and keyboard-visible focus states.
- [ ] Step 4: Verify the header remains absent for guest routes and that the existing logout function still pushes `/login`.

### Task 3: Style operational input and lifecycle surfaces

**Files:**
- Modify: `front/src/style.css`
- Review only: `front/src/views/ResumeListView.vue`, `front/src/views/UploadMatchView.vue`, `front/src/views/ResumeRematchView.vue`, `front/src/views/ModelProfilesView.vue`

**Interfaces:**
- Consumes: Existing form fields, task status text, lifecycle actions, recovery links and model configuration controls.
- Produces: Consistent workbench layouts, form grouping, list rows, status panels and action hierarchy.

- [ ] Step 1: Convert `.workspace`, `.workspace-header`, `.workspace-nav`, `.operation-panel`, `.operation-status`, `.record-list` and `.record-row` into a consistent ledger grid with predictable spacing and no nested card treatment.
- [ ] Step 2: Add fieldset/label/help/error styling that makes required inputs, model selection, job description and retention notes scannable.
- [ ] Step 3: Give loading, empty, success, failure, timeout, blocked and deletion-confirmation states distinct semantic surfaces using color plus text, never color alone.
- [ ] Step 4: Ensure long titles, IDs and model names wrap without changing row height unpredictably or causing horizontal overflow.

### Task 4: Make the matching report the visual centerpiece

**Files:**
- Modify: `front/src/style.css`
- Review only: `front/src/views/MatchResultView.vue`, `front/src/components/MatchEvidenceTable.vue`

**Interfaces:**
- Consumes: Existing score band, evidence table, review plan, targeted review items, model suggestions and duplicate-title warning.
- Produces: A readable evidence-first report hierarchy with clear confidence/risk states.

- [ ] Step 1: Style `.result-workspace`, `.job-description` and `.score-band` as an evidence header with restrained score emphasis and tabular numerals.
- [ ] Step 2: Improve evidence-table density, column headers, row separation, non-positive states and mobile overflow while preserving the table DOM and data.
- [ ] Step 3: Style `.review-plan`, `.targeted-review-list`, `.suggestion-list` and state badges so low/medium/high match guidance is immediately distinguishable and actionable.
- [ ] Step 4: Keep unsupported or confirmation-required suggestions visually prominent without implying automatic application.

### Task 5: Authentication, dialogs and responsive finish

**Files:**
- Modify: `front/src/style.css`
- Review only: `front/src/views/LoginView.vue`, `front/src/views/RegisterView.vue`, `front/src/views/ForgotPasswordView.vue`, `front/src/components/DeleteResumeDialog.vue`, `front/src/components/RecoveryDialog.vue`

**Interfaces:**
- Consumes: Existing auth forms, top notifications, delete confirmation and recovery dialogs.
- Produces: Focused guest forms, protected destructive actions and responsive layouts consistent with the workbench theme.

- [ ] Step 1: Style `.auth-page`, `.auth-panel` and `.auth-links` as a calm single-column entry surface with clear success/error notice treatment.
- [ ] Step 2: Style dialog backdrop, panel, headings, confirmation input and action buttons with protected focus and danger semantics.
- [ ] Step 3: Add responsive breakpoints for 1440px, 800px, 600px, 480px and 390px behavior; collapse navigation and grids structurally rather than shrinking typography fluidly.
- [ ] Step 4: Check keyboard focus, reduced-motion preference, text wrapping and no-overlap behavior across the existing routes.

### Task 6: Structural review and handoff evidence

**Files:**
- Review: `front/src/App.vue`, `front/src/style.css`, all files named above

- [ ] Step 1: Run `corepack pnpm build` from `front` and record the exit status.
- [ ] Step 2: Run `node C:\Users\theking.guo\.agents\skills\impeccable\scripts\detect.mjs --json front/src/style.css front/src/App.vue` exactly once after edits; classify remaining findings as fixed or intentional.
- [ ] Step 3: Review the diff for accidental changes to routes, API calls, stores, `data-test` attributes and form names.
- [ ] Step 4: Record user-owned pending validation: full Vitest, Playwright, Java/Python integration and real-provider checks remain for the user to run.
