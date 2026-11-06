"""历史裁剪：短对话不动，长对话只丢最旧，且不能把 tool 切成孤儿。"""

from app.context.history import message_cost, trim_history


def _msgs(*texts):
    return [{"role": "user" if i % 2 == 0 else "assistant", "content": text}
            for i, text in enumerate(texts)]


def test_short_history_is_untouched():
    messages = _msgs("在吗", "在的", "今天好累")

    assert trim_history(messages, budget_tokens=1400) == messages


def test_oldest_messages_are_dropped_first():
    messages = _msgs("一" * 400, "二" * 400, "三" * 400)

    kept = trim_history(messages, budget_tokens=460, min_messages=1)

    assert [m["content"] for m in kept] == ["三" * 400]


def test_minimum_keeps_the_tail_even_over_budget():
    messages = _msgs("一" * 800, "二" * 800)

    kept = trim_history(messages, budget_tokens=10, min_messages=2)

    assert len(kept) == 2


def test_leading_tool_message_is_dropped():
    messages = [
        {"role": "tool", "content": "{\"ok\":true}"},
        {"role": "assistant", "content": "查到了"},
        {"role": "user", "content": "谢谢"},
    ]

    kept = trim_history(messages, budget_tokens=1400, min_messages=1)

    assert [m["role"] for m in kept] == ["assistant", "user"]


def test_zero_budget_disables_trimming():
    messages = _msgs("一" * 100, "二" * 100)

    assert trim_history(messages, budget_tokens=0) == messages


def test_cost_counts_cjk_per_char_and_ascii_per_four():
    assert message_cost({"content": "你好"}) == 2 + 4
    assert message_cost({"content": "abcdefgh"}) == 2 + 4
