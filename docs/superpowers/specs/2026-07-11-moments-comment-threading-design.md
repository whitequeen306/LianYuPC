# Moments Comment Threading Design

## Goal

Make the Moments page comment area show reply relationships clearly when a post author replies to different commenters, so each author reply stays attached to the specific parent comment it answers.

## Current Problem

`frontend/src/pages/MomentsPage.vue` renders all comments for a post as one flat list ordered by time. The backend already returns `parentId` and `rootId`, but the UI only uses `parentId` to add a CSS class. When the post author replies to multiple different characters, those replies appear as consecutive lines in the same flat stream, which makes it unclear who each reply targets.

## Design

### Data handling

Keep the backend contract unchanged. Build a frontend-only threading step from the existing comment payload:

- Top-level comments are comments without `parentId`, plus any orphaned replies whose parent is missing from the current payload.
- Child replies are attached to their direct parent comment by `parentId`.
- Ordering stays chronological within the original backend order so the UI does not invent a new timeline.

### Presentation

For expanded comments on each moment post:

- Render top-level comments as the main comment list.
- Render direct replies inside the parent comment item instead of flattening them into the same level.
- Show reply author text as `A 回复 B` when a reply has a known direct parent author.
- Preserve the existing reply button and reply target composer behavior.

This solves the ambiguity without changing comment generation, notification, or orchestration behavior.

### Scope constraints

- No backend schema or API changes.
- No changes to comment creation semantics.
- No changes to updater or release flow except bumping desktop version for local packaging.

## Files

- Add `frontend/src/pages/momentsCommentThread.js` for comment threading and author-label helpers.
- Add `frontend/src/pages/__tests__/momentsCommentThread.test.js` for regression coverage.
- Modify `frontend/src/pages/MomentsPage.vue` to render threaded comments and use the helper.
- Modify locale files if a dedicated reply label string is needed.

## Error handling

- If a parent comment is missing, keep the reply visible as a top-level fallback instead of dropping it.
- If author names are missing, fall back to the existing localized `you` label or the response name already provided by the API.

## Testing

- Unit test the threading helper with mixed top-level comments and author replies to different characters.
- Unit test orphan reply fallback behavior.
- Run targeted Vitest for the new helper, then the full frontend test suite.
- Use Playwright screenshots against a local dev server with mocked Moments responses to verify the visual hierarchy and reply labels.

## Self-review

- No placeholder sections remain.
- Scope stays within the existing frontend contract.
- The design uses current backend fields instead of adding unnecessary APIs.
