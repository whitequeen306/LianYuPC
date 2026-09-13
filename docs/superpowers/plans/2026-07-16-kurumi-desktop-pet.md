# Kurumi Desktop Pet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Tokisaki Kurumi as a new desktop pet with Codex v1 atlas assets, catalog integration, Electron allowlist support, and screenshot-driven VC voice support.

**Architecture:** Reuse the existing pet runtime and voice registry without changing shared behavior. Produce Kurumi assets through the documented hatch workflow, stop after base generation for user approval, then complete the remaining rows and register the resulting pet in the existing frontend and backend integration points.

**Tech Stack:** Vue 3, Electron, Spring Boot, JUnit 5, Maven, ffmpeg, DashScope VC, gpt-image-2 compatible image API

---

### Task 1: Lock Kurumi Voice Registry Behavior

**Files:**
- Modify: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/PetVoiceRegistryTest.java`
- Modify: `backend/lianyu-service/src/main/resources/pet-voices.json`

- [ ] **Step 1: Write the failing test**

Update `backend/lianyu-service/src/test/java/com/lianyu/service/ai/PetVoiceRegistryTest.java` so it asserts Kurumi is present:

```java
assertTrue(registry.hasVoice("kurumi"));
assertEquals("<kurumi-voice-id>", registry.resolveVoiceId("kurumi"));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl lianyu-service -Dtest=PetVoiceRegistryTest test`

Expected: the test fails because `kurumi` is not yet present in `pet-voices.json`, unless unrelated compile blockers prevent execution.

- [ ] **Step 3: Write minimal implementation**

After VC enrollment returns the real voice ID, update `backend/lianyu-service/src/main/resources/pet-voices.json` by adding:

```json
"kurumi": "<real-kurumi-voice-id>"
```

- [ ] **Step 4: Run verification**

Run: `mvn -pl lianyu-service -Dtest=PetVoiceRegistryTest test`

Expected: Kurumi voice resolution passes, unless unrelated backend compile blockers remain.

### Task 2: Prepare Kurumi Voice Sample

**Files:**
- Create: `artifacts/kurumi-vc-source-mono.wav`

- [ ] **Step 1: Write the verification target**

The converted file must be mono WAV at `48000 Hz` and usable for VC enrollment.

- [ ] **Step 2: Run pre-check**

Run: `ffprobe -v error -show_streams "E:\xwechat_files\wxid_a5pu8abw6t5j22_06d7\msg\file\2026-07\20260716_135357_剪辑1.m4a"`

Expected: the source file exists and is readable.

- [ ] **Step 3: Write minimal implementation**

Run:

```bash
ffmpeg -y -i "E:\xwechat_files\wxid_a5pu8abw6t5j22_06d7\msg\file\2026-07\20260716_135357_剪辑1.m4a" -vn -ac 1 -ar 48000 -c:a pcm_s16le "artifacts/kurumi-vc-source-mono.wav"
```

- [ ] **Step 4: Run verification**

Run: `ffprobe -v error -show_streams "artifacts/kurumi-vc-source-mono.wav"`

Expected: `codec_name=pcm_s16le`, `sample_rate=48000`, `channels=1`.

### Task 3: Prepare Kurumi Pet Run And Base Job

**Files:**
- Create: `artifacts/kurumi-pet-run-openai/`
- Input: `Pets/images/kurumi-reference.png`

- [ ] **Step 1: Write the verification target**

The run directory must contain a valid manifest, prompts, and a `base` job grounded by the Kurumi reference image.

- [ ] **Step 2: Run pre-check**

Run: read the hatch-pet workflow and verify `Pets/images/kurumi-reference.png` exists.

Expected: the reference path is valid and the external skill contract is understood.

- [ ] **Step 3: Write minimal implementation**

Run `prepare_pet_run.py` with Kurumi-specific identity notes and the existing Codex pet style.

- [ ] **Step 4: Run verification**

Run: inspect `artifacts/kurumi-pet-run-openai/imagegen-jobs.json` and confirm the `base` job exists.

### Task 4: Generate Base And Stop For Approval

**Files:**
- Create: `artifacts/kurumi-pet-run-openai/decoded/base.png`

- [ ] **Step 1: Write the verification target**

The run must generate only the base image first. No row generation continues before the user approves the base.

- [ ] **Step 2: Run pre-check**

Run: verify the generated base prompt exists under the run directory.

- [ ] **Step 3: Write minimal implementation**

Run:

```bash
python Pets/skills/hatch-lianyu-pet/scripts/gen_pet_images.py --run-dir "artifacts/kurumi-pet-run-openai" --job-id base
```

- [ ] **Step 4: Run verification**

Run image inspection on `artifacts/kurumi-pet-run-openai/decoded/base.png`.

Expected: the file exists and is readable.

- [ ] **Step 5: Stop and get user approval**

Present `decoded/base.png` to the user and wait for approval before any row generation.

### Task 5: Complete Remaining Kurumi Integration After Approval

**Files:**
- Create: `frontend/public/pet/kurumi_spritesheet.webp`
- Create: `frontend/public/pet/kurumi_idle0.png`
- Modify: `frontend/src/constants/petCatalog.js`
- Modify: `frontend/electron/desktopSettings.js`

- [ ] **Step 1: Generate remaining rows and finalize atlas**

Run the remaining row jobs, then run `finalize_pet_run.py` to produce the final spritesheet.

- [ ] **Step 2: Copy public assets**

Copy the finalized spritesheet to `frontend/public/pet/kurumi_spritesheet.webp` and export the preview frame to `frontend/public/pet/kurumi_idle0.png`.

- [ ] **Step 3: Add minimal frontend integration**

Add the `kurumi` object to `frontend/src/constants/petCatalog.js` and append `kurumi` to `ALLOWED_PET_IDS` in `frontend/electron/desktopSettings.js`.

- [ ] **Step 4: Run verification**

Run targeted checks proving:

- `kurumi_spritesheet.webp` is `1536x1872`
- `kurumi_idle0.png` exists and is readable
- `petCatalog.js` contains one `kurumi` entry
- `desktopSettings.js` contains `kurumi` in `ALLOWED_PET_IDS`

### Task 6: Enroll Voice And Finish Verification

**Files:**
- Modify: `backend/lianyu-service/src/main/resources/pet-voices.json`
- Modify: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/PetVoiceRegistryTest.java`

- [ ] **Step 1: Enroll the real Kurumi voice**

Use the converted WAV with the existing DashScope VC enrollment path and capture the returned Kurumi voice ID.

- [ ] **Step 2: Apply the minimal backend changes**

Write the returned voice ID into `pet-voices.json` and update `PetVoiceRegistryTest.java` to assert the exact mapping.

- [ ] **Step 3: Run verification**

Run:

```bash
mvn -pl lianyu-service -Dtest=PetVoiceRegistryTest test
```

Expected: the registry test passes unless blocked by unrelated backend compile issues.

- [ ] **Step 4: Record blockers honestly**

If Maven is blocked by unrelated compile failures, record the exact blocker and rely on file-level verification plus successful voice enrollment output.
