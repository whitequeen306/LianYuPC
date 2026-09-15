# Elysia Desktop Pet Design

## Goal

Add a new desktop pet for Elysia to the existing YuNian desktop-pet system.

This work covers two deliverables:

1. A Codex v1 pet atlas and preview asset for Elysia.
2. Integration into the existing pet catalog and screenshot-driven TTS pipeline.

This phase does not add action-triggered voice clips, persistent voice caching, or changes to the existing observe workflow beyond enabling Elysia to use it.

## Existing System Constraints

- Atlas format is fixed at `1536x1872`.
- Grid is fixed at `8 columns x 9 rows`.
- Cell size is fixed at `192x208`.
- Animation protocol is already defined in `frontend/src/constants/petSprite.js` and will not change.
- Pet integration happens through `frontend/src/constants/petCatalog.js`.
- Screenshot-driven pet speech already exists and uses `petId` to resolve a voice mapping in `backend/lianyu-service/src/main/resources/pet-voices.json`.

## Visual Direction

### Character Identity

The pet represents Elysia from Honkai Impact 3rd in a recognizable Q-version style.

Required recognition cues:

- Long pink hair
- Pink-white palette
- Elegant dress silhouette
- Floral head ornament details
- Butterfly or crystal-like decorative cues
- Bright, soft, confident smile

### Proportion

- Use a large-head chibi ratio around `1.2:1`
- Face and hair are the primary recognition features
- Body remains complete enough to preserve dress movement in run and jump actions

### Pixel Style

- Use a refined pixel-art direction rather than a low-detail retro style
- Preserve layered pink-white shading where possible
- Keep silhouette and facial read clear at desktop scale
- Prefer readability over excessive accessory detail

### Motion Personality

Animation tone should be sweet and elegant.

Behavioral intent by action:

- `idle`: gentle sway, soft breathing feel
- `run-right` / `run-left`: light, graceful movement with hair and skirt follow-through
- `wave`: warm greeting with smile
- `jump`: airy upward motion, light celebratory feeling
- `failed`: cute, restrained stumble or embarrassed recovery; not slapstick
- `waiting`: poised anticipation
- `running`: lighter short-run loop than full directional run
- `review`: attentive, graceful check-in feeling

## Asset Contract

### Required Files

- `frontend/public/pet/elysia_spritesheet.webp`
- `frontend/public/pet/elysia_idle0.png`

### Atlas Layout

Rows must match the existing shared protocol:

0. `idle` - 6 frames
1. `run-right` - 8 frames
2. `run-left` - 8 frames
3. `wave` - 4 frames
4. `jump` - 5 frames
5. `failed` - 8 frames
6. `waiting` - 6 frames
7. `running` - 6 frames
8. `review` - 6 frames

Unused cells in shorter rows may remain visually duplicated or blank only if the atlas consumer already tolerates it. Preferred behavior is to keep row alignment compatible with existing pets.

## Catalog Integration

Add a new `elysia` entry to `frontend/src/constants/petCatalog.js`.

The entry must include:

- `id: 'elysia'`
- localized display names
- series metadata
- sprite path
- preview path
- persona text suitable for screenshot-driven proactive greeting
- `voiceSource: 'vc'`

The persona should preserve Elysia's tone:

- warm and proactive
- elegant and bright
- affectionate without becoming overbearing
- observant and emotionally attentive
- able to comment naturally on the user's current activity in real life

## Voice Integration

### Scope

Only the existing screenshot-driven greeting voice path is in scope.

Out of scope:

- click voice
- drag voice
- jump voice
- cached action audio
- ambient chatter loops

### Current Runtime Path

1. Desktop observer captures the screen on schedule.
2. Backend vision model analyzes the image.
3. Backend text model generates a short proactive greeting in-character.
4. DashScope pet TTS synthesizes that greeting.
5. Electron forwards the generated audio to the launcher window for playback.

### Elysia Voice Decision

Use `qwen3-tts-vc-2026-01-22` with a newly enrolled custom voice ID for `elysia`.

The voice source is the user's own authorized recording, processed into a Qwen-compatible mono WAV sample.

Resulting registry mapping:

- model remains `qwen3-tts-vc-2026-01-22`
- add `elysia` voice ID to `pet-voices.json`

## Implementation Approach

### Phase 1: Voice Readiness

- Process the user-provided recording into a valid VC input asset
- Enroll a DashScope VC voice for `elysia`
- Verify synthesis returns an audio URL and downloadable audio
- Register the `elysia` voice ID in the existing voice registry

### Phase 2: Pet Asset Integration

- Inspect existing pet asset conventions
- Produce the best feasible Elysia atlas under the fixed contract
- Export `elysia_idle0.png` from the first idle frame or equivalent preview frame
- Add the catalog entry

### Phase 3: Verification

- Verify atlas dimensions
- Verify preview file exists
- Verify catalog entry resolves correctly
- Verify Elysia voice registry entry is present
- Verify one real TTS synthesis succeeds using the registered voice ID

## Error Handling and Risks

### Technical Risks

- The current environment may lack a direct local image-generation path for high-quality frame creation.
- The current model runtime cannot reliably inspect image attachments passed through the chat channel.
- Existing backend compilation issues may prevent narrow JUnit execution even when the changed files are correct.

### Mitigation

- Keep visual asset work isolated to Elysia files.
- Use direct file-based validation for asset dimensions and registry contents.
- Avoid unrelated backend refactors.
- Report any limitation honestly if atlas generation quality cannot meet the intended bar with available tooling.

## Testing Strategy

### Voice

- Confirm the enrolled Elysia voice can synthesize a short Chinese greeting through the same model family used by the runtime.
- Confirm the returned audio URL is present and downloadable.

### Resource Integration

- Verify `pet-voices.json` contains the `elysia` mapping.
- Verify `petCatalog.js` contains the `elysia` entry.
- Verify the atlas dimensions are exactly `1536x1872`.
- Verify the preview image exists and is readable.

### Note on Automated Tests

If targeted Maven tests are blocked by unrelated compile failures elsewhere in the repo, treat file-level validation and live API synthesis as the source of truth for this task and report the compile blocker explicitly.

## Out of Scope

- New animation protocol or atlas version upgrade
- 11-row directional look support
- New UI picker redesign
- New TTS provider abstraction
- Voice caching layer
- Non-Elysia pet refactors
