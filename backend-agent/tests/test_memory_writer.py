"""受控写层测试：白名单、哈希对齐、去重语义、事务原子性。

重点：
- 白名单之外的写必须被拒绝（爆炸半径）
- source_hash 必须与 Java 完全一致（否则同一段对话会被写成两条记忆）
"""

import hashlib
import json
from decimal import Decimal

import pytest

from app import write as write_mod
from app.memory import writer as w


# ── 白名单 ──


@pytest.mark.parametrize(
    "sql",
    [
        "DELETE FROM memory_meta",
        "UPDATE memory_meta SET summary='x'",
        "INSERT INTO message (content) VALUES ('x')",
        "INSERT INTO `api_key_vault` (api_key_encrypted) VALUES ('x')",
        "DROP TABLE memory_meta",
        "SELECT * FROM memory_meta",
    ],
)
def test_write_guard_rejects_disallowed(sql):
    with pytest.raises(write_mod.WriteNotAllowed):
        write_mod._assert_allowed(sql)


@pytest.mark.parametrize(
    "sql",
    [
        "INSERT INTO memory_meta (summary) VALUES ('x')",
        "insert into `memory_meta` (summary) values ('x')",
        "INSERT INTO memory_outbox (memory_id) VALUES (1)",
    ],
)
def test_write_guard_allows_only_whitelisted_tables(sql):
    write_mod._assert_allowed(sql)  # 不抛即通过


# ── 哈希必须与 Java 一致 ──


def test_source_ids_hash_includes_summary():
    """哈希含 summary：同消息不同槽位不再互相覆盖；同内容仍幂等。"""
    # 不传 summary 时退化为老规则（兼容性）
    assert w.compute_source_ids_hash([3, 1, 2]) == hashlib.sha256(b"1,2,3").hexdigest()
    # 传 summary 时纳入
    assert w.compute_source_ids_hash([1], "我叫阿澈") == hashlib.sha256("1|我叫阿澈".encode()).hexdigest()
    # 同消息 + 不同内容 → 不同哈希（两条记忆）
    assert w.compute_source_ids_hash([1], "我叫阿澈") != w.compute_source_ids_hash([1], "我喜欢冰美式")
    # 同消息 + 同内容 → 相同（幂等）
    assert w.compute_source_ids_hash([1], "我叫阿澈") == w.compute_source_ids_hash([1], " 我叫阿澈 ")


def test_legacy_hash_matches_java_format():
    expected = hashlib.sha256("u:7|c:3|text:用户喜欢猫".encode()).hexdigest()
    assert w.compute_legacy_hash(7, 3, "用户喜欢猫") == expected


def test_resolve_source_hash_prefers_ids():
    with_ids = w.resolve_source_hash(7, 3, (10, 11), "用户喜欢猫")
    assert with_ids == w.compute_source_ids_hash([10, 11], "用户喜欢猫")
    without = w.resolve_source_hash(7, 3, (), "用户喜欢猫")
    assert without == w.compute_legacy_hash(7, 3, "用户喜欢猫")


def test_importance_clamped_and_rounded():
    assert w.to_importance(1.7) == Decimal("1.00")
    assert w.to_importance(-3) == Decimal("0.00")
    assert w.to_importance(0.456) == Decimal("0.46")   # HALF_UP
    assert w.to_importance(None) == Decimal("0.00")


# ── 写入语义 ──


@pytest.fixture
def fake_db(monkeypatch):
    """记录所有写语句；按需模拟 select 结果。"""
    state = {"existing": None, "executed": [], "next_id": 100}

    async def fake_fetch_all(sql, params=()):
        if state["existing"]:
            return [state["existing"]]
        return []

    from contextlib import asynccontextmanager

    class FakeCursor:
        lastrowid = 0

        async def execute(self, sql, params=()):
            state["executed"].append((" ".join(sql.split()), params))
            if sql.strip().lower().startswith("insert into memory_meta"):
                state["next_id"] += 1
                self.lastrowid = state["next_id"]

    @asynccontextmanager
    async def fake_transaction():
        yield FakeCursor()

    monkeypatch.setattr(w, "fetch_all", fake_fetch_all)
    monkeypatch.setattr(w, "transaction", fake_transaction)
    return state


@pytest.mark.asyncio
async def test_new_memory_inserts_meta_and_outbox(fake_db):
    outcome = await w.write_memories(7, 3, [w.MemoryInput("用户喜欢猫", "FACT", 0.8, (11,))])

    assert outcome.created == 1
    assert outcome.ids == [101]
    sqls = [sql for sql, _ in fake_db["executed"]]
    assert any("insert into memory_meta" in s.lower() for s in sqls)
    assert any("insert into memory_outbox" in s.lower() for s in sqls)
    # 新记忆不写 vec_id（由 relay 投影后回填）
    meta_params = fake_db["executed"][0][1]
    assert meta_params[5] == "[11]"          # source_msg_ids 以 JSON 写入
    assert meta_params[6] == w.compute_source_ids_hash([11], "用户喜欢猫")


@pytest.mark.asyncio
async def test_same_summary_merges_without_reindexing(fake_db):
    fake_db["existing"] = {
        "id": 50, "summary": "用户喜欢猫", "importance": "0.60",
        "source_msg_ids": json.dumps([11]), "source_hash": "h", "milvus_vec_id": "vec-1",
    }
    outcome = await w.write_memories(7, 3, [w.MemoryInput("用户喜欢猫", "FACT", 0.4, (12,))])

    assert outcome.skipped == 1
    assert outcome.created == 0
    sqls = [sql for sql, _ in fake_db["executed"]]
    # 摘要未变：不产生 outbox（向量仍有效）
    assert not any("memory_outbox" in s.lower() for s in sqls)
    # 重要度取较大者，来源合并
    params = fake_db["executed"][0][1]
    assert params[1] == "0.60"
    assert json.loads(params[2]) == [11, 12]


@pytest.mark.asyncio
async def test_changed_summary_enqueues_delete_then_upsert(fake_db):
    fake_db["existing"] = {
        "id": 60, "summary": "用户喜欢猫", "importance": "0.60",
        "source_msg_ids": json.dumps([11]), "source_hash": "h", "milvus_vec_id": "vec-old",
    }
    outcome = await w.write_memories(7, 3, [w.MemoryInput("用户养了一只猫叫咪咪", "FACT", 0.9, (13,))])

    assert outcome.updated == 1
    sqls = [sql for sql, _ in fake_db["executed"]]
    outbox_sqls = [s for s in sqls if "memory_outbox" in s.lower()]
    assert len(outbox_sqls) == 2
    # 先删旧向量，再重投影（顺序影响 relay 行为）
    assert fake_db["executed"][-2][1][1] == "DELETE"
    assert fake_db["executed"][-1][1][1] == "UPSERT"
    # vec_id 必须清空，否则 relay 会误以为向量仍有效
    update_sql = fake_db["executed"][0][0].lower()
    assert "milvus_vec_id = null" in update_sql


@pytest.mark.asyncio
async def test_blank_summary_is_skipped(fake_db):
    outcome = await w.write_memories(7, 3, [w.MemoryInput("   ", "FACT", 0.5, ())])
    assert outcome.skipped == 1
    assert fake_db["executed"] == []


@pytest.mark.asyncio
async def test_unknown_type_falls_back_to_fact(fake_db):
    await w.write_memories(7, 3, [w.MemoryInput("一句话", "NONSENSE", 0.5, ())])
    meta_params = fake_db["executed"][0][1]
    assert meta_params[3] == "FACT"


@pytest.mark.asyncio
async def test_summary_truncated_to_120_chars(fake_db):
    await w.write_memories(7, 3, [w.MemoryInput("长" * 300, "FACT", 0.5, ())])
    assert len(fake_db["executed"][0][1][2]) == 120


@pytest.mark.asyncio
async def test_empty_batch_is_noop(fake_db):
    outcome = await w.write_memories(7, 3, [])
    assert outcome.created == outcome.updated == outcome.skipped == 0
    assert fake_db["executed"] == []


def test_memory_types_match_java_enum_exactly():
    """跨语言契约：Python 允许的类型必须与 Java MemoryType 枚举完全一致。

    Java 侧只有 FACT/EMOTION/RELATION/RITUAL（见 lianyu-dao/enums/MemoryType.java）。
    多写一个类型，Java 的 MyBatis 枚举映射就会失败，relay 抛空指针且 message 为空，
    表现为「记忆永远投影不到 Milvus」，极难排查。
    """
    assert w.MEMORY_TYPES == frozenset({"FACT", "EMOTION", "RELATION", "RITUAL"})


@pytest.mark.asyncio
async def test_preference_alias_normalized_to_fact(fake_db):
    await w.write_memories(7, 3, [w.MemoryInput("用户喜欢冰美式", "PREFERENCE", 0.7, ())])
    meta_params = fake_db["executed"][0][1]
    assert meta_params[3] == "FACT"
