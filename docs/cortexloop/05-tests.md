# Tests Findings

**Count:** 12

## CL-050
- **Severity:** Critical
- **Location:** backend/lianyu-service/.../auth/impl/AuthServiceImpl.java
- **Problem:** Auth register/login/idempotent flows untested
- **Evidence:** No AuthServiceTest or AuthControllerTest
- **Confidence:** high
- **Recommendation:** Add unit tests for register/login branches
- **Auto-fixable:** yes

## CL-051
- **Severity:** Critical
- **Location:** backend/lianyu-service/.../auth/CaptchaService.java
- **Problem:** Captcha verify/burn-after-read untested
- **Evidence:** No CaptchaServiceTest
- **Confidence:** high
- **Recommendation:** Test valid/replay/expired/malformed captcha
- **Auto-fixable:** yes

## CL-052
- **Severity:** Critical
- **Location:** frontend/
- **Problem:** Zero frontend tests for auth/SSE client
- **Evidence:** vitest --passWithNoTests; 0 spec files
- **Confidence:** high
- **Recommendation:** Add Vitest for secureToken and SSE parser
- **Auto-fixable:** yes

## CL-053
- **Severity:** Critical
- **Location:** ConversationService.sendMessageStream()
- **Problem:** SSE send path and post-stream persistence untested
- **Evidence:** No sendMessageStream/chatStream tests
- **Confidence:** high
- **Recommendation:** Mock stream callback success/failure paths
- **Auto-fixable:** yes

## CL-054
- **Severity:** Critical
- **Location:** GroupChatService.java
- **Problem:** Group interrupt/mention routing untested
- **Evidence:** No GroupChatServiceTest
- **Confidence:** high
- **Recommendation:** Test turn interrupt and mention filter
- **Auto-fixable:** yes

## CL-055
- **Severity:** Critical
- **Location:** MemoryController.java
- **Problem:** Memory detail ownership filter untested
- **Evidence:** filterMessagesOwnedByUser has no tests
- **Confidence:** high
- **Recommendation:** Test cross-user source message filtering
- **Auto-fixable:** yes

## CL-056
- **Severity:** High
- **Location:** AuthRateLimiter.java
- **Problem:** Rate limiting untested
- **Evidence:** No AuthRateLimiterTest
- **Confidence:** high
- **Recommendation:** Mock Redis; assert 429 at threshold
- **Auto-fixable:** yes

## CL-057
- **Severity:** High
- **Location:** MemoryWriter.java
- **Problem:** Memory upsert/dedup pipeline untested
- **Evidence:** MemoryWriterRelationshipTest does not cover processSummary
- **Confidence:** high
- **Recommendation:** Test CREATED/UPDATED/SKIPPED paths
- **Auto-fixable:** yes

## CL-058
- **Severity:** High
- **Location:** backend/
- **Problem:** No HTTP/API-layer tests for critical endpoints
- **Evidence:** Zero @WebMvcTest/@SpringBootTest
- **Confidence:** high
- **Recommendation:** Add @WebMvcTest slices for auth/conversation/memory
- **Auto-fixable:** partial

## CL-059
- **Severity:** High
- **Location:** GlobalExceptionHandler.java
- **Problem:** SSE error contract untested
- **Evidence:** shouldSkipJsonErrorBody has no tests
- **Confidence:** medium
- **Recommendation:** Unit-test SSE skip matrix
- **Auto-fixable:** yes

## CL-060
- **Severity:** Medium
- **Location:** RelationshipStateService.java
- **Problem:** Assistant relationship heuristics untested end-to-end
- **Evidence:** recordAssistantTurn not directly verified
- **Confidence:** high
- **Recommendation:** Test ASSISTANT_VULNERABLE_REPLY detection
- **Auto-fixable:** yes

## CL-061
- **Severity:** Medium
- **Location:** SessionSummaryService.java
- **Problem:** formatForPrompt with Redis state untested
- **Evidence:** maybeMergeAsync untested
- **Confidence:** medium
- **Recommendation:** Test non-empty Redis summary formatting
- **Auto-fixable:** yes

