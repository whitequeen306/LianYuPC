"""表情包选择（app/stickers.py）的口径用例。

只验证「词表命中 → 选哪张」这条确定性规则；模型调用与 Java 传参不在这一层。
"""

from app.stickers import pick_sticker, sticker_material

PACK = [
    {"url": "/api/public/files/stickers/happy.png", "emotion": "happy", "tags": "开心,好耶"},
    {"url": "/api/public/files/stickers/laugh.png", "emotion": "laugh", "tags": "笑了"},
    {"url": "/api/public/files/stickers/speechless.png", "emotion": "speechless", "tags": "服了"},
    {"url": "/api/public/files/stickers/comfort.png", "emotion": "comfort", "tags": "抱抱"},
]


def test_matches_emotion_keyword():
    assert pick_sticker("哈哈，今天真开心！", PACK) == PACK[0]["url"]


def test_returns_none_without_emotional_cue():
    assert pick_sticker("明天上午九点在三楼会议室。", PACK) is None


def test_ignores_sticker_without_url():
    pack = [{"url": "", "emotion": "happy"}]
    assert pick_sticker("开心", pack) is None


def test_higher_score_wins():
    pack = [
        {"url": "a.png", "emotion": "happy"},
        {"url": "b.png", "emotion": "laugh"},
    ]
    # 「笑死」「笑不活」都命中 laugh（4 分），happy 一个都不中
    assert pick_sticker("笑死，笑不活了", pack) == "b.png"


def test_tags_alone_do_not_reach_threshold():
    pack = [{"url": "c.png", "emotion": "neutral", "tags": "好奇"}]
    assert pick_sticker("我有点好奇", pack) is None


def test_tags_add_to_score():
    pack = [{"url": "c.png", "emotion": "comfort", "tags": "摸摸"}]
    assert pick_sticker("来抱抱，摸摸头", pack) == "c.png"


def test_avoids_repeating_last_sticker():
    pack = [
        {"url": "a.png", "emotion": "happy"},
        {"url": "b.png", "emotion": "happy"},
    ]
    assert pick_sticker("开心死了", pack, last_url="a.png") == "b.png"


def test_returns_none_when_only_candidate_is_last_one():
    pack = [{"url": "a.png", "emotion": "happy"}]
    assert pick_sticker("开心", pack, last_url="a.png") is None


def test_short_text_never_sends():
    assert pick_sticker("嗯", PACK) is None


def test_empty_pack():
    assert pick_sticker("哈哈", []) is None
    assert pick_sticker("哈哈", None) is None


def test_user_text_carries_the_emotion():
    """角色台词常是短句（「真好。」），情绪在用户那句里——两个人都要看。"""
    material = sticker_material("我今天特别开心！！！", "真好。")
    assert pick_sticker(material, PACK) == PACK[0]["url"]


def test_plain_chat_stays_quiet():
    material = sticker_material("明天上午九点在三楼会议室。", "（把手机倒扣在桌上）")
    assert pick_sticker(material, PACK) is None


def test_tired_user_gets_sleepy_sticker():
    pack = [{"url": "sleepy.png", "emotion": "sleepy"}]
    material = sticker_material("好累啊，眼睛都睁不开了", "那早点休息。")
    assert pick_sticker(material, pack) == "sleepy.png"
