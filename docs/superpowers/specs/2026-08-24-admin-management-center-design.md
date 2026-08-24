# LianYu Admin Management Center Design

Status: Proposed and interactively approved

Date: 2026-08-24

## 1. Objective

Build a Windows desktop management application, distributed as an independent
EXE, for operating the LianYu platform. The cloud server continues to host only
backend and infrastructure services; no administrator web UI is deployed.

The management center covers administrator accounts and permissions, users,
client releases, announcements, moderation, characters, AI configuration,
service health, and audit history. Delivery is phased so the first release is a
secure, usable operating loop rather than a collection of incomplete screens.

## 2. Confirmed Decisions

- Build a separate `LianYu Admin.exe`, not a hidden mode in the consumer app.
- Keep the admin client and backend module in the existing monorepo.
- Add an isolated Spring Boot module and `/api/admin/v1/**` API namespace.
- Support multiple super administrators and full role-based access control.
- Use username and password by default; TOTP 2FA is optional per account.
- Expose the admin API over public HTTPS. Device binding and private-network-only
  access are not required.
- Support release upload from a local EXE and import from an allowlisted GitHub
  repository.
- Support stable, beta, percentage rollout, selected-user/device rollout,
  minimum supported versions, forced updates, and rollback.
- Private conversation and memory content is hidden by default. A user must
  issue a one-time, scoped, expiring support grant before an administrator can
  read a selected conversation.
- Continue using the current MySQL, Redis, MinIO, RabbitMQ, and API gateway.
- Never perform a Windows build on the Linux server. The server validates,
  stores, selects, and publishes prebuilt artifacts.

## 3. Scope

### Phase 1: Secure operating loop

- Administrator login, optional TOTP, session management, and password reset.
- Multiple super administrators, roles, permissions, and custom roles.
- User search, account status, session revocation, and temporary/permanent bans.
- Client release drafts, local EXE upload, GitHub import, validation, stable/beta
  publication, rollback, and release notes.
- Announcements and targeted in-app notices.
- Append-only audit records and basic service health.
- Independent packaging and update flow for the Admin EXE.

### Phase 2: Operations and rollout

- Community moderation queues, reports, decisions, and appeals.
- Character visibility, publication state, and catalog management.
- Percentage rollout and selected-user/device rollout.
- Minimum supported versions, mandatory update policy, and rollback history.
- User-authorized support access to a selected conversation.
- Support cases and operational work queues.

### Phase 3: Platform governance

- Provider and model catalog management.
- Global AI routing policy and safe configuration editing.
- Queue, circuit breaker, rate limit, latency, token, and cost views.
- Alert rules and configuration approval workflows.

### Non-goals

- The admin client does not connect directly to MySQL, Redis, or MinIO.
- The server does not compile or sign Windows executables.
- The consumer LianYu client does not contain administrator screens or
  administrator credentials.
- Phase 1 does not attempt cross-region infrastructure orchestration.

## 4. Architecture

### 4.1 Admin desktop client

Create a root-level `admin/` application using Vue 3, Vite, Element Plus,
Pinia, and Electron. It has an independent package version and produces
`LianYu-Admin-Setup-<version>.exe`.

The client contains presentation, local session handling, resumable upload, and
API calls only. It never receives database credentials, MinIO access keys,
GitHub tokens, the master encryption key, or provider secrets.

The UI follows `DESIGN.md`: dark-first with equivalent light mode, existing
color and spacing variables, dense work-focused layouts, fixed navigation, and
restrained glass surfaces. It uses the LianYu brand without copying the
consumer app's immersive chat composition.

### 4.2 Backend module

Add `backend/lianyu-admin/` and load it from `lianyu-app` in the existing
backend process:

```text
lianyu-app -> lianyu-admin -> lianyu-service / lianyu-dao
                            -> lianyu-security / lianyu-storage
                            -> lianyu-common
```

The module owns administrator authentication, RBAC, audit, release orchestration,
moderation orchestration, and admin DTOs/controllers. Existing domain services
remain the source of truth for users, characters, conversations, and storage.

The API gateway routes `/api/admin/v1/**` to the existing backend with separate
login and upload rate limits. No second Java container or static admin website
is added.

### 4.3 Infrastructure

- MySQL stores administrator, permission, release, announcement, moderation,
  support grant, and audit metadata.
- Redis stores active admin sessions, login throttles, idempotency results, and
  release locks.
- MinIO stores private staging uploads and published update assets.
- RabbitMQ may run long validation/import jobs after Phase 1 if synchronous job
  execution becomes a bottleneck.
- GitHub credentials remain server-side and are restricted to the configured
  `whitequeen306/LianYuPC` repository.

## 5. Authentication and Authorization

### 5.1 Administrator identity

Administrator identities are separate from normal `user` records. A normal
account cannot be promoted into an administrator account.

The first super administrator is created by a one-time server bootstrap command.
There is no default username or password. Later administrators and super
administrators are created by an existing super administrator. The system must
always retain at least one active super administrator.

Passwords use BCrypt and the existing security module. Optional TOTP secrets
are encrypted at rest; recovery codes are stored as one-way hashes. TOTP is not
mandatory by default, but a super administrator may require it for selected
roles through a security policy.

Login defenses include per-account and per-IP rate limits, escalating temporary
lockouts, generic failure messages, login audit records, short access tokens,
rotating refresh tokens, idle locking in the EXE, and global session revocation.

### 5.2 RBAC

Permissions use stable identifiers such as:

```text
admin.read             admin.create           admin.disable
role.read              role.manage
user.read              user.ban               user.session.revoke
support.grant.read     support.conversation.read
release.read           release.upload         release.publish
release.rollback       release.policy.manage
announcement.manage   moderation.decide      character.manage
ai.config.read         ai.config.write        operations.read
audit.read
```

System roles provide sensible defaults: super administrator, operations,
moderation, release manager, and technical support. Custom roles compose the
same permissions. Every controller and service operation checks permission on
the server; hiding controls in the EXE is only a usability measure.

The super administrator role is a protected system role. Its permission set
cannot be weakened, and disabling or demoting the last active super
administrator is rejected.

## 6. Data Model

Flyway migrations add idempotent tables with appropriate unique constraints:

- `admin_user`: identity, password hash, status, optional encrypted TOTP data,
  password version, timestamps, and creator.
- `admin_role`: system/custom role metadata and active state.
- `admin_permission`: seeded permission catalog.
- `admin_user_role`: many-to-many user-role assignment.
- `admin_role_permission`: many-to-many role-permission assignment.
- `admin_session`: session inventory, token identifier hash, IP, issue/expiry,
  last activity, and revocation metadata. Token state remains in Redis.
- `admin_audit_log`: actor, action, target type/id, request summary, redacted
  before/after values, IP, result, error code, traceId, and timestamp.
- `app_release`: version, channel eligibility, source, notes, object keys,
  sizes, hashes, compatibility fields, state, and publication metadata.
- `release_channel`: current stable/beta release pointer and policy revision.
- `release_rollout`: deterministic percentage, user, and device targeting rules.
- `announcement`: content, audience, schedule, priority, and state.
- `moderation_case`: report source, target, evidence reference, assignment,
  decision, and appeal state.
- `support_access_grant`: hashed one-time code, user, conversation scope,
  allowed administrator, issue/expiry/use/revocation state.
- `admin_config_revision`: versioned, redacted configuration changes for later
  AI and operations governance.

Audit records cannot be deleted through admin APIs. Database backup and
retention policy cover long-term preservation.

## 7. API Surface

Representative endpoint groups:

```text
/api/admin/v1/auth/**
/api/admin/v1/admins/**
/api/admin/v1/roles/**
/api/admin/v1/users/**
/api/admin/v1/releases/**
/api/admin/v1/release-channels/**
/api/admin/v1/announcements/**
/api/admin/v1/moderation/**
/api/admin/v1/characters/**
/api/admin/v1/support-grants/**
/api/admin/v1/operations/**
/api/admin/v1/ai-config/**
/api/admin/v1/audit-logs/**
```

Mutation endpoints require an idempotency key. List endpoints use cursor or
bounded page pagination, explicit sorting, and server-side filtering. Responses
use the existing `Result<T>` envelope and traceId conventions.

Secrets are never returned after creation. Configuration and audit DTOs use a
central redaction policy for tokens, credentials, private keys, TOTP material,
and user private content.

## 8. Client Release Management

### 8.1 Local upload

1. The Admin EXE creates a release draft and an upload session.
2. The backend returns short-lived, object-scoped MinIO multipart presigned URLs.
3. The EXE uploads directly to a private staging prefix with resume support.
4. The backend checks object size, SHA-512, file name, SemVer, duplicate version,
   Windows executable metadata, and channel compatibility.
5. Validation produces a `READY` draft. A blockmap is optional in Phase 1
   because the current updater downloads the full installer.

The presigned URL grants no listing or unrelated object access and does not
expose MinIO credentials.

### 8.2 GitHub import

The administrator selects an allowlisted release/tag. The backend downloads the
EXE, optional blockmap, and manifest with explicit connect/read timeouts and
size limits. It then performs the same validation as a local upload. Arbitrary
repository names and arbitrary asset URLs are rejected.

### 8.3 Publication

Publication uses a Redis lock per channel and an idempotency key. Slow MinIO and
GitHub operations happen outside database transactions. The final short
transaction performs duplicate prevention, stores release metadata, switches
the channel pointer, and appends the audit record.

Assets are copied to their immutable published keys before the channel manifest
is changed. The channel manifest is always written last. A failed operation
leaves the previous public release untouched and the draft retryable.

Rollback switches the channel pointer to a previously validated release and
writes a new manifest revision. Old assets are retained according to policy and
are not deleted during rollback.

## 9. Update Channels and Compatibility

The existing `updates/latest.yml` remains the legacy stable manifest so current
clients continue to update. Phase 1 manages this manifest and adds a beta
manifest without breaking `v0.2.363`.

Percentage and selected-account/device rollout require a newer client resolver:

```text
GET /api/public/client-updates/resolve
  ?currentVersion=...
  &channel=stable|beta
  &deviceId=...
```

Authenticated requests also use the user ID. Rollout selection is deterministic
using a salted hash, so the same user/device does not oscillate between cohorts.
The response contains the selected version, URL, size, SHA-512, mandatory flag,
minimum supported version, notes, and policy revision.

The consumer client stores a random installation device ID, supports channel
selection where permitted, and enforces mandatory updates only after receiving
a valid signed/HTTPS response. Legacy clients continue using static stable
`latest.yml`; fine-grained rollout starts from the first compatible client
baseline.

## 10. User Privacy Support Flow

1. A signed-in user selects a conversation and requests a support access code.
2. The backend stores only a hash of the one-time code with conversation scope
   and a short expiry.
3. An administrator with `support.conversation.read` enters the code in a
   support case.
4. Successful redemption binds the grant to that administrator and case.
5. Only the selected conversation is readable until expiry or user revocation.
6. Every read records administrator, case, conversation, time, and traceId.

Account statistics and operational metadata remain available under `user.read`,
but conversation and memory bodies are never returned without an active grant.

## 11. Error Handling and Recovery

- Uploads are resumable and staging objects expire through a cleanup job.
- Repeated mutations return the stored idempotent result rather than repeating
  the operation.
- Concurrent publication to the same channel is rejected or queued.
- Validation errors preserve the draft and show actionable, non-secret details.
- GitHub, MinIO, and other HTTP calls use the repository-standard timeout-aware
  client factories; they never use zero-timeout clients.
- Slow network/file operations never run inside `@Transactional` methods.
- A failed release never changes the active manifest.
- Failed high-privilege actions are audited as well as successful ones.
- The Admin EXE preserves safe draft metadata locally but never persists raw
  passwords, TOTP codes, refresh tokens in plaintext, or infrastructure secrets.

## 12. Observability

Admin actions propagate traceId from Electron through the API gateway and
backend. Structured logs include action category, actor ID, target ID, result,
duration, and release/job ID without sensitive payloads.

The operations view initially exposes backend/gateway health, queue depth,
database pool state, Redis reachability, MinIO reachability, current client
channels, recent failed jobs, and high-risk audit events. Detailed AI cost and
latency dashboards arrive in Phase 3.

## 13. Testing

### Unit tests

- Permission resolution, protected super administrator invariants, password and
  optional TOTP flows.
- SemVer comparison, release state transitions, deterministic rollout, channel
  policy, redaction, and support grant expiry.

### Integration tests

- Testcontainers for MySQL, Redis, and MinIO.
- Login throttling, session revocation, RBAC enforcement, release locking,
  idempotency, Flyway migrations, audit persistence, manifest-last publication,
  and rollback.

### Security tests

- Horizontal and vertical privilege escalation.
- Brute-force login, refresh-token replay, revoked sessions, malicious file
  names, oversized uploads, object-key traversal, arbitrary GitHub URL import,
  audit redaction, and expired/replayed support codes.

### Desktop end-to-end tests

- Electron launch, login, optional 2FA setup, role-aware navigation, user ban,
  local upload resume, GitHub import, stable/beta publish, rollback, and session
  revocation.
- Playwright screenshots across supported Windows viewport sizes; verify no
  clipped text, overlapping controls, blank views, or theme mismatch.

## 14. Deployment and Migration

1. Add Flyway migrations and the `lianyu-admin` module behind a disabled-by-
   default production feature flag.
2. Deploy the backend and run the one-time first-super-admin bootstrap command.
3. Enable `/api/admin/v1/**` routing, rate limits, and presigned upload routing.
4. Build and distribute the independent Admin EXE to administrators.
5. Enable Phase 1 modules after smoke and permission tests.
6. Migrate release control from the local script to the admin release API while
   retaining the script as an emergency recovery path until two successful
   admin-driven releases have completed.

Backend changes use `local/ship-release.ps1 -BackendOnly`; Admin EXE changes use
its dedicated Electron release target. Changes affecting both run the full
release workflow. The normal consumer Electron artifact is not rebuilt for
admin-only UI changes, except when adding the rollout-aware updater.

## 15. Phase 1 Acceptance Criteria

- Multiple super administrators and custom roles can be created safely.
- Username/password login works; optional TOTP can be enabled and recovered.
- No normal user token can access an admin endpoint.
- User search, ban, and session revocation are permission-checked and audited.
- A local EXE and a GitHub Release can each become validated release drafts.
- Stable and beta can publish independently without exposing partial assets.
- Failed publication leaves the current client update unchanged.
- A prior validated release can be restored with one rollback action.
- Announcements can be drafted, scheduled, published, and withdrawn.
- Service health and failed jobs are visible without exposing secrets.
- Audit records can be searched by actor, action, target, result, time, and
  traceId and cannot be deleted through admin APIs.
- The server hosts only backend/infrastructure services; the admin UI is shipped
  exclusively as a Windows EXE.
