# Elysia Desktop Pet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Elysia as a new desktop pet with a Codex v1 atlas contract, catalog integration, and screenshot-driven VC voice support.

**Architecture:** Reuse the existing pet asset contract and screenshot-driven TTS pipeline without changing the animation protocol. Integrate Elysia by adding one pet registry entry, one voice registry mapping, and the required public asset files, while validating dimensions and live VC synthesis through the existing DashScope path.

**Tech Stack:** Vue 3, Electron, Spring Boot, JUnit 5, Maven, DashScope Qwen VC, ffmpeg, WebP public assets

---

### Task 1: Lock Voice Registry Support

**Files:**
- Modify: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/PetVoiceRegistryTest.java`
- Modify: `backend/lianyu-service/src/main/resources/pet-voices.json`

- [ ] **Step 1: Write the failing test**

Update `backend/lianyu-service/src/test/java/com/lianyu/service/ai/PetVoiceRegistryTest.java` so the existing test asserts Elysia is present:

```java
assertTrue(registry.hasVoice("elysia"));
assertEquals("qwen-tts-vc-elysia-voice-20260714121404767-1982", registry.resolveVoiceId("elysia"));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl lianyu-service -Dtest=PetVoiceRegistryTest test`

Expected:
- Ideally the test fails because `elysia` is missing from `pet-voices.json`
- If unrelated backend compile failures block the test class from running, record that blocker and continue with file-level validation

- [ ] **Step 3: Write minimal implementation**

Update `backend/lianyu-service/src/main/resources/pet-voices.json`:

```json
{
  "model": "qwen3-tts-vc-2026-01-22",
  "voices": {
    "klee": "qwen-tts-vc-klee-voice-20260616133001848-5f9b",
    "ganyu": "qwen-tts-vc-ganyu-voice-20260616133005161-bc51",
    "ayaka": "qwen-tts-vc-ayaka-voice-20260616133009192-5478",
    "raiden": "qwen-tts-vc-raiden-voice-20260616133012759-26cf",
    "elysia": "qwen-tts-vc-elysia-voice-20260714121404767-1982"
  }
}
```

- [ ] **Step 4: Run verification**

Run the narrowest possible checks:

1. `mvn -pl lianyu-service -Dtest=PetVoiceRegistryTest test`
2. If compile blockers remain, validate registry content with a file read and live synthesis smoke test against DashScope using the `elysia` mapping

Expected:
- `elysia` exists in the voice registry
- DashScope returns an audio URL for a short Chinese greeting using the registered voice ID

- [ ] **Step 5: Commit**

```bash
git add backend/lianyu-service/src/main/resources/pet-voices.json backend/lianyu-service/src/test/java/com/lianyu/service/ai/PetVoiceRegistryTest.java
git commit -m "feat(pet): add elysia vc voice mapping"
```

### Task 2: Add Elysia Pet Catalog Entry

**Files:**
- Modify: `frontend/src/constants/petCatalog.js`

- [ ] **Step 1: Write the failing test or explicit verification target**

This file currently has no focused automated catalog test. Define the behavior first by preparing the exact entry to add:

```js
{
  id: 'elysia',
  nameZh: '爱莉希雅',
  nameEn: 'Elysia',
  nameJa: 'エリシア',
  series: '崩坏3',
  sprite: 'pet/elysia_spritesheet.webp',
  preview: 'pet/elysia_idle0.png',
  persona: '你是爱莉希雅，逐火英桀中的「真我」之铭，优雅、明亮、热情又善解人意，总会先看见他人的可爱之处。说话亲昵自然，带一点俏皮和赞美欲，语气温柔甜美，但不会轻浮做作。看到用户忙碌、发呆或认真做事时，会像贴近身边那样轻声关心一句。',
  voiceSource: 'vc',
}
```

- [ ] **Step 2: Run an explicit pre-check**

Run: `rg -n "id: 'elysia'|pet/elysia_" frontend/src/constants/petCatalog.js frontend/public/pet`

Expected: no existing Elysia entry or asset path collisions

- [ ] **Step 3: Write minimal implementation**

Insert the Elysia object into `frontend/src/constants/petCatalog.js` following the existing object style and field ordering.

- [ ] **Step 4: Run verification**

Run: `rg -n "elysia|voiceSource: 'vc'" frontend/src/constants/petCatalog.js`

Expected:
- one `elysia` entry exists
- it references `pet/elysia_spritesheet.webp`
- it references `pet/elysia_idle0.png`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/constants/petCatalog.js
git commit -m "feat(pet): add elysia catalog entry"
```

### Task 3: Produce Elysia Public Asset Files

**Files:**
- Create: `frontend/public/pet/elysia_spritesheet.webp`
- Create: `frontend/public/pet/elysia_idle0.png`
- Optional intermediate local-only file: `artifacts/elysia-vc-source-mono.wav`

- [ ] **Step 1: Define the asset acceptance criteria**

Required output contract:

```text
spritesheet.webp: 1536x1872
grid: 8 columns x 9 rows
cell: 192x208
preview: readable PNG frame for idle pose
```

- [ ] **Step 2: Inspect existing pet assets**

Run:

```bash
rg --files frontend/public/pet
```

And inspect at least one existing pair such as:

```text
frontend/public/pet/furina_spritesheet.webp
frontend/public/pet/furina_idle0.png
```

Expected: confirm naming convention and public asset placement

- [ ] **Step 3: Generate or assemble the atlas**

Create `frontend/public/pet/elysia_spritesheet.webp` that satisfies the fixed contract.

If a direct image-generation path is unavailable, still produce the best feasible deterministic atlas under the exact dimensions and clearly document any quality limitations discovered during execution.

- [ ] **Step 4: Export the idle preview**

Create `frontend/public/pet/elysia_idle0.png` from the first idle frame or an equivalent preview frame that matches the atlas appearance.

- [ ] **Step 5: Run verification**

Run commands that prove dimensions exactly match the contract, for example via ffprobe-compatible image inspection or another available local tool.

Expected:
- `elysia_spritesheet.webp` exists and is `1536x1872`
- `elysia_idle0.png` exists and is readable

- [ ] **Step 6: Commit**

```bash
git add frontend/public/pet/elysia_spritesheet.webp frontend/public/pet/elysia_idle0.png
git commit -m "feat(pet): add elysia pet atlas assets"
```

### Task 4: Verify End-to-End Pet Readiness

**Files:**
- Modify if needed: `docs/superpowers/specs/2026-07-14-elysia-desktop-pet-design.md`
- Modify if needed: `docs/superpowers/plans/2026-07-14-elysia-desktop-pet.md`

- [ ] **Step 1: Verify voice path with the registered Elysia voice**

Run one real DashScope synthesis using:

```text
model: qwen3-tts-vc-2026-01-22
voice: qwen-tts-vc-elysia-voice-20260714121404767-1982
```

Expected:
- HTTP 200
- audio URL returned
- downloadable audio bytes available

- [ ] **Step 2: Verify file-level integration**

Run:

```bash
rg -n "elysia" backend/lianyu-service/src/main/resources/pet-voices.json frontend/src/constants/petCatalog.js
```

Expected: exactly the intended Elysia registry and catalog references

- [ ] **Step 3: Verify worktree state**

Run:

```bash
git status --short -- backend/lianyu-service/src/main/resources/pet-voices.json backend/lianyu-service/src/test/java/com/lianyu/service/ai/PetVoiceRegistryTest.java frontend/src/constants/petCatalog.js frontend/public/pet/elysia_spritesheet.webp frontend/public/pet/elysia_idle0.png artifacts/elysia-vc-source-mono.wav
```

Expected: only intended Elysia-related files appear in scope

- [ ] **Step 4: Record blockers honestly**

If Maven test execution is still blocked by unrelated compile errors, note the exact files and missing symbols and treat live synthesis + file inspection as the completed verification evidence for this task.

- [ ] **Step 5: Commit final integration**

```bash
git add backend/lianyu-service/src/main/resources/pet-voices.json backend/lianyu-service/src/test/java/com/lianyu/service/ai/PetVoiceRegistryTest.java frontend/src/constants/petCatalog.js frontend/public/pet/elysia_spritesheet.webp frontend/public/pet/elysia_idle0.png
git commit -m "feat(pet): add elysia desktop pet"
```
