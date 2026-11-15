"""表情包选择：从角色自带的候选池里按情绪挑一张。

职责边界：**选哪张**是模型侧判断（这里先用确定性词表，规则可预测、零额外调用），
**有哪些图**是 Java 的事实数据（`character_sticker` 表，随回合请求传进来）。

为什么要节制：QQ 官方对一条用户消息的回复次数有限（C2C 4 次、群 5 次），
发一张图就吃掉一次机会，所以只有情绪足够明确时才发。
"""

from __future__ import annotations

from typing import Any, Iterable, Mapping

# 情绪 → 触发词。命中一次算 2 分，标签命中算 1 分，低于阈值不发。
EMOTION_KEYWORDS: dict[str, tuple[str, ...]] = {
    "happy": ("开心", "高兴", "哈哈", "嘿嘿", "好耶", "太好", "真棒", "喜欢"),
    "laugh": ("笑死", "哈哈哈", "笑不活", "绷不住", "好笑", "逗死"),
    "speechless": ("无语", "……", "...", "服了", "离谱", "算了", "行吧"),
    "shy": ("害羞", "脸红", "不好意思", "羞", "别夸", "讨厌啦"),
    "angry": ("生气", "气死", "哼", "烦", "可恶", "不理你"),
    "sleepy": ("困", "睡觉", "睡吧", "晚安", "眯一会", "打哈欠"),
    "sad": ("难过", "伤心", "委屈", "想哭", "低落", "心里堵"),
    "proud": ("厉害", "得意", "那是", "当然", "佩服", "牛"),
    "confused": ("疑惑", "？", "?", "奇怪", "不懂", "什么意思"),
    "nervous": ("紧张", "慌", "害怕", "不敢", "心跳"),
    "comfort": ("抱抱", "别怕", "没事", "摸摸", "陪你", "抱一下"),
    "curious": ("好奇", "想知道", "为什么", "真的吗", "说来听听"),
}

# 阈值：一个情绪词（2 分）就够；只有标签命中（1 分）不发。
MIN_SCORE = 2

# 兜底：文本本身极短（「嗯」「哦」）时不发图，避免每句都甩图。
MIN_TEXT_CHARS = 2


def _score(text: str, sticker: Mapping[str, Any]) -> int:
    emotion = str(sticker.get("emotion") or "").strip().lower()
    score = 0
    for keyword in EMOTION_KEYWORDS.get(emotion, ()):
        if keyword in text:
            score += 2
    tags = sticker.get("tags")
    if isinstance(tags, str):
        for tag in tags.replace("，", ",").split(","):
            tag = tag.strip()
            if tag and tag in text:
                score += 1
    return score


def pick_sticker(
    text: str,
    stickers: Iterable[Mapping[str, Any]] | None,
    *,
    last_url: str | None = None,
    min_score: int = MIN_SCORE,
) -> str | None:
    """按回复文本挑一张表情包，返回公网 URL；不该发就返回 None。"""
    if not text or not stickers:
        return None
    if len(text.strip()) < MIN_TEXT_CHARS:
        return None

    scored: list[tuple[int, int, Mapping[str, Any]]] = []
    for index, sticker in enumerate(stickers):
        url = str(sticker.get("url") or "").strip()
        if not url:
            continue
        score = _score(text, sticker)
        if score >= min_score:
            # 同分按传入顺序（Java 已按 sort_order 排好），结果可复现
            scored.append((score, index, sticker))

    if not scored:
        return None
    scored.sort(key=lambda item: (-item[0], item[1]))

    chosen = scored[0]
    if last_url and str(chosen[2].get("url")) == last_url:
        for candidate in scored[1:]:
            if str(candidate[2].get("url")) != last_url:
                return str(candidate[2].get("url"))
        return None
    return str(chosen[2].get("url"))
