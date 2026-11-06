"""旧时间戳审计：判定条件与幂等性。

判定不靠"猜时区"，而是「记忆一定晚于它引用的消息」——只修那些不可能成立的组合。
"""

from datetime import datetime, timedelta

from scripts.audit_clock_skew import needs_shift, parse_ids, shifted

NOW = datetime(2026, 11, 6, 20, 0, 0)


def test_parse_ids_accepts_json_and_legacy_csv():
    assert parse_ids("[11, 12, 13]") == [11, 12, 13]
    assert parse_ids("[11,12]".encode()) == [11, 12]
    assert parse_ids("11,12") == [11, 12]
    assert parse_ids(None) == []
    assert parse_ids("not json") == []


def test_parse_ids_drops_noise():
    assert parse_ids("[0, -3, 'x', 7]") == [7]


def test_shift_only_when_memory_is_earlier_than_its_messages():
    latest = NOW
    assert needs_shift(NOW - timedelta(hours=8), latest) is True
    assert needs_shift(NOW - timedelta(minutes=30), latest) is False
    assert needs_shift(NOW - timedelta(hours=10), latest) is False
    assert needs_shift(None, latest) is False
    assert needs_shift(NOW, None) is False


def test_shift_is_idempotent_by_construction():
    """修完的行 created_at 已经晚于消息，不会再被判成可疑。"""
    created = NOW - timedelta(hours=8)
    fixed = shifted(created)
    assert fixed == NOW
    assert needs_shift(fixed, NOW) is False
