# Updater and Chat Continuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document MinIO updater publishing, avoid global update dialogs when already current, and keep single-chat assistant replies persisted when the user leaves the chat page.

**Architecture:** Keep the updater runtime UX in frontend components while the publish flow stays in `CLAUDE.md` and `_upload_update_assets.py`. Preserve SSE streaming for visible chats, but treat client disconnects as a delivery problem rather than provider generation failure when full assistant content exists.

**Tech Stack:** Vue 3, Vitest, Spring Boot MVC `SseEmitter`, JUnit 5, Mockito, Maven.

---

### Task 1: Updater Dialog State

**Files:**
- Modify: `frontend/src/components/AppUpdateDialog.vue`
- Test: `frontend/src/composables/__tests__/useAppUpdater.test.js`

- [ ] Add a test or component-level assertion path proving `checking` and `no-update` do not auto-open the global dialog, while `update-available` does.
- [ ] Change the dialog watcher to auto-open only for `update-available`, `downloading`, `ready`, `installing`, and `error`.
- [ ] Run `npx vitest run src/composables/__tests__/useAppUpdater.test.js`.

### Task 2: Chat Stream Persistence

**Files:**
- Modify: `backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java`
- Modify: `backend/lianyu-service/src/test/java/com/lianyu/service/conversation/ConversationServiceStreamErrorTest.java`

- [ ] Add a failing test that invokes the stream callback with full content and an `IOException`/client disconnect style error, expecting assistant turn persistence.
- [ ] Keep the existing provider-error test behavior: provider errors with partial content must not persist.
- [ ] Implement minimal callback logic that persists when full content exists and the error represents client disconnect/delivery failure.
- [ ] Run the focused Maven test.

### Task 3: Release Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] Document that `electron:release` publishes GitHub Release backup first, then syncs assets into MinIO `updates/` from the cloud server.
- [ ] Document that `latest.yml` is written last and MinIO keeps only the latest 3 installer versions.

### Task 4: Verification and Review

**Files:**
- All changed files

- [ ] Run focused frontend and backend tests.
- [ ] Run a code review pass.
- [ ] Commit and push after verification.
