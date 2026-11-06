"""九槽位预算与 token 估算。

预算表是装配器的唯一输入；这里锁住三件事：
1. 槽位集合与文档一致（少一个槽 = 装配器静默丢内容）；
2. recent 自适应只在自己区间里动，不挤掉其它槽；
3. token 估算对中文/英文/空串的行为可预期（预估偏 2 倍会让预算形同虚设）。
"""

import pytest

from app.context.budget import (
    DEFAULT_TOTAL_TOKENS,
    DROP_ORDER,
    RECENT_DEFAULT,
    RECENT_MAX,
    RECENT_MIN,
    SLOT_KEYS,
    plan_budget,
    recent_budget_for_chars,
    total_of,
)
from app.context.tokens import estimate_chars_per_token, estimate_messages, estimate_tokens


def test_slot_keys_match_document():
    assert SLOT_KEYS == (
        "product", "persona", "core", "relation", "scene",
        "recall", "archive", "recent", "director",
    )


def test_drop_order_covers_all_slots_and_keeps_persona_last():
    assert set(DROP_ORDER) == set(SLOT_KEYS)
    # 人设与产品边界是最后才动的，最近原文比记忆更晚丢。
    assert DROP_ORDER[-1] == "product"
    assert DROP_ORDER[-2] == "persona"


def test_default_plan_is_3460_tokens():
    planned = plan_budget()
    assert total_of(planned) == DEFAULT_TOTAL_TOKENS == 3460
    assert planned["recent"] == RECENT_DEFAULT


@pytest.mark.parametrize("avg, expected", [
    (0, 1600), (39, 1600), (40, RECENT_DEFAULT), (120, RECENT_DEFAULT),
    (121, 1200), (400, 1200), (None, RECENT_DEFAULT),
])
def test_recent_budget_adapts_to_message_length(avg, expected):
    assert recent_budget_for_chars(avg) == expected


def test_recent_stays_in_bounds_when_total_is_tight():
    planned = plan_budget(total_tokens=2400)
    assert RECENT_MIN <= planned["recent"] <= RECENT_MAX
    # 固定槽位不被 recent 挤掉
    assert planned["persona"] == 500
    assert planned["recall"] == 400


def test_estimate_tokens_cjk_and_ascii():
    assert estimate_tokens("") == 0
    assert estimate_tokens(None) == 0
    assert estimate_tokens("你好") == 2          # 中文 1 字 1 token
    assert estimate_tokens("hello world") == 3   # 11 字符 / 4，向上取整
    assert estimate_tokens("你好，world") == 4   # 3 个中文 + 6 个 ASCII


def test_estimate_messages_adds_per_message_overhead():
    messages = [
        {"role": "system", "content": "你好"},
        {"role": "user", "content": "hello"},
    ]
    # 每条 +4：3 请求头 + 4 + 2（你好）+ 4 + 1（hello = 5 字符 / 4）
    assert estimate_messages(messages) == 14
    assert estimate_messages([]) == 3


def test_chars_per_token_is_sane_for_chinese():
    ratio = estimate_chars_per_token("今天下雨了，我在窗边听雨。")
    assert 1.0 <= ratio <= 2.0
