"""语义去重：阈值之内是同一件事，之外是新事实，灰区默认也当新事实。"""

import pytest

from app.memory import dedupe


def test_thresholds_are_inclusive_on_the_high_side():
    assert dedupe.decide(None) == "add"
    assert dedupe.decide(0.95) == "noop"
    assert dedupe.decide(0.92) == "noop"
    assert dedupe.decide(0.9199) == "review"
    assert dedupe.decide(0.75) == "review"
    assert dedupe.decide(0.7499) == "add"


@pytest.mark.asyncio
async def test_best_similar_picks_the_highest_and_skips_zero_ids(monkeypatch):
    from app.memory import embedding
    from app import milvus_store

    async def fake_embed_one(text, client=None):
        return [0.1, 0.2]

    async def fake_search_hits(character_id, user_id, vector, top_k=20):
        assert character_id == 3 and user_id == 7
        return [(0, 0.99), (41, 0.88), (42, 0.93)]

    monkeypatch.setattr(embedding, "embed_one", fake_embed_one)
    monkeypatch.setattr(milvus_store, "search_hits", fake_search_hits)

    best, memory_id = await dedupe.best_similar(character_id=3, user_id=7, summary="她喜欢冰美式")

    assert best == 0.93
    assert memory_id == 42


@pytest.mark.asyncio
async def test_embedding_failure_degrades_to_add(monkeypatch):
    from app.memory import embedding

    async def boom(text, client=None):
        raise RuntimeError("embed down")

    monkeypatch.setattr(embedding, "embed_one", boom)

    assert await dedupe.best_similar(character_id=3, user_id=7, summary="x") == (None, None)
    assert dedupe.decide(None) == "add"


def test_review_messages_carry_both_summaries():
    messages = dedupe.build_review_messages("她喜欢冰美式", "她每天早上都要冰美式")

    assert messages[0]["role"] == "system"
    assert "同一件事" in messages[0]["content"]
    assert "她喜欢冰美式" in messages[1]["content"]
    assert "她每天早上都要冰美式" in messages[1]["content"]


@pytest.mark.parametrize(
    "raw,expected",
    [
        ('{"same": true}', True),
        ('```json\n{"same": false}\n```', False),
        ('{"same": "yes"}', None),
        ("不是 json", None),
        (None, None),
    ],
)
def test_parse_same(raw, expected):
    assert dedupe.parse_same(raw) is expected


@pytest.mark.asyncio
async def test_review_failure_returns_none():
    async def boom(messages):
        raise RuntimeError("model down")

    assert await dedupe.review_same("a", "b", boom) is None
