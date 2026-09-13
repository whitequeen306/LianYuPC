# Kurumi Desktop Pet Design

## Goal

Add a new desktop pet for Tokisaki Kurumi to the existing LianYu desktop-pet system.

This work covers four deliverables:

1. A Codex v1 pet atlas and preview asset for Kurumi.
2. Integration into the existing pet catalog.
3. Integration into the Electron desktop pet allowlist.
4. Screenshot-driven VC voice support using the user-provided recording.

## Existing System Constraints

- Atlas format is fixed at `1536x1872`.
- Grid is fixed at `8 columns x 9 rows`.
- Cell size is fixed at `192x208`.
- Animation protocol is already defined by the existing desktop pet runtime and will not change.
- Pet integration happens through `frontend/src/constants/petCatalog.js`.
- Electron desktop pet switching is gated by `frontend/electron/desktopSettings.js`.
- Screenshot-driven pet speech already uses `petId` to resolve a voice mapping in `backend/lianyu-service/src/main/resources/pet-voices.json`.

## Character Direction

### Identity

The pet represents Tokisaki Kurumi from Date A Live in a recognizable Q-version style.

Required recognition cues:

- Black-and-crimson twin-tail hairstyle
- Heterochromia motif with the clock-eye association preserved where readable
- Gothic red-black dress silhouette
- Slightly dangerous but playful smile
- Delicate lace or ribbon cues only where they remain readable at pet scale

### Personality in Motion

Kurumi should read as elegant, mischievous, and lightly theatrical rather than cute-only or generic moe.

Behavioral intent by action:

- `idle`: poised, gentle sway with confident stillness
- `running-right` / `running-left`: light but sharp movement, dress and hair trailing slightly
- `waving`: teasing or courtly greeting rather than overexcited waving
- `jumping`: quick, nimble, slightly dramatic hop
- `failed`: brief flustered or annoyed recovery, not slapstick collapse
- `waiting`: patient, observant stance as if watching the user
- `running`: compact forward loop consistent with the directional run
- `review`: focused, curious, slightly amused check-in pose

### Pixel Style

- Use the existing Codex digital pet house style.
- Keep silhouettes readable before preserving tiny costume detail.
- Avoid detached effects, shadows, clock halos, muzzle flashes, floating bullets, or decorative particles.
- Prefer expression, pose, hair shape, and dress contrast over clutter.

## Asset Contract

### Required Files

- `frontend/public/pet/kurumi_spritesheet.webp`
- `frontend/public/pet/kurumi_idle0.png`

### Atlas Layout

Rows must match the existing shared protocol:

0. `idle` - 6 frames
1. `running-right` - 8 frames
2. `running-left` - 8 frames
3. `waving` - 4 frames
4. `jumping` - 5 frames
5. `failed` - 8 frames
6. `waiting` - 6 frames
7. `running` - 6 frames
8. `review` - 6 frames

Unused cells must remain compatible with the existing atlas reader.

## Voice Integration

### Scope

Only the existing screenshot-driven greeting voice path is in scope.

Out of scope:

- click voice
- drag voice
- jump voice
- cached action audio
- any new TTS provider logic

### Voice Source

Use the user-provided recording at `E:\xwechat_files\wxid_a5pu8abw6t5j22_06d7\msg\file\2026-07\20260716_135357_剪辑1.m4a`.

The recording must be converted into a mono WAV sample suitable for VC enrollment.

### Runtime Decision

Use the existing `qwen3-tts-vc-2026-01-22` model family and enroll a new Kurumi-specific voice ID.

Resulting registry behavior:

- model remains `qwen3-tts-vc-2026-01-22`
- add `kurumi` mapping to `pet-voices.json`
- extend `PetVoiceRegistryTest` to assert Kurumi is present

## Catalog Integration

Add a new `kurumi` entry to `frontend/src/constants/petCatalog.js`.

The entry must include:

- `id: 'kurumi'`
- `nameZh: '时崎狂三'`
- `nameEn: 'Tokisaki Kurumi'`
- `nameJa: '時崎狂三'`
- `series: '约会大作战'`
- `sprite: 'pet/kurumi_spritesheet.webp'`
- `preview: 'pet/kurumi_idle0.png'`
- persona text suitable for screenshot-driven proactive greeting
- `voiceSource: 'vc'`

The persona should preserve Kurumi's tone:

- refined and self-possessed
- flirtatious in a restrained way
- observant and slightly dangerous without becoming hostile
- capable of giving short, intimate, in-character reactions to the user's current activity

## Workflow Decision

The image generation workflow must pause after `decoded/base.png` is produced.

That base image is the only explicit user approval gate in this task. All later row generation depends on the base being accepted. Spec and plan review are handled inline by the agent and do not require a separate user signoff.

## Implementation Approach

### Phase 1: TDD for integration points

- Extend the existing backend voice registry test to cover Kurumi.
- Use targeted verification for catalog and allowlist additions.

### Phase 2: Prepare voice and pet run inputs

- Convert the provided recording to the required mono WAV format.
- Prepare a hatch-pet run directory using `kurumi-reference.png`.
- Confirm the prompts and manifest look correct before generation.

### Phase 3: Generate and review base art

- Generate only the `base` job first using the project-specific fallback script.
- Stop and show the user the generated `decoded/base.png` for approval.

### Phase 4: Complete the rest after approval

- Generate remaining motion rows.
- Finalize atlas output.
- Copy the final assets into `frontend/public/pet/`.
- Add catalog, allowlist, and voice registry entries.
- Verify dimensions, mappings, and targeted tests.

## Risks and Mitigation

- The current chat model cannot visually inspect the source image itself, so approval must rely on the user for `base.png` quality confirmation.
- VC enrollment depends on a live external API key and service availability.
- Narrow backend tests may still be affected by unrelated module compile issues.

Mitigation:

- Keep all code edits narrowly scoped to Kurumi integration files.
- Use live file-level verification plus targeted test execution.
- Stop immediately at the base checkpoint instead of wasting row-generation work.

## Testing Strategy

### Before user base approval

- Verify voice registry test fails before the Kurumi mapping exists.
- Verify no existing `kurumi` catalog or allowlist entry exists.
- Verify the mono WAV conversion succeeds.
- Verify the pet run directory and base prompt are created.
- Verify `decoded/base.png` exists after generation.

### After user base approval

- Verify final atlas dimensions are exactly `1536x1872`.
- Verify `kurumi_idle0.png` exists and is readable.
- Verify `petCatalog.js` contains the `kurumi` entry.
- Verify `desktopSettings.js` allowlist contains `kurumi`.
- Verify `pet-voices.json` and `PetVoiceRegistryTest` contain the Kurumi mapping.
- Verify one narrow backend test run passes unless blocked by unrelated compile issues.

## Out of Scope

- New animation protocol versions
- New desktop pet UI redesign
- Voice caching
- Action-triggered voiced emotes
- Refactors to unrelated pets
