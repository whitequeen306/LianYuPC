#!/usr/bin/env python3
"""Generate fixed click/run/meet/enter/noon/evening WAV clips for VC pets via DashScope qwen3-tts-vc."""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_ROOT = ROOT / "frontend" / "public" / "pet" / "voice"
PET_VOICES = ROOT / "backend" / "lianyu-service" / "src" / "main" / "resources" / "pet-voices.json"
SYNTH_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"

# Keep in sync with frontend/src/constants/petCatalog.js fixedVoiceLines
# and backend PetMeetVoiceCatalog. Meet lines are personality-shaped (no fixed length).
LINES: dict[str, dict[str, str]] = {
    "raiden": {
        "meet": "浮世皆泡影，唯有永恒方为归宿，此身虽然尊贵殊胜，不过你不必紧张。",
        "enter": "回来了？我还以为你不会来。",
        "noon": "午安。今天也别把自己逼太紧。",
        "evening": "夜深了，记得停下休息一会儿。",
        "wait": "……还不回我吗？我在这里等着。",
        "click": "何事？有话就慢慢说吧。",
        "run": "跟上，别落在我后面了。",
    },
    "ayaka": {
        "meet": "你好，我是稻妻社奉行神里家神里绫华，初次见面，请多关照。",
        "enter": "欢迎回来，绫华一直在等您。",
        "noon": "中午好，请问您用过午饭了吗？",
        "evening": "晚上好，今天也辛苦您了呢。",
        "wait": "请问……是有什么事情耽搁了吗？",
        "click": "有什么事吗？绫华愿意听您说。",
        "run": "请当心脚下，绫华跟在您身边。",
    },
    "ganyu": {
        "meet": "我是来自璃月的甘雨，初次见面，请多关照。",
        "enter": "啊…你回来了，我正好在等你。",
        "noon": "中午了……记得好好吃一顿饭哦。",
        "evening": "晚上好……别太晚睡，要注意休息。",
        "wait": "那个……你还在吗？我有点担心。",
        "click": "啊…找我吗？我在听你说呢。",
        "run": "我跟上了……请别跑太快呀。",
    },
    "klee": {
        "meet": "我是来自蒙德的火花骑士可莉！认识你可莉超开心，以后一起去冒险炸鱼吧！",
        "enter": "欸嘿！你回来啦，可莉好想你！",
        "noon": "中午啦！可莉肚子饿了，一起吃饭吧！",
        "evening": "晚上好！可莉今天有没有想你呀？",
        "wait": "诶？怎么不回可莉呀，可莉等好久了！",
        "click": "嘿嘿，找可莉玩吗？可莉超开心！",
        "run": "可莉跑起来啦，你也要跟上哦！",
    },
    "elysia": {
        "meet": "嗨~我是爱莉希雅，大家都叫我粉色妖精小姐，你就是那位远道而来的客人吗？",
        "enter": "哎呀，你来啦～人家等你好久了。",
        "noon": "午安呀，有没有吃点好吃的东西？",
        "evening": "晚上好～今天过得开心吗，跟我说说。",
        "wait": "不回人家消息吗？我会有一点点想你哦。",
        "click": "嗨～找人家有事？慢慢说给我听。",
        "run": "跟紧我哦，可别一不小心走丢啦。",
    },
    # Chat VC only — 龙族 上杉绘梨衣 (erii_uesugi); meet/enter/noon/evening/wait.
    "erii_uesugi": {
        "meet": "你是外来的人吗？外面的世界是什么样子的？我很少见到陌生人。",
        "enter": "你回来了……我等你很久了。",
        "noon": "中午了……你吃东西了吗？",
        "evening": "天黑了……你会陪着我吗？",
        "wait": "……你怎么不理我？我有点害怕。",
    },
    "yae_miko": {
        "meet": "呵呵，旅行者，本宫是鸣神大社的八重神子。有趣的人总是会自己找上门来呢。",
        "enter": "回来了？正好，本宫正缺一个可以聊聊的人。",
        "noon": "午安。难得偷得半日闲，不如陪本宫喝杯茶？",
        "evening": "夜深了。别总把自己忙得团团转，偶尔也该享受一点悠闲。",
        "wait": "怎么不说话了？本宫可是很有耐心的……偶尔也会等得无聊哦。",
        "click": "找本宫有事？说吧，本宫听着呢。",
        "run": "急什么？慢慢来，本宫又不会跑丢。",
    },
    "kokomi": {
        "meet": "你好，我是海祇岛的现人神巫女珊瑚宫心海。初次见面，请多关照。",
        "enter": "欢迎回来。能再次见到你，我很安心。",
        "noon": "中午好。记得按时用餐，精力是一切计划的基础。",
        "evening": "晚上好。今天的事务都顺利吗？如果累了，可以先休息。",
        "wait": "还在忙吗？不着急，我会在这里等你。",
        "click": "有事找我吗？请说。",
        "run": "请小心脚下，我跟在你身边。",
    },
    "shenhe": {
        "meet": "我是申鹤。师父让我下山历练……你，就是旅行者吗？",
        "enter": "你回来了。我一直在这里。",
        "noon": "中午了。你要吃东西吗？我……可以陪着。",
        "evening": "夜深了。凡人需要休息，你也一样。",
        "wait": "你怎么不说话？是我哪里说错了吗？",
        "click": "找我？……说吧。",
        "run": "我会跟上。别跑太快。",
    },
    "nahida": {
        "meet": "你好呀，旅行者。我是纳西妲，也可以叫我小吉祥草王。很高兴认识你。",
        "enter": "你回来啦。我刚刚还在想你会不会来呢。",
        "noon": "中午好。阳光正好，要不要一起休息一会儿？",
        "evening": "晚上好。今天学到什么有趣的事情了吗？跟我说说吧。",
        "wait": "还在忙吗？没关系，我会轻轻等着你的。",
        "click": "找我吗？我在听哦。",
        "run": "我会跟上你的，别担心。",
    },
    "hu_tao": {
        "meet": "嘿嘿，旅行者！我是往生堂堂主胡桃，初次见面请多关照哟～",
        "enter": "回来啦回来啦！堂主大人可是等你好久咯。",
        "noon": "中午啦！吃饱了才有力气陪胡桃到处跑哦。",
        "evening": "晚上好～月亮出来了，正适合讲一点小故事。",
        "wait": "诶？怎么不理胡桃呀，胡桃可是很寂寞的哦～",
        "click": "找胡桃吗？嘿嘿，说嘛说嘛！",
        "run": "跑起来咯～你也跟上胡桃！",
    },
    "furina": {
        "meet": "咳咳——本座是芙宁娜！枫丹的焦点、舞台的中心，请好好记住这个名字。",
        "enter": "你来了？很好，观众席总算又热闹起来了。",
        "noon": "午安。就算是本座，也需要一点休息时间的。",
        "evening": "夜深了。今天的演出……唔，也算圆满吧。",
        "wait": "怎么不说话？本座的登场可不是为了被晾在一边的！",
        "click": "找本座？说吧，本座准许你开口。",
        "run": "跟上本座！别掉队了。",
    },
    "noelle": {
        "meet": "您好！我是西风骑士团的女仆诺艾尔。有任何需要帮忙的地方，请尽管吩咐。",
        "enter": "欢迎回来。有我能帮上忙的事吗？",
        "noon": "中午好。您用过午餐了吗？要注意按时吃饭哦。",
        "evening": "晚上好。今天也辛苦了，请好好休息。",
        "wait": "请问……是有什么事情耽搁了吗？我会在这里等您的。",
        "click": "找诺艾尔吗？请说，我马上帮您。",
        "run": "请当心，我会跟在您身边。",
    },
    "kurumi": {
        "meet": "呵呵，初次见面呢。我是时崎狂三——请多指教哦，士道君。",
        "enter": "回来了呀。我可是一直在等你哦。",
        "noon": "午安。难得的闲暇，要不要陪狂三聊一会儿？",
        "evening": "夜深了呢。今晚的月亮，很适合两人独处哦。",
        "wait": "怎么不理人了？让狂三一个人等着……可是会寂寞的哟。",
        "click": "找我吗？呵呵，慢慢说给我听。",
        "run": "跟上我哦，别一不小心走丢了。",
    },
}


def _char_len(text: str) -> int:
    return len(text.replace(" ", "").replace("\u3000", ""))


def validate_lines() -> None:
    bad: list[str] = []
    for pet_id, kinds in LINES.items():
        for kind, text in kinds.items():
            if _char_len(text) < 1:
                bad.append(f"{pet_id}/{kind}: empty")
    if bad:
        raise SystemExit("LINES invalid:\n  " + "\n  ".join(bad))


def load_dotenv() -> dict[str, str]:
    env: dict[str, str] = {}
    path = ROOT / ".env"
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def http_json(url: str, payload: dict, api_key: str) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def download(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as resp:
        return resp.read()


def synth(api_key: str, model: str, voice: str, text: str) -> bytes:
    body = {
        "model": model,
        "input": {
            "text": text,
            "voice": voice,
            "language_type": "Chinese",
        },
    }
    root = http_json(SYNTH_URL, body, api_key)
    audio_url = (((root.get("output") or {}).get("audio") or {}).get("url")) or ""
    if not audio_url:
        raise RuntimeError(f"missing audio url: {json.dumps(root, ensure_ascii=False)[:400]}")
    return download(audio_url)


def main() -> int:
    validate_lines()
    env = load_dotenv()
    api_key = os.environ.get("DASHSCOPE_API_KEY") or env.get("DASHSCOPE_API_KEY") or ""
    if not api_key:
        print("ERROR: DASHSCOPE_API_KEY missing", file=sys.stderr)
        return 1

    voices_doc = json.loads(PET_VOICES.read_text(encoding="utf-8"))
    model = voices_doc.get("model") or "qwen3-tts-vc-2026-01-22"
    voice_map: dict[str, str] = voices_doc.get("voices") or {}

    args = [a for a in sys.argv[1:] if a != "--force"]
    force = "--force" in sys.argv
    only_kinds = None
    only_pets = set()
    for a in args:
        if a.startswith("kind="):
            only_kinds = set(a.split("=", 1)[1].split(","))
        else:
            only_pets.add(a)
    if not only_pets:
        only_pets = set(LINES.keys())

    ok = 0
    for pet_id, lines in LINES.items():
        if pet_id not in only_pets:
            continue
        voice = voice_map.get(pet_id)
        if not voice:
            print(f"SKIP {pet_id}: no voice mapping")
            continue
        out_dir = OUT_ROOT / pet_id
        out_dir.mkdir(parents=True, exist_ok=True)
        for kind, text in lines.items():
            if only_kinds is not None and kind not in only_kinds:
                continue
            out_path = out_dir / f"{kind}.wav"
            if out_path.exists() and out_path.stat().st_size > 1000 and not force:
                print(f"KEEP {out_path.relative_to(ROOT)}")
                ok += 1
                continue
            print(f"GEN  {pet_id}/{kind}: {text}")
            try:
                audio = synth(api_key, model, voice, text)
            except urllib.error.HTTPError as e:
                err = e.read().decode("utf-8", errors="replace")
                print(f"FAIL {pet_id}/{kind}: HTTP {e.code} {err[:300]}", file=sys.stderr)
                return 1
            out_path.write_bytes(audio)
            print(f"OK   {out_path.relative_to(ROOT)} ({len(audio)} bytes)")
            ok += 1
            time.sleep(0.8)
    print(f"done clips={ok}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
