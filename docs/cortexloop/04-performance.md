# Performance Findings

**Count:** 12

## CL-038
- **Severity:** High
- **Location:** backend/lianyu-web/src/main/java/com/lianyu/web/controller/MemoryController.java:62
- **Problem:** Memory list N+1 character lookups
- **Evidence:** Up to 200 metas each selectById in stream
- **Confidence:** high
- **Recommendation:** Batch-load character names by ID set
- **Auto-fixable:** yes

## CL-039
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:144
- **Problem:** Conversation list loads all conversations without pagination
- **Evidence:** Unbounded select for sidebar list
- **Confidence:** high
- **Recommendation:** Paginate or cap list
- **Auto-fixable:** needs-confirmation

## CL-040
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:260
- **Problem:** SSE path does heavy sync work before first token
- **Evidence:** DB writes, 32-msg history, memory/relationship/session assembly pre-stream
- **Confidence:** high
- **Recommendation:** Defer non-critical writes; cache warm context
- **Auto-fixable:** no

## CL-041
- **Severity:** High
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:373
- **Problem:** Group reply fan-out saturates AI bulkhead (16 concurrent)
- **Evidence:** Up to 4 parallel chatBlocking per round x 2 rounds
- **Confidence:** high
- **Recommendation:** Cap per-user group AI concurrency
- **Auto-fixable:** needs-confirmation

## CL-042
- **Severity:** High
- **Location:** frontend/src/pages/MomentsPage.vue:532
- **Problem:** Moments loads comments sequentially per post then polls all every 6s
- **Evidence:** 20 posts = 200 comment req/min steady state
- **Confidence:** high
- **Recommendation:** Batch comments endpoint; poll only expanded posts
- **Auto-fixable:** no

## CL-043
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:579
- **Problem:** Thread.sleep(bubbleGapMs) blocks group-chat pool threads
- **Evidence:** 500ms sleep per bubble during broadcast
- **Confidence:** high
- **Recommendation:** Use ScheduledExecutorService for non-blocking delays
- **Auto-fixable:** yes

## CL-044
- **Severity:** Medium
- **Location:** frontend/src/pages/ChatPage.vue:787
- **Problem:** Chat polls getMessages every 10s re-fetching 50 rows
- **Evidence:** 6 req/min/conv on idle chat
- **Confidence:** high
- **Recommendation:** Poll with sinceSeq/ETag; backoff when idle
- **Auto-fixable:** needs-confirmation

## CL-045
- **Severity:** Medium
- **Location:** frontend/src/pages/ChatPage.vue:70
- **Problem:** Message list not virtualized; DOM grows unbounded
- **Evidence:** v-for full timeline; loadOlder prepends 50 per scroll
- **Confidence:** high
- **Recommendation:** Virtual scroller; cap in-memory window
- **Auto-fixable:** needs-confirmation

## CL-046
- **Severity:** Medium
- **Location:** frontend/src/pages/ChatPage.vue:383
- **Problem:** messageTimeline recomputes O(n) on every stream token
- **Evidence:** stripInnerThoughts per message; GSAP bounce during stream
- **Confidence:** medium
- **Recommendation:** Incremental append; debounce stream watcher
- **Auto-fixable:** yes

## CL-047
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterStateService.java:203
- **Problem:** Emotion decay scans entire character_state table every 15min
- **Evidence:** selectList(null) then per-row updateById
- **Confidence:** high
- **Recommendation:** Query only non-default emotions past interval
- **Auto-fixable:** yes

## CL-048
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryRetriever.java:234
- **Problem:** Profile fact metas query unbounded on cache miss
- **Evidence:** likeRight prefix selectList with no LIMIT
- **Confidence:** high
- **Recommendation:** Add LIMIT or slot-index table
- **Auto-fixable:** yes

## CL-049
- **Severity:** Medium
- **Location:** backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java:156
- **Problem:** SSE streams use ForkJoinPool.commonPool(); Electron bundle monolithic
- **Evidence:** runAsync blockLast competes with group tasks; inlineDynamicImports in vite
- **Confidence:** medium
- **Recommendation:** Dedicated executor for AI/SSE; restore route chunks if feasible
- **Auto-fixable:** needs-confirmation

