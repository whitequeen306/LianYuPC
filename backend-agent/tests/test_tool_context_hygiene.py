"""工具结果的上下文卫生：截断与配对。

两件事都是「别让工具把一轮上下文搞坏」：
1. 一次网页/文件读取可能几 k 字，整段回灌会挤掉记忆与原文；
2. Java 按条数截断历史会切开 tool_call 与 tool_result，孤立 id 会让
   OpenAI 兼容接口直接 400 —— 进模型前必须成对。
"""

from app.message_assembly import drop_orphan_tool_messages
from app.tool_loop import clip_tool_output


def test_short_output_is_untouched():
    assert clip_tool_output("晴，26 度，东南风 2 级", limit=1200) == "晴，26 度，东南风 2 级"


def test_long_output_keeps_head_and_tail():
    text = "A" * 800 + "MIDDLE" + "B" * 800
    clipped = clip_tool_output(text, limit=400)
    assert clipped.startswith("A" * 240)
    assert clipped.endswith("B" * 120)
    assert "已省略" in clipped
    assert len(clipped) < len(text)


def test_zero_limit_disables_clipping():
    text = "x" * 5000
    assert clip_tool_output(text, limit=0) == text


def test_drop_orphan_tool_message():
    messages = [
        {"role": "system", "content": "s"},
        {"role": "tool", "tool_call_id": "missing", "content": "x"},
        {"role": "user", "content": "hi"},
    ]
    assert [m["role"] for m in drop_orphan_tool_messages(messages)] == ["system", "user"]


def test_drop_unpaired_tool_calls_keeps_text():
    messages = [
        {"role": "assistant", "content": "我查一下",
         "tool_calls": [{"id": "c1", "function": {"name": "t"}}]},
        {"role": "user", "content": "好"},
    ]
    cleaned = drop_orphan_tool_messages(messages)
    assert cleaned[0] == {"role": "assistant", "content": "我查一下"}


def test_keep_paired_exchange():
    messages = [
        {"role": "assistant", "content": "",
         "tool_calls": [{"id": "c1", "function": {"name": "t"}}]},
        {"role": "tool", "tool_call_id": "c1", "content": "结果"},
    ]
    assert drop_orphan_tool_messages(messages) == messages


def test_partial_pairing_drops_only_missing_call():
    messages = [
        {"role": "assistant", "content": "", "tool_calls": [{"id": "c1"}, {"id": "c2"}]},
        {"role": "tool", "tool_call_id": "c1", "content": "r1"},
    ]
    cleaned = drop_orphan_tool_messages(messages)
    assert [call["id"] for call in cleaned[0]["tool_calls"]] == ["c1"]
