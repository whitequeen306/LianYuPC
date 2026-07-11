# Moments Comment Threading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Moments comments display as parent-plus-replies so a post author's reply is clearly attached to the specific commenter it answers.

**Architecture:** Add a small frontend helper that converts the flat API response into a threaded structure while preserving chronological order. Update `MomentsPage.vue` to render top-level comments with nested replies and author labels derived from direct parent relationships.

**Tech Stack:** Vue 3, Vitest, Vite, Element Plus, Electron

---

### Task 1: Add failing regression tests for comment threading

**Files:**
- Create: `frontend/src/pages/__tests__/momentsCommentThread.test.js`
- Create: `frontend/src/pages/momentsCommentThread.js`

- [ ] **Step 1: Write the failing test**

```javascript
import { describe, expect, it } from 'vitest'
import { buildMomentCommentThread, formatMomentCommentAuthorLabel } from '../momentsCommentThread'

describe('momentsCommentThread', () => {
  it('groups replies under the correct parent comment and labels reply targets', () => {
    const comments = [
      { id: 1, parentId: null, characterName: '小雪', content: '顶层 A' },
      { id: 2, parentId: null, characterName: '小雨', content: '顶层 B' },
      { id: 3, parentId: 1, characterName: '阿宁', content: '回复 A' },
      { id: 4, parentId: 2, characterName: '阿宁', content: '回复 B' }
    ]

    const thread = buildMomentCommentThread(comments)

    expect(thread).toHaveLength(2)
    expect(thread[0].replies.map(item => item.id)).toEqual([3])
    expect(thread[1].replies.map(item => item.id)).toEqual([4])
    expect(formatMomentCommentAuthorLabel(thread[0].replies[0], '你')).toBe('阿宁 回复 小雪')
    expect(formatMomentCommentAuthorLabel(thread[1].replies[0], '你')).toBe('阿宁 回复 小雨')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test -- --run src/pages/__tests__/momentsCommentThread.test.js`
Expected: FAIL because `../momentsCommentThread` does not exist yet or required exports are missing.

- [ ] **Step 3: Write minimal implementation**

```javascript
export function buildMomentCommentThread(comments = []) {
  return comments
}

export function formatMomentCommentAuthorLabel(comment, fallbackYou) {
  return comment?.characterName || fallbackYou
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test -- --run src/pages/__tests__/momentsCommentThread.test.js`
Expected: PASS

### Task 2: Apply threaded rendering in `MomentsPage.vue`

**Files:**
- Modify: `frontend/src/pages/MomentsPage.vue`
- Modify: `frontend/src/i18n/locales/zh.js`
- Modify: `frontend/src/i18n/locales/en.js`
- Modify: `frontend/src/i18n/locales/ja.js`
- Modify: `frontend/src/i18n/locales/zh-TW.js`

- [ ] **Step 1: Write the failing test**

```javascript
import { describe, expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const currentDir = dirname(fileURLToPath(import.meta.url))
const momentsPagePath = resolve(currentDir, '../MomentsPage.vue')

describe('MomentsPage comment threading', () => {
  test('renders nested replies with dedicated reply author label', () => {
    const source = readFileSync(momentsPagePath, 'utf8')
    expect(source).toContain('feed-comment-reply-list')
    expect(source).toContain('formatCommentAuthorLabel')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test -- --run src/pages/__tests__/momentsCommentThread.test.js`
Expected: FAIL because `MomentsPage.vue` does not yet render nested replies or use the label helper.

- [ ] **Step 3: Write minimal implementation**

```javascript
function threadedComments(postId) {
  return buildMomentCommentThread(getComments(postId))
}

function formatCommentAuthorLabel(comment) {
  return formatMomentCommentAuthorLabel(comment, t('moments.you'))
}
```

```vue
<ul v-else-if="threadedComments(item.post.id).length" class="feed-comment-list">
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test -- --run src/pages/__tests__/momentsCommentThread.test.js`
Expected: PASS

### Task 3: Verify full frontend behavior and package `0.2.278`

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

- [ ] **Step 1: Bump the version for local packaging**

```json
{
  "version": "0.2.278"
}
```

- [ ] **Step 2: Run targeted and full verification**

Run: `npm run test -- --run src/pages/__tests__/momentsCommentThread.test.js`
Expected: PASS

Run: `npm run test -- --run`
Expected: PASS

Run: `osv-scanner scan --lockfile "package-lock.json"`
Expected: No actionable vulnerabilities blocking packaging.

- [ ] **Step 3: Run visual verification with Playwright**

Run: `python "C:\Users\hp\.config\opencode\skills\webapp-testing\scripts\with_server.py" --help`
Expected: Usage output

Then run a Playwright script against the local frontend server with mocked Moments API responses and save screenshots proving nested reply placement.

- [ ] **Step 4: Build the Electron package**

Run: `npm run electron:build`
Expected: Build succeeds and emits `frontend/release/v0.2.278/LianYu Setup 0.2.278.exe`

## Self-review

- The plan covers threading logic, page integration, localized reply labels, verification, and packaging.
- No task requires backend changes.
- The verification step includes tests, vulnerability scan, Playwright screenshots, and desktop packaging.
