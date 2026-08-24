# LianYu Admin Management Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an independent Windows Electron management center and the isolated Spring Boot admin API needed to manage administrators, users, releases, announcements, operations, and audit history.

**Architecture:** Add a `backend/lianyu-admin` module to the existing backend process and expose `/api/admin/v1/**` through the current gateway. Add an independent `admin/` Vue/Vite/Electron app using Element Plus, TanStack Table, ECharts, Pinia, and Vue Router; it communicates only through the admin API. Reuse existing MySQL, Redis, MinIO, Sa-Token, and design tokens, while keeping release artifacts and infrastructure credentials server-side.

**Tech Stack:** Spring Boot 3.5, MyBatis-Plus, Flyway, Sa-Token, BCrypt, Redis, MinIO, Vue 3, Vite, Electron, Element Plus, TanStack Table, ECharts, Pinia, Vue Router, Vitest, JUnit/Testcontainers.

---

### Task 1: Extend design tokens for the desktop admin surface

**Files:**
- Modify: `DESIGN.md`
- Modify: `frontend/src/styles/_tokens.scss` (or the existing token source discovered by `rg --files frontend/src/styles`)
- Test: `frontend/src/styles/__tests__/adminTokens.test.js` (create if token tests do not exist)

- [ ] **Step 1: Add compact density, title-bar, and drawer tokens to DESIGN.md**

  Define named tokens for compact table row height, compact cell padding, table header height, frameless title-bar height, title-bar control width, and admin drawer widths. Keep all values on the existing 4px scale and use the existing color/radius/timing tokens.

- [ ] **Step 2: Mirror the tokens in the stylesheet token source**

  Add CSS/Sass variables with the same names and provide both dark and light values. Do not add hardcoded component colors.

- [ ] **Step 3: Add a token contract test**

  Assert that the compact density, title-bar, drawer, and EaseOutQuint transition variables exist in the compiled token source.

- [ ] **Step 4: Run the focused token test**

  Run `cd frontend; npm test -- --run src/styles/__tests__/adminTokens.test.js`.
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git add DESIGN.md frontend/src/styles && git commit -m "feat(design): add admin desktop density tokens"`

### Task 2: Create admin backend module and dependency wiring

**Files:**
- Create: `backend/lianyu-admin/pom.xml`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/AdminModuleMarker.java`
- Modify: `backend/pom.xml`
- Modify: `backend/lianyu-app/pom.xml`
- Test: `backend/lianyu-admin/src/test/java/com/lianyu/admin/AdminModuleSmokeTest.java`

- [ ] **Step 1: Add a module smoke test**

  Test that the admin module marker loads and its package is included by the application module.

- [ ] **Step 2: Add the Maven module**

  Depend only on existing `lianyu-common`, `lianyu-dao`, `lianyu-security`, `lianyu-storage`, Spring Web/Security infrastructure already managed by the parent, MyBatis-Plus, Flyway access, and test dependencies. Do not introduce a second web stack.

- [ ] **Step 3: Wire parent and app dependencies**

  Add `lianyu-admin` to the parent module list and `lianyu-app` dependency list following existing module ordering.

- [ ] **Step 4: Run the module test**

  Run `cd backend; mvn -pl lianyu-admin -am test`.
  Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

  `git add backend/pom.xml backend/lianyu-admin backend/lianyu-app/pom.xml && git commit -m "feat(admin): add backend module skeleton"`

### Task 3: Add administrator schema, permissions, and bootstrap

**Files:**
- Create: `backend/lianyu-dao/src/main/resources/db/migration/V51__admin_management_schema.sql`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminBootstrapService.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminIdentityProperties.java`
- Test: `backend/lianyu-admin/src/test/java/com/lianyu/admin/identity/AdminBootstrapServiceTest.java`

- [ ] **Step 1: Write migration tests for the schema contract**

  Assert required tables, unique constraints, protected super-admin invariant fields, and audit indexes using Testcontainers/MySQL or the repository's migration test pattern.

- [ ] **Step 2: Add idempotent Flyway tables**

  Create `admin_user`, `admin_role`, `admin_permission`, `admin_user_role`, `admin_role_permission`, `admin_session`, `admin_audit_log`, `app_release`, `release_channel`, `release_rollout`, `announcement`, `moderation_case`, `support_access_grant`, and `admin_config_revision` with UTC timestamps, status constraints, unique keys, and indexes for actor/action/target/time queries. Store password hashes and TOTP recovery-code hashes only; encrypt optional TOTP seed material through the existing security abstraction.

- [ ] **Step 3: Seed stable permission identifiers and protected roles**

  Seed the permissions from the design spec and system roles for super administrator, operations, moderation, release manager, and technical support. Use `INSERT ... SELECT ... WHERE NOT EXISTS` patterns so the migration is re-runnable.

- [ ] **Step 4: Implement first-super-admin bootstrap**

  Require explicit environment-provided bootstrap username/password, hash with BCrypt, refuse to overwrite an existing active super administrator, and emit no password or seed in logs.

- [ ] **Step 5: Run schema and bootstrap tests**

  Run `cd backend; mvn -pl lianyu-admin,lianyu-dao -am test`.
  Expected: BUILD SUCCESS and protected-role assertions pass.

- [ ] **Step 6: Commit**

  `git add backend/lianyu-dao/src/main/resources/db/migration backend/lianyu-admin && git commit -m "feat(admin): add identity and release schema"`

### Task 4: Implement admin authentication, optional 2FA, RBAC, and sessions

**Files:**
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthController.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthorizationService.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminSessionService.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminSecurityFilter.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminDtos.java`
- Modify: `backend/lianyu-security/src/main/java/com/lianyu/security/config/SaTokenConfig.java` only if a separate admin login type requires a focused extension
- Test: `backend/lianyu-admin/src/test/java/com/lianyu/admin/identity/AdminAuthServiceTest.java`
- Test: `backend/lianyu-admin/src/test/java/com/lianyu/admin/identity/AdminAuthorizationServiceTest.java`

- [ ] **Step 1: Write failing tests for login and permission behavior**

  Cover valid password login, generic invalid-credential response, per-account/IP lockout, optional TOTP challenge when enabled, refresh rotation, revoked-session rejection, super-admin protection, and vertical/horizontal permission denial.

- [ ] **Step 2: Implement password login and session issuance**

  Use existing BCrypt and Sa-Token primitives. Keep access tokens short-lived, rotate refresh tokens, store only token identifier hashes in `admin_session`, and use Redis for throttles and revocation.

- [ ] **Step 3: Implement optional TOTP lifecycle**

  Add enable/confirm/disable/recovery-code endpoints. Encrypt the seed with the existing security service, hash recovery codes, and never return seed material after setup.

- [ ] **Step 4: Implement server-side RBAC enforcement**

  Resolve permissions from role assignments on every admin request, cache only short-lived permission snapshots, and reject disabled users, expired sessions, revoked tokens, and protected-role violations.

- [ ] **Step 5: Implement admin management endpoints**

  Add create/list/disable/reset-password/assign-role/revoke-sessions operations with idempotency keys and mandatory audit events.

- [ ] **Step 6: Run focused security tests**

  Run `cd backend; mvn -pl lianyu-admin -am test -Dtest="*Admin*Test"`.
  Expected: PASS, including unauthorized and revoked-session cases.

- [ ] **Step 7: Commit**

  `git add backend/lianyu-admin backend/lianyu-security && git commit -m "feat(admin): add authentication and rbac"`

### Task 5: Add audit logging and privacy-scoped support grants

**Files:**
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/audit/AdminAuditService.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/audit/AdminAuditController.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/support/SupportGrantService.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/support/SupportGrantController.java`
- Modify: existing conversation read service/controller only at the authorization boundary
- Test: `backend/lianyu-admin/src/test/java/com/lianyu/admin/audit/AdminAuditServiceTest.java`
- Test: `backend/lianyu-admin/src/test/java/com/lianyu/admin/support/SupportGrantServiceTest.java`

- [ ] **Step 1: Write failing tests**

  Verify successful and failed mutations create redacted audit records; audit records cannot be deleted; support codes are one-time, scoped to one conversation, bound to one administrator after redemption, and rejected after expiry/revocation/replay.

- [ ] **Step 2: Implement append-only audit service**

  Capture actor, action, target, redacted before/after values, IP, result, error code, and traceId. Centralize redaction for passwords, tokens, TOTP, provider keys, and private content.

- [ ] **Step 3: Implement support grant issue/redeem/revoke**

  Store only a hash of the one-time code, enforce a short expiry and conversation scope, and require `support.conversation.read` at read time.

- [ ] **Step 4: Add conversation authorization guard**

  Preserve ordinary user access while returning conversation/memory bodies to administrators only with a live grant bound to the case and administrator.

- [ ] **Step 5: Run focused tests**

  Run `cd backend; mvn -pl lianyu-admin -am test -Dtest="*Audit*Test,*SupportGrant*Test"`.
  Expected: PASS.

- [ ] **Step 6: Commit**

  `git add backend/lianyu-admin backend/lianyu-web backend/lianyu-service && git commit -m "feat(admin): add audit and scoped support access"`

### Task 6: Implement release validation, staging upload, GitHub import, and publication

**Files:**
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseService.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseController.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseValidationService.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/release/GitHubReleaseImporter.java`
- Create: `backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseLockService.java`
- Modify: `backend/lianyu-storage` only through existing timeout-aware MinIO abstraction
- Test: `backend/lianyu-admin/src/test/java/com/lianyu/admin/release/ReleaseValidationServiceTest.java`
- Test: `backend/lianyu-admin/src/test/java/com/lianyu/admin/release/ReleasePublicationIntegrationTest.java`

- [ ] **Step 1: Write failing release tests**

  Cover SemVer validation, canonical file names, size limits, SHA-512, duplicate version rejection, channel policy, idempotency, concurrent channel locks, manifest-last ordering, failed publication isolation, and rollback pointer changes.

- [ ] **Step 2: Implement release metadata and state transitions**

  Add draft/uploading/validating/ready/published/rollout/stopped/rolled-back/archived transitions with explicit illegal-transition errors.

- [ ] **Step 3: Implement presigned multipart upload sessions**

  Return short-lived, object-scoped MinIO multipart URLs; persist only upload metadata; support resume and staging cleanup. Keep large-file bytes outside Spring memory and transactions.

- [ ] **Step 4: Implement allowlisted GitHub import**

  Use the repository-standard timeout-aware HTTP client, fixed repository/tag validation, bounded asset sizes, and server-side download/hash verification.

- [ ] **Step 5: Implement publication and rollback**

  Acquire the Redis channel lock, verify staged assets, copy immutable published objects, write the manifest last, update the channel pointer in a short transaction, and append audit records. Rollback changes only the channel pointer and manifest revision.

- [ ] **Step 6: Implement stable/beta resolver and rollout rules**

  Add deterministic salted-hash selection for percentage/user/device rollout and return minimum-supported-version and mandatory-update fields. Preserve legacy `updates/latest.yml` behavior for old clients.

- [ ] **Step 7: Run integration tests**

  Run `cd backend; mvn -pl lianyu-admin -am test -Dtest="*Release*Test"`.
  Expected: PASS with MinIO/Testcontainers coverage.

- [ ] **Step 8: Commit**

  `git add backend/lianyu-admin backend/lianyu-storage && git commit -m "feat(admin): add release publishing workflow"`

### Task 7: Build the Admin Electron shell and authentication flow

**Files:**
- Create: `admin/package.json`
- Create: `admin/vite.config.js`
- Create: `admin/src/main.js`
- Create: `admin/src/router/index.js`
- Create: `admin/src/stores/adminSession.js`
- Create: `admin/src/api/adminClient.js`
- Create: `admin/electron/main.js`
- Create: `admin/electron/preload.js`
- Create: `admin/src/layouts/AdminShell.vue`
- Create: `admin/src/pages/LoginPage.vue`
- Create: `admin/src/pages/OverviewPage.vue`
- Create: `admin/src/components/TitleBar.vue`
- Modify: root release scripts only after the app can build locally
- Test: `admin/src/**/__tests__/*.test.js`

- [ ] **Step 1: Add package and build configuration**

  Pin Vue/Vite/Electron/Element Plus/TanStack Table/ECharts/Pinia/Vue Router versions compatible with the existing frontend. Import TanStack Table and ECharts by feature, not globally. Add `dev`, `build`, `test`, and `electron:build` scripts.

- [ ] **Step 2: Implement frameless Electron window**

  Add secure preload IPC, draggable title-bar region, minimize/maximize/close controls, native resize behavior, fixed minimum dimensions, and no Node integration in renderer.

- [ ] **Step 3: Implement API client and session store**

  Add typed request helpers, Result envelope handling, traceId propagation, access/refresh rotation, logout, revoke-session handling, and permission-aware route guards.

- [ ] **Step 4: Implement login page**

  Support username/password login, optional TOTP challenge, recovery-code flow, lockout errors, and explicit server error states without revealing credential validity details.

- [ ] **Step 5: Implement shell and overview page**

  Use fixed sidebar, compact top toolbar, dynamic menu from permissions, stable table/workspace primitives, restrained glass only for title bar/sidebar/drawers/dialogs, and existing design tokens.

- [ ] **Step 6: Add focused renderer tests**

  Test route guards, permission menu filtering, session expiry redirect, login states, and frameless title-bar button IPC calls.

- [ ] **Step 7: Run frontend checks**

  Run `cd admin; npm install; npm test -- --run; npm run build`.
  Expected: PASS and a non-empty renderer bundle.

- [ ] **Step 8: Commit**

  `git add admin && git commit -m "feat(admin): add electron management shell"`

### Task 8: Build release, user, announcement, and audit management screens

**Files:**
- Create: `admin/src/pages/UsersPage.vue`
- Create: `admin/src/pages/ReleasesPage.vue`
- Create: `admin/src/pages/AnnouncementsPage.vue`
- Create: `admin/src/pages/AdminsRolesPage.vue`
- Create: `admin/src/pages/AuditLogsPage.vue`
- Create: `admin/src/components/DataTableToolbar.vue`
- Create: `admin/src/components/DetailDrawer.vue`
- Create: `admin/src/components/ReleaseUploadDialog.vue`
- Create: `admin/src/components/ReleaseTimeline.vue`
- Create: `admin/src/components/PermissionGate.vue`
- Test: `admin/src/pages/__tests__/*.test.js`

- [ ] **Step 1: Implement reusable table and drawer primitives**

  Add TanStack Table server-side sorting/filtering/pagination, compact density, column visibility, selection, keyboard focus, and responsive overflow. Add detail drawers with stable widths and 0.2-0.28s EaseOutQuint motion.

- [ ] **Step 2: Implement user management**

  Add searchable user table, status filters, ban/unban confirmation dialog, session revocation, and support-grant entry point. Gate mutations with permissions.

- [ ] **Step 3: Implement release management**

  Add drafts, local file picker, resumable upload progress, GitHub import, validation results, stable/beta channel tabs, rollout controls, publish confirmation, release timeline, and rollback dialog.

- [ ] **Step 4: Implement admin/RBAC management**

  Add administrator table, role editor, permission matrix, optional 2FA status, session revocation, and protected super-admin constraints.

- [ ] **Step 5: Implement announcements and audit logs**

  Add announcement draft/publish/withdraw flow and audit search by actor/action/target/result/time/traceId with redacted details.

- [ ] **Step 6: Add ECharts only for useful trends**

  Add low-saturation, axis-labeled activity and release adoption trends only where the corresponding table is insufficient. Do not create a decorative dashboard wall.

- [ ] **Step 7: Run renderer tests and visual checks**

  Run `cd admin; npm test -- --run` and use Playwright screenshots at desktop and compact desktop sizes. Expected: no clipped text, overlapping controls, blank panes, or unthemed light mode.

- [ ] **Step 8: Commit**

  `git add admin && git commit -m "feat(admin): add operations management screens"`

### Task 9: Add backend deployment, updater compatibility, and release packaging

**Files:**
- Modify: `docker-compose.yml` only if the existing backend image needs the module dependency
- Modify: `backend/lianyu-app/src/main/resources/application.yml`
- Modify: `frontend/electron/updater/updater.js` and `frontend/electron/preload.js` when rollout resolver support is ready
- Create: `admin/scripts/electron-pack.mjs`
- Create: `admin/scripts/electron-release.mjs`
- Modify: `local/ship-release.ps1`
- Create: `admin/README.md`
- Test: `admin/scripts/__tests__/releasePackaging.test.js`

- [ ] **Step 1: Add feature flags and admin API routing configuration**

  Keep admin endpoints disabled until migrations and bootstrap complete; configure gateway routing, rate limits, upload limits, and allowed origins using environment variables.

- [ ] **Step 2: Add rollout-aware consumer updater compatibility**

  Preserve static `latest.yml` for legacy clients. For compatible clients, call the resolver, validate HTTPS/allowed origin, compare minimum supported version, and retain SHA-512 verification before installation.

- [ ] **Step 3: Add independent Admin EXE packaging**

  Build `LianYu-Admin-Setup-<version>.exe` with its own icon, output directory, version metadata, and smoke-launch test. Do not include consumer payload or server credentials.

- [ ] **Step 4: Extend the official release script**

  Add an admin-only release mode that runs backend deployment when backend changes exist and Admin Electron packaging when admin changes exist. Keep `-ElectronOnly` and `-BackendOnly` semantics explicit.

- [ ] **Step 5: Run local package checks**

  Run `cd admin; npm test -- --run; npm run build`, build the backend image with `docker compose up -d --build backend`, verify health, and run the Admin EXE smoke test.

- [ ] **Step 6: Commit**

  `git add admin docker-compose.yml backend/lianyu-app/src/main/resources/application.yml frontend/electron local/ship-release.ps1 && git commit -m "build(admin): add deployment and packaging workflow"`

### Task 10: End-to-end staging verification and handoff

**Files:**
- Create: `docs/superpowers/runbooks/admin-management-center.md`
- Modify: `docs/superpowers/specs/2026-08-24-admin-management-center-design.md` only for verified implementation deviations

- [ ] **Step 1: Run backend and frontend test suites**

  Run backend Maven tests, `cd frontend; npm test -- --run`, and `cd admin; npm test -- --run`. Expected: all suites pass.

- [ ] **Step 2: Verify staging deployment**

  Run the repository-required backend image rebuild, check `docker compose ps backend`, verify actuator health, and smoke-test admin login, permission denial, user ban, release draft, upload, publish, rollback, announcement, and audit search.

- [ ] **Step 3: Run security checks**

  Execute OSV Scanner for both lockfiles, inspect for credentials in tracked files, and run the admin authorization test matrix.

- [ ] **Step 4: Verify desktop UX**

  Capture Admin EXE screenshots in dark/light mode and compact/normal desktop sizes. Confirm native title-bar controls, table density, drawers, dialogs, and no template-style dashboard composition.

- [ ] **Step 5: Write the runbook**

  Document bootstrap of the first super administrator, enabling optional 2FA, creating roles, publishing/rolling back a release, revoking sessions, rotating server credentials, and recovering a failed staging upload.

- [ ] **Step 6: Commit verified runbook and final implementation changes**

  `git add docs/superpowers/runbooks docs/superpowers/specs && git commit -m "docs(admin): add operations runbook"`

## Self-review checklist

- [x] Every confirmed design area maps to one or more implementation tasks.
- [x] No task requires WSL; Windows builds run with Node/Electron and the server remains Linux backend-only.
- [x] No task introduces a complete third-party admin template.
- [x] Credentials, MinIO keys, GitHub tokens, and database access remain server-side.
- [x] Slow HTTP/storage operations stay outside transactions and use explicit timeouts.
- [x] Phase 1 has a complete acceptance path before Phase 2/3 expansion.
- [x] Placeholder language and unspecified file targets were removed or tied to an explicit repository inspection step.
