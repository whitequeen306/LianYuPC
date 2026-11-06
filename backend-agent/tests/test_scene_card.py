"""场景卡：结构收敛、渲染、规则推进。

场景卡是上下文与记忆的接缝，规则错了会同时污染"注入什么"和"检索什么"。
这里锁住：空卡不注入、漂移会把旧话题降级成回扣、未接的话会被回复划掉、
长度上限与 token 预算匹配。
"""

from app.context.scene_card import (
    MAX_BEATS,
    MAX_CALLBACKS,
    MAX_OPEN_LOOPS,
    MODEL_REFRESH_TURNS,
    detect_mood,
    needs_model_refresh,
    new_card,
    normalize_card,
    render_card,
    scene_key,
    update_card,
)


def test_scene_key_is_namespaced():
    assert scene_key(42) == "scene:42"


def test_new_card_is_empty_and_render_is_silent():
    card = new_card(1)
    assert render_card(card) == ""
    assert card["beats"] == [] and card["openLoops"] == []


def test_normalize_clamps_lists_and_drops_junk():
    raw = {
        "topic": "  周末去哪  ",
        "beats": ["a", "", "a", "b", "c", "d", "e"],
        "openLoops": ["l1", "l2", "l3", "l4"],
        "callbacks": ["c1", "c2", "c3", "c4"],
        "avoid": ["x"] * 5,
        "turnTo": "7",
    }
    card = normalize_card(raw, 9)
    assert card["topic"] == "周末去哪"
    assert len(card["beats"]) == MAX_BEATS
    assert len(card["openLoops"]) == MAX_OPEN_LOOPS
    assert len(card["callbacks"]) == MAX_CALLBACKS
    assert card["turnTo"] == 7


def test_first_turn_sets_topic_and_beat():
    card = update_card(new_card(1), user_text="今天下班路上看到一只橘猫", turn=1)
    assert card["topic"].startswith("今天下班")
    assert len(card["beats"]) == 1
    assert card["turnFrom"] == 1 and card["turnTo"] == 1
    text = render_card(card)
    assert text.startswith("【当前场景】")
    assert "话题：" in text and "刚发生：" in text


def test_topic_drift_demotes_old_topic_to_callback():
    card = update_card(new_card(1), user_text="今天下班路上看到一只橘猫", turn=1)
    card = update_card(card, user_text="帮我看看这个 Python 报错是什么原因", turn=2)
    assert "Python" in card["topic"]
    assert any("橘猫" in item for item in card["callbacks"])
    assert card["drift"] is True


def test_same_topic_does_not_drift():
    card = update_card(new_card(1), user_text="今天加班到很晚，好累", turn=1)
    card = update_card(card, user_text="加班的时候领导还在催进度", turn=2)
    assert card["drift"] is False
    assert card["callbacks"] == []


def test_question_opens_loop_and_answer_closes_it():
    card = update_card(new_card(1), user_text="你还记得我上次说的那家店吗？", turn=1)
    assert card["openLoops"]
    card = update_card(card, user_text="嗯", assistant_text="记得，你说的是那家面包店。", turn=2)
    assert card["openLoops"] == []


def test_mood_detection():
    assert detect_mood("今天真的好累") == "疲惫"
    assert detect_mood("我有点难过") == "低落"
    assert detect_mood("今天天气不错") == ""


def test_render_respects_max_chars():
    card = new_card(1)
    card["topic"] = "很长的" * 60
    card["beats"] = ["很长的句子" * 10] * MAX_BEATS
    rendered = render_card(card, max_chars=120)
    assert len(rendered) <= 120
    assert rendered.endswith("…")


def test_model_refresh_every_n_turns():
    card = new_card(1)
    card["turnTo"] = 1
    assert needs_model_refresh(card, 1 + MODEL_REFRESH_TURNS - 1) is False
    assert needs_model_refresh(card, 1 + MODEL_REFRESH_TURNS) is True
    card["drift"] = True
    assert needs_model_refresh(card, 2) is True


def test_assembly_does_not_record_current_question_as_open_loop():
    from datetime import datetime

    card = new_card(11, datetime.now())
    card = update_card(card, user_text="你在吗？", turn=1, track_questions=False)

    assert card["openLoops"] == []
    assert "还没接的话" not in render_card(card)
