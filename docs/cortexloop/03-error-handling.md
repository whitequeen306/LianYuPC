# Error-handling Findings

**Count:** 15

## CL-023
- **Severity:** Critical
- **Location:** frontend/src/pages/ChatPage.vue:827
- **Problem:** Background message polling swallows all errors silently
- **Evidence:** pollCurrentConversationMessages catch { // ignore polling errors } with silent API
- **Confidence:** high
- **Recommendation:** Log and show degraded-mode indicator after N failures
- **Auto-fixable:** no

## CL-024
- **Severity:** Critical
- **Location:** frontend/src/stores/notifications.js:106
- **Problem:** Notification store updates use silent API in empty catch blocks
- **Evidence:** refreshUnreadCount/markAllRead etc. catch {} with no logging
- **Confidence:** high
- **Recommendation:** Log failures; expose lastSyncError in store
- **Auto-fixable:** no

## CL-025
- **Severity:** High
- **Location:** frontend/src/stores/notifications.js:43
- **Problem:** WebSocket JSON parse failures silently dropped
- **Evidence:** JSON.parse in catch {} for group and notification topics
- **Confidence:** high
- **Recommendation:** Log parse errors with topic and truncated body
- **Auto-fixable:** yes

## CL-026
- **Severity:** High
- **Location:** frontend/src/composables/useConversationUnread.js:49
- **Problem:** Unread badge refresh fails silently
- **Evidence:** listNotifications silent:true in catch { // ignore }
- **Confidence:** high
- **Recommendation:** Log failure; keep last-known-good counts
- **Auto-fixable:** no

## CL-027
- **Severity:** High
- **Location:** frontend/electron/desktopObserver.js:157
- **Problem:** Desktop pet observe pipeline fails with zero logging
- **Evidence:** catch (e) { // 静默失败 } with no console/log
- **Confidence:** high
- **Recommendation:** Log at warn level with error message
- **Auto-fixable:** yes

## CL-028
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:434
- **Problem:** All AI replies failing still sends TURN_COMPLETE success signal
- **Evidence:** repliedCount==0 breaks loop then unconditionally TURN_COMPLETE
- **Confidence:** high
- **Recommendation:** Send TURN_ERROR or include failure count when repliedCount==0
- **Auto-fixable:** no

## CL-029
- **Severity:** High
- **Location:** backend/lianyu-web/src/main/java/com/lianyu/web/controller/CharacterStateController.java:169
- **Problem:** Inner space build failures return default with no log
- **Evidence:** catch (RuntimeException ignored) return defaultSpace()
- **Confidence:** high
- **Recommendation:** Log warn; return unavailable flag instead of fake default
- **Auto-fixable:** yes

## CL-030
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:127
- **Problem:** Milvus insert failure leaves MySQL rows without vectors silently
- **Evidence:** No warn when vecId null; may overwrite milvusVecId with null
- **Confidence:** high
- **Recommendation:** Warn/error when vecId null; queue repair; never overwrite with null
- **Auto-fixable:** no

## CL-031
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java:128
- **Problem:** MinIO errors indistinguishable from missing object
- **Evidence:** objectExists catch returns false with no log; sync skips upload on outage
- **Confidence:** high
- **Recommendation:** Tri-state result; log non-404 failures
- **Auto-fixable:** no

## CL-032
- **Severity:** High
- **Location:** frontend/src/pages/GroupChatPage.vue:681
- **Problem:** Group chat partial load failure clears messages without notify
- **Evidence:** catch clears groupMessages to [] with no toast
- **Confidence:** high
- **Recommendation:** Show error toast; preserve existing messages
- **Auto-fixable:** no

## CL-033
- **Severity:** Medium
- **Location:** frontend/src/pages/ChatPage.vue:604
- **Problem:** Load older messages errors swallowed locally
- **Evidence:** bare catch { // ignore load errors }
- **Confidence:** high
- **Recommendation:** Log error; reset pagination flags
- **Auto-fixable:** yes

## CL-034
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryRetriever.java:138
- **Problem:** Memory retrieval failure returns empty list; chat proceeds unaware
- **Evidence:** catch returns List.of() after log.error
- **Confidence:** high
- **Recommendation:** Propagate degraded flag to chat layer
- **Auto-fixable:** no

## CL-035
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryLlmExtractor.java:104
- **Problem:** Memory extraction JSON parse failure logged at debug only
- **Evidence:** log.debug on parse failure; memories skipped in production
- **Confidence:** high
- **Recommendation:** Upgrade to log.warn with conversation IDs
- **Auto-fixable:** yes

## CL-036
- **Severity:** Medium
- **Location:** frontend/electron/authSessionStore.js:47
- **Problem:** Corrupt auth session deleted with no log
- **Evidence:** catch deletes file and returns null silently
- **Confidence:** high
- **Recommendation:** Log decrypt/parse failure without token values
- **Auto-fixable:** yes

## CL-037
- **Severity:** Medium
- **Location:** backend/lianyu-storage/src/main/java/com/lianyu/storage/milvus/MilvusConfig.java:55
- **Problem:** Milvus init failure logged but startup continues
- **Evidence:** catch log.error only; no health flag or fail-fast
- **Confidence:** high
- **Recommendation:** Fail readiness probe or explicit degraded mode
- **Auto-fixable:** no

