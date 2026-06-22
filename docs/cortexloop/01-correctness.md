# Correctness Findings

**Count:** 13

## CL-010
- **Severity:** Critical
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java:1048
- **Problem:** SSE stream errors with partial content still persist assistant messages
- **Evidence:** finishSseError calls onComplete with partial content and null error when tokens emitted before failure
- **Confidence:** high
- **Recommendation:** Never save partial replies on stream error paths
- **Auto-fixable:** yes

## CL-011
- **Severity:** High
- **Location:** frontend/src/pages/ChatPage.vue:910
- **Problem:** Client/server diverge after failed SSE with partial tokens
- **Evidence:** Frontend removes optimistic UI on error; backend may persist partial reply (CL-010)
- **Confidence:** high
- **Recommendation:** Align persistence with client error handling or expose failed-turn flag
- **Auto-fixable:** partial

## CL-012
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:656
- **Problem:** Turn interrupt not re-checked between multi-bubble inserts
- **Evidence:** bubbleGapMs sleep loop persists stale bubbles after new turnId set
- **Confidence:** high
- **Recommendation:** Re-check turnId before each insert/broadcast
- **Auto-fixable:** yes

## CL-013
- **Severity:** High
- **Location:** frontend/src/pages/GroupChatPage.vue:499
- **Problem:** Group WebSocket USER_MESSAGE events not handled on client
- **Evidence:** Server broadcasts USER_MESSAGE; handleGroupEvent ignores type
- **Confidence:** high
- **Recommendation:** Handle USER_MESSAGE with dedupe against optimistic entries
- **Auto-fixable:** yes

## CL-014
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:178
- **Problem:** Memory dedup hash uses summary text not source_msg_ids per schema design
- **Evidence:** computeMemoryHash hashes summary text; migration specifies SHA-256(sorted(source_msg_ids))
- **Confidence:** high
- **Recommendation:** Hash sorted source message IDs for idempotent upsert
- **Auto-fixable:** no

## CL-015
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:40
- **Problem:** Concurrent memory summary tasks can create near-duplicate memories
- **Evidence:** Every turn enqueues memory.summary with 2-8 consumers; overlapping tasks with paraphrased summaries
- **Confidence:** high
- **Recommendation:** Per-conversation lock/debounce; dedupe on source message set
- **Auto-fixable:** partial

## CL-016
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:140
- **Problem:** loadOrCreateState check-then-insert race without duplicate-key recovery
- **Evidence:** Concurrent recordUserTurn can double-insert on uk_relationship_user_character
- **Confidence:** high
- **Recommendation:** Mirror CharacterStateService duplicate-key catch pattern
- **Auto-fixable:** yes

## CL-017
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:106
- **Problem:** Duplicate SINGLE conversations possible under concurrent create
- **Evidence:** No unique index on (user_id, character_id, mode); parallel POST can both insert
- **Confidence:** high
- **Recommendation:** Add unique index or advisory lock; return existing on conflict
- **Auto-fixable:** partial

## CL-018
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:384
- **Problem:** Cold-open guard non-atomic; duplicate opening messages possible
- **Evidence:** findLastMessage==null check then generate without lock
- **Confidence:** medium
- **Recommendation:** Redis SETNX lock per conversation for cold open
- **Auto-fixable:** partial

## CL-019
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:395
- **Problem:** Group replies persist in completion order not roster order
- **Evidence:** Parallel CompletableFuture per member; seq follows LLM completion speed
- **Confidence:** high
- **Recommendation:** Serialize turns per round or document completion-order semantics
- **Auto-fixable:** partial

## CL-020
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:1251
- **Problem:** Redis seq fallback can assign colliding sequence numbers
- **Evidence:** reserveSeqBlock returns safeCount when increment null; multiple inserts may get seq 1
- **Confidence:** medium
- **Recommendation:** Fail request if seq allocation fails; bootstrap from DB max(seq)+1
- **Auto-fixable:** yes

## CL-021
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/SessionSummaryService.java:54
- **Problem:** Concurrent session-summary merges can lose work (last-write-wins)
- **Evidence:** @Async maybeMergeAsync with no per-conversation lock
- **Confidence:** medium
- **Recommendation:** Per-conversation Redis lock or CAS on summary version
- **Auto-fixable:** partial

## CL-022
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:116
- **Problem:** Concurrent memory insert on identical hash can fail MQ consumer
- **Evidence:** No DuplicateKeyException handler unlike CharacterStateService
- **Confidence:** medium
- **Recommendation:** Catch duplicate key, re-read, treat as SKIPPED
- **Auto-fixable:** yes

