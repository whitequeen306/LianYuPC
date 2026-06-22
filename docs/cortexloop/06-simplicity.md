# Simplicity Findings

**Count:** 10

## CL-062
- **Severity:** High
- **Location:** ConversationService.java:913
- **Problem:** Duplicate natural-style rules via PromptRuleEngine and buildNaturalStyleBlock
- **Evidence:** Overlapping inner-thought rules appended twice
- **Confidence:** high
- **Recommendation:** Single source of truth in PromptRuleEngine
- **Auto-fixable:** no

## CL-063
- **Severity:** High
- **Location:** OutputLanguageService.java:155
- **Problem:** Dead buildOutputLanguageBlock() duplicates OutputLanguageRuleHook
- **Evidence:** Method never called repo-wide
- **Confidence:** high
- **Recommendation:** Remove unused method
- **Auto-fixable:** yes

## CL-064
- **Severity:** High
- **Location:** CharacterSquareCatalog*.java
- **Problem:** Identical pack()/tags() copied in six catalog files
- **Evidence:** Same 8-line helpers per franchise file
- **Confidence:** high
- **Recommendation:** Shared helper on CharacterSquareCatalog
- **Auto-fixable:** yes

## CL-065
- **Severity:** High
- **Location:** CharacterSquareCatalog.java:151
- **Problem:** slugForSortOrder switch duplicates BY_SLUG keys
- **Evidence:** 50+ case switch mirrors map.put slugs
- **Confidence:** high
- **Recommendation:** Derive sort order from registration metadata
- **Auto-fixable:** yes

## CL-066
- **Severity:** Medium
- **Location:** GlobalExceptionHandler.java:22
- **Problem:** Six handlers repeat SSE skip guard boilerplate
- **Evidence:** shouldSkipJsonErrorBody duplicated per handler
- **Confidence:** high
- **Recommendation:** Extract shared wrapper helper
- **Auto-fixable:** yes

## CL-067
- **Severity:** Medium
- **Location:** frontend/src/api/index.js:34
- **Problem:** 401 handling duplicated in success and error interceptors
- **Evidence:** Identical clearToken/redirect block twice
- **Confidence:** high
- **Recommendation:** Extract handleUnauthorizedSession()
- **Auto-fixable:** yes

## CL-068
- **Severity:** Medium
- **Location:** ChatPage.vue / GroupChatPage.vue
- **Problem:** parseMessageTime/formatTimeDivider duplicated
- **Evidence:** Byte-for-byte identical helpers
- **Confidence:** high
- **Recommendation:** Shared chatTimeline util
- **Auto-fixable:** yes

## CL-069
- **Severity:** Medium
- **Location:** MemoryController.java et al.
- **Problem:** Character display-name fallback scattered inconsistently
- **Evidence:** 8+ repeated ternaries with different defaults
- **Confidence:** high
- **Recommendation:** Centralize CharacterDisplayNames helper
- **Auto-fixable:** yes

## CL-070
- **Severity:** Medium
- **Location:** CharacterSquareCatalog.java:44
- **Problem:** Mixed inline vs franchise-file catalog registration
- **Evidence:** First 7 slugs inline; rest delegated to *Catalog*.java
- **Confidence:** high
- **Recommendation:** Unify registration pattern
- **Auto-fixable:** yes

## CL-071
- **Severity:** Low
- **Location:** ChatPage.vue / GroupChatPage.vue
- **Problem:** Partial duplication of message timeline assembly
- **Evidence:** Shared TIME_GAP_MS divider logic; ChatPage adds group flags
- **Confidence:** medium
- **Recommendation:** Extract shared timeline builder with options
- **Auto-fixable:** yes

