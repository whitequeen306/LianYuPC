# Startup, Chat Continuity, And Auth Routing Design

## Goal

Resolve four related desktop app issues without broad architectural churn:

1. Reduce perceived startup latency so the main window becomes usable faster.
2. Prevent single-chat replies from being interrupted when the user leaves the chat page after sending a message.
3. Skip the landing page when a valid local login session already exists and route directly into the authenticated app shell.
4. Ensure character cards show the character's latest reply preview instead of the user's latest message.

## Current Context

### Startup path today

- `frontend/electron/main.js` creates the main Electron window hidden, then reveals it on `did-finish-load` or `ready-to-show`.
- `frontend/electron/main.js` always loads `#/` first via `loadRoute(win, '#/')`.
- `frontend/src/router/index.js` defines `/` as `LandingPage` and only redirects to `/app` when `router.beforeEach()` sees both a token and `userStore.isLoggedIn`.
- `frontend/src/main.js` mounts the app first, waits for `router.isReady()`, dismisses the boot splash, then asynchronously runs `prepareAuthRoute(pinia)` and `bootstrapAuth(pinia)`.
- `frontend/src/auth/bootstrap.js` can recover a persisted token from Electron main-process storage, but that work currently happens after mount, so the first rendered route can still be the landing page even when the session is valid.
- `frontend/src/App.vue` may fetch the user profile again after mount when the user is logged in but `userId` is still missing.

Result: the shell appears later than necessary, and authenticated users can briefly see the landing page before a redirect to `/app`.

### Single-chat stream path today

- `frontend/src/pages/ChatPage.vue` starts a streaming fetch with `sendMessageStream()` and `drainAssistantStream()`.
- `ChatPage` uses `useStreamAbort()` and also keeps a local `streamController` variable for cleanup comments.
- `ChatPage` calls `streamController?.abort()` in `onUnmounted()`.
- `frontend/src/pages/QuickChatPage.vue` has the same pattern: a send starts an SSE stream, and unmount aborts it.
- `backend/lianyu-service/.../ConversationService.java` already contains server-side logic intended to persist the full assistant reply even if the client disconnects after generation completes.
- `frontend/src/stores/notifications.js` can already refresh the active chat when a notification arrives, but only if that chat page is still active.

Result: leaving the page can still abort the client request early enough to stop the reply path in practice, depending on provider and network behavior.

### Character card preview behavior today

- The user reported that role cards are showing the user's latest sent message instead of the role's latest reply.
- This likely comes from conversation preview selection logic using the latest message regardless of role.
- The fix must be role-aware: prefer the latest assistant message for preview text, and only fall back when no assistant reply exists yet.

## Chosen Approach

Use the minimal, low-risk solution:

- Keep the existing page architecture and polling/notification model.
- Move lightweight auth route preparation earlier so the initial route is correct before the first meaningful screen is chosen.
- Keep startup visually fast by prioritizing shell render and deferring non-critical data fetches.
- Decouple an in-flight single-chat send from page unmount so navigation does not cancel the reply.
- Reuse existing persistence, notification, and polling mechanisms to surface the finished reply after navigation.
- Adjust conversation preview selection to prefer assistant content.

This avoids introducing a new global streaming store or broader app-state refactor.

## Design

### 1. Startup fast path

#### Intent

Show a usable authenticated shell sooner, while avoiding the incorrect `LandingPage -> /app` flash for users who already have a valid persisted session.

#### Changes

- In `frontend/src/main.js`, perform the lightest possible auth bootstrap before the app commits to the initial route:
  - recover token from Electron main process via the existing `bootstrapAuthToken()` path,
  - seed the user store with cached profile data if present,
  - decide whether `/`, `/login`, or `/register` should be rewritten to `/app` before users see the wrong page.
- Keep heavy work out of the critical path:
  - do not block first screen on `fetchProfile()`, notifications sync, character list fetch, provider list fetch, or any other network-bound tasks.
  - let those continue after mount and after the correct route is already active.
- Preserve the boot splash error handling and the CSS preload recovery added in `0.2.263`.

#### Outcome

- Authenticated users go straight into `/app` on startup.
- Unauthenticated users still land on `LandingPage`.
- Main shell render is no longer delayed by avoidable route correction work happening after mount.

### 2. Auth-aware initial routing

#### Intent

Only unauthenticated users should see the landing page. Existing local sessions should start on the authenticated home shell immediately.

#### Changes

- Update the auth-preparation flow so route correction happens before the landing page meaningfully renders.
- In `frontend/src/router/index.js` and/or `frontend/src/auth/bootstrap.js`, keep the existing public/guest semantics but stop relying on `userStore.isLoggedIn` becoming fully hydrated after mount to decide the first route.
- Treat a recovered persisted token as sufficient for initial route selection to `/app`.
- Keep later session validation behavior unchanged:
  - if a later `restoreSession()` or profile fetch proves the session invalid, existing logout/401 handling still clears auth and returns the user to the public path.

#### Outcome

- No more landing-page flash for already logged-in users.
- No change to the login requirement for actually using protected APIs.

### 3. Single-chat continuity after navigation

#### Intent

Once the user sends a single-chat message, the role reply should continue to completion and be saved even if the user leaves the chat page.

#### Changes

- Change single-chat send lifecycle from page-bound to send-bound.
- In `frontend/src/pages/ChatPage.vue`:
  - stop aborting the active send stream just because the page unmounted,
  - only stop page-local UI updates after unmount,
  - keep the request alive long enough for the server to finish and persist the reply.
- Apply the same rule in `frontend/src/pages/QuickChatPage.vue`.
- Keep the current UI rule that an unmounted page does not continue incremental token-by-token rendering into destroyed state.
- Continue using `isUnmounted` guards so the finished request does not write reactive state back into a destroyed component.
- Allow explicit cancellation only from deliberate cancellation paths, not passive route navigation.

#### Practical behavior

- If the user stays on the chat page, behavior remains the same: stream, render, reconcile, refresh.
- If the user leaves mid-reply:
  - the page stops rendering that live stream,
  - the network request is not proactively aborted,
  - the backend completes and persists the reply when possible,
  - returning to the chat refreshes and shows the saved assistant messages.

#### Outcome

- Navigating away no longer blocks role replies.
- The fix stays minimal and does not require a new global stream store.

### 4. Reply resurfacing after navigation

#### Intent

A completed reply must still appear in the app even if the originating chat page is no longer mounted.

#### Changes

- Reuse existing notification and polling paths instead of creating a second delivery mechanism.
- Ensure the relevant list/detail surfaces refresh from persisted conversation state when new assistant messages arrive.
- Keep `ChatPage` and `QuickChatPage` refresh-on-enter behavior so returning to the conversation immediately shows stored messages.
- Ensure conversation/card preview data is refreshed from persisted messages rather than relying only on the just-mounted page's in-memory stream state.

#### Outcome

- The user sees the finished role reply after returning to the conversation or when related list surfaces refresh.

### 5. Character card preview selection

#### Intent

Character cards should reflect what the role most recently said, not what the user most recently typed.

#### Changes

- Find the conversation preview mapping used by character cards / conversation cards.
- Change preview text selection order to:
  1. latest assistant message with non-empty content,
  2. if none exists, current fallback behavior (likely latest message or default empty copy).
- Keep timestamps/order derived from actual latest conversation activity unless the current UI specifically requires assistant-message time for sorting. Sorting behavior should only change if preview logic currently depends on the wrong message object.

#### Outcome

- Card content matches the role's newest reply.
- Newly completed replies remain visible on cards even when the user sent the last message and then left the page.

## Files Likely To Change

### Frontend startup and routing

- `frontend/src/main.js`
- `frontend/src/auth/bootstrap.js`
- `frontend/src/router/index.js`
- `frontend/src/App.vue` (only if redundant post-mount auth/profile work must be reduced)

### Frontend chat continuity

- `frontend/src/pages/ChatPage.vue`
- `frontend/src/pages/QuickChatPage.vue`
- `frontend/src/composables/useStreamAbort.js` (only if current API forces page-unmount abort semantics)

### Frontend card preview / list refresh

- The conversation or character-list surface that derives card preview text, likely one or more of:
  - `frontend/src/pages/CharactersPage.vue`
  - relevant store/composable/api mapping files once identified during implementation

### Backend

- No backend change is planned initially because the disconnect-persist logic already exists in `ConversationService.java`.
- Backend investigation is still required during implementation to confirm deployed behavior matches repository behavior.

## Error Handling

### Startup

- If persisted token recovery fails, continue as anonymous and show the landing page.
- If later profile/session validation fails with 401, clear auth and fall back to public routes using existing logic.
- Do not let startup fast-path failures regress into the blackscreen issue fixed in `0.2.263`.

### Chat continuity

- If the request genuinely fails before generation completes, preserve current user-visible error handling.
- If the page is gone, do not surface page-local toast mutations into destroyed state.
- If the backend completes and saves the reply, the user must still recover it by refresh/poll/notification paths.

## Testing Strategy

### Frontend unit tests

- Add tests around auth-route preparation so valid persisted auth chooses `/app` instead of `/`.
- Add tests around any extracted startup helper to verify anonymous users still land on `/`.
- Add tests for the single-chat unmount behavior so route leave no longer triggers abort as a side effect.
- Add tests for preview selection logic so assistant messages win over user messages.

### Frontend manual / smoke verification

- Logged-in Electron startup opens directly into `/app` with no landing-page flash.
- Logged-out Electron startup still shows landing page.
- Startup feels faster because shell appears without waiting on profile and list fetches.
- Send a message in single chat, leave the page immediately, wait, return, and verify the role reply exists.
- Repeat the same behavior in quick chat if applicable.
- Verify role/character cards show the newest assistant reply text.

### Backend verification

- Confirm the deployed backend includes `51ac1bb fix(chat): persist stream replies after client disconnect` or an equivalent later commit.
- If deployed code differs from repository code, redeploy before concluding the frontend fix is insufficient.

## Non-Goals

- No global streaming store.
- No redesign of notification transport.
- No broad route-table rewrite.
- No startup behavior that waits for full profile/network hydration before showing the shell.

## Risks And Mitigations

### Risk: recovered token is stale

- Mitigation: use it only for initial routing and local shell entry; existing `restoreSession()` / profile fetch remains the real server validation step.

### Risk: not aborting on unmount leaks streams

- Mitigation: tie stream lifetime to the specific send operation, keep existing completion/failure cleanup, and avoid continuing UI writes after unmount.

### Risk: preview logic changes card sort unintentionally

- Mitigation: isolate preview-text selection from list ordering unless ordering is explicitly wrong.

## Recommendation

Implement this as a focused frontend change set first, then verify the backend deployment state if the chat continuity issue still reproduces.
