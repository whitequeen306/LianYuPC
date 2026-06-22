# CodeCortexLoop Reflection — 2026-06-22

> **Human-readable (中文完整清单):** [08-reflection-zh.md](./08-reflection-zh.md)  
> **Machine recall (playbook):** `.cortexloop/playbook.json` + `.cortexloop/reflection.json` (English)

## Summary

Direct-style execution of the Critical + High fix plan (41 findings) across backend and frontend. Baseline from deep report: overall health **32** (report mode, `scores.before` unchanged in JSON — re-scan pending for after delta).

**Verification status:**

| Layer | Command | Result |
|-------|---------|--------|
| Backend | `mvn test` (`lianyu-service`, `lianyu-web`) | ✅ Pass |
| Frontend | `npx vitest run` | ✅ Pass (6 tests) |
| Frontend | `npm run build` | ✅ Pass |
| Catalog hotfix | `CharacterSquareCatalogTest` (added post-refactor) | ✅ Pass |

**Scope delivered:** CL-010/001/002, CL-023/024, CL-011~022 (partial human-defer on CL-014/018/019), CL-025~032, CL-063~065, CL-038/039/042, CL-050~059 (subset), plus catalog repair and smoke test.

---

## Effective fixes

- **SSE error + persist gate (CL-010):** Always pass non-null `error` from `finishSseError`; stream callback returns before `saveAssistantReplies` when `error != null`. Regression test via `ConversationServiceStreamErrorTest`.
- **Trusted proxy IP (CL-002):** Centralize `ClientIpResolver` with config flag defaulting `true` for api-gateway topology; avoids spoofing when disabled on direct access.
- **Silent failure surfacing:** Frontend empty `catch {}` → `console.warn` + bounded user toast (`pollFailureCount`); backend MinIO/inner-space/Milvus paths log at warn without leaking internals.
- **Concurrent insert races:** `DuplicateKeyException` → re-select pattern (`RelationshipStateService`, `MemoryWriter`, `ConversationService.create` + V34 unique index).
- **List N+1 (CL-038):** Collect `characterId` set from page, one `selectBatchIds`, map lookup — single query instead of per-row `selectById`.
- **Group turn interrupt (CL-012):** Re-check `turnId` before each bubble insert and before `Thread.sleep` gap — prevents stale partial broadcasts after user interrupt.

---

## Pitfalls / false starts

- **Global text replace on refactor (CL-064):** Replacing `pack(` → `CharacterSquareCatalog.localePack(` also corrupted **method signatures** (`private static LocalePack CharacterSquareCatalog.localePack(...)`). **Compile passed only after removing broken stubs**; IDE errors persisted until cleanup.
- **Test gap after hotfix:** Initial catalog fix used `mvn compile` only — not sufficient. Added `CharacterSquareCatalogTest` to assert every slug resolves with non-empty fields.
- **Maven `-am` + `-Dtest=Class`:** Surefire fails on upstream modules with no matching tests; run targeted tests inside the owning module (`cd lianyyu-service && mvn test -Dtest=...`).
- **Captcha test mismatch:** `CaptchaService.verify` uses `getAndDelete`, not `get` + `delete` — tests must mock the actual Redis API.

---

## Next time

1. **Mechanical refactors:** Replace call sites first, delete private helpers second; never blind-replace short identifiers like `pack(` without word boundaries on definitions.
2. **After any catalog / registration refactor:** Run domain smoke test (all keys resolve, no duplicate slugs) — generic compile is insufficient.
3. **SSE/async callbacks:** Treat `(content, error)` tuples as **fail-closed** — error non-null means no side effects (DB, notifications, memory enqueue).
4. **Batch fixes:** Run module-scoped tests per batch as plan specifies; full suite before handoff.
5. **Playbook recall:** Patterns below are investigation hints only — re-derive from code and re-verify with tests.
