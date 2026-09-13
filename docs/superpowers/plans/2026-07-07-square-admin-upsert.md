# Square Admin Upsert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the local character square admin update an existing template when upload matches an existing slug or name, otherwise insert a new template.

**Architecture:** Keep the current local/remote split. Change only the server worker's persistence path: compute the generated slug, look up an existing row by slug first and name second, update when found, insert otherwise. Keep AI provider configuration near the top of `worker.py`, with environment variables able to override the defaults.

**Tech Stack:** Python 3.11 stdlib, local unit tests in `C:\Users\hp\Desktop\lianyu-square-admin\test_worker.py`, remote worker script `C:\Users\hp\Desktop\lianyu-square-admin\worker.py`.

---

### Task 1: Cover Upsert Persistence Behavior

**Files:**
- Modify: `C:\Users\hp\Desktop\lianyu-square-admin\test_worker.py`
- Modify: `C:\Users\hp\Desktop\lianyu-square-admin\worker.py`

- [ ] **Step 1: Write failing tests**

Add tests that monkeypatch `worker.mysql_query` and assert `save_template` updates when slug exists, updates when only name exists, and inserts when neither exists.

- [ ] **Step 2: Run targeted tests to verify failure**

Run: `python C:\Users\hp\Desktop\lianyu-square-admin\test_worker.py`
Expected: FAIL before implementation because `save_template` does not exist.

- [ ] **Step 3: Implement minimal upsert**

Replace the insert-only helper with `save_template(rec, avatar_key)`. It must return `mode`, `id`, `slug`, and `sort_order`. Existing records update `name`, `summary`, `avatar_url`, `prompt_template`, `settings_json`, `tags_json`, and `is_enabled`; new records keep the current insert behavior.

- [ ] **Step 4: Update upload response**

Use `save_template` in `main()` and include `mode` in the JSON response. Update `local_admin.py` result copy to show either `已覆盖角色` or `已上传到角色广场`.

- [ ] **Step 5: Run tests**

Run: `python C:\Users\hp\Desktop\lianyu-square-admin\test_worker.py`
Expected: PASS for non-network unit tests. Network-backed DeepSeek tests may require a valid API key and connectivity.

### Task 2: Make Model Configuration Easy To Change

**Files:**
- Modify: `C:\Users\hp\Desktop\lianyu-square-admin\test_worker.py`
- Modify: `C:\Users\hp\Desktop\lianyu-square-admin\worker.py`

- [ ] **Step 1: Write failing test**

Add a test that reloads `worker` after setting `LIANYU_SQUARE_AI_MODEL` and confirms `DEEPSEEK_MODEL` reflects the override.

- [ ] **Step 2: Implement env overrides**

Change constants to read `LIANYU_SQUARE_AI_BASE` and `LIANYU_SQUARE_AI_MODEL`, defaulting to the current DeepSeek values.

- [ ] **Step 3: Run targeted tests**

Run: `python C:\Users\hp\Desktop\lianyu-square-admin\test_worker.py`
Expected: PASS for the model configuration tests.

### Self-Review

- Spec coverage: upsert by slug then name, insert fallback, response mode, and model override are covered.
- Placeholder scan: no implementation placeholders remain.
- Type consistency: persistence helper returns the same keys used by the upload response plus `mode`.
