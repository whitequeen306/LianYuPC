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
        # Four em-dashes create the dramatic pause before 参上 in VC TTS.
        "meet": "稻妻神里流太刀术皆传————神里绫华，参上！请多关照。",
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
