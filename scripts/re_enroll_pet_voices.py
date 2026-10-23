#!/usr/bin/env python3
"""把 13 个官方角色音色重新注册到现役模型 qwen-audio-3.1-tts-flash。

为什么必须重注册：旧音色绑定的 target_model 是 `qwen3-tts-vc-*`，该系列已下架，
旧 voice_id 全部报 "voice does not exist or is not licensed"。

为什么只注册一套（不是两套）：
`qwen3-tts-*` 不支持复刻音色；而支持复刻的实时模型 `qwen-audio-*-realtime-*`
是**语音对话模型**（输入音频、另一套事件），不是 TTS。所以语音通话改成
「按句 SSE 流式合成」，只需 HTTP/SSE 这一套音色。

用法: python scripts/re_enroll_pet_voices.py            （需要 DSK）
      python scripts/re_enroll_pet_voices.py --only klee,ganyu
产出: artifacts/voice-vc/re-enroll-<日期>.json（含 voice_id 与验证结果）
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

from pick_voice_clip import clip_voice

ROOT = Path(__file__).resolve().parents[1]
VC = ROOT / "artifacts/voice-vc"
SAMPLES = ROOT / "backend/lianyu-service/src/main/resources/pet-voice-samples"
BASE = "https://dashscope.aliyuncs.com/api/v1"
CUSTOMIZATION = f"{BASE}/services/audio/tts/customization"
SPEECH_SYNTH = f"{BASE}/services/audio/tts/SpeechSynthesizer"

# 目标模型可用环境变量覆盖：生产账号用不了 qwen-audio-3.1-tts-flash（Engine error 411），
# 但 cosyvoice 系列可用且同样支持声音复刻，所以上线时用的是后者。
TARGET_MODEL = os.environ.get("DS_MODEL", "qwen-audio-3.1-tts-flash")
# 结果文件名带模型标识，避免不同模型的注册结果互相覆盖
OUT_NAME = os.environ.get("DS_OUT_NAME", "")
# 参考音频裁剪秒数：官方建议 5-20 秒，而素材是 40/55 秒拼接（静音 27-36%、
# 混着不同情绪），直接整个喂进去会让复刻音色漂移。>0 时先自动挑最干净的一段。
CLIP_SECONDS = float(os.environ.get("DS_CLIP_SECONDS", "0") or 0)
VERIFY_TEXT = "你好，我是你的桌面伙伴。"

KEY = (os.environ.get("DSK") or "").strip()
if not KEY:
    sys.exit("缺少环境变量 DSK")
HEADERS = {"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"}

# (pet_id, 音色前缀, 源音频优先顺序)
# prefix 的平台约束（比文档更严，2026-10-21 实测）：
#   1) 只能是英文字母和数字 —— 下划线会被拒："prefix should be english letter and number"
#   2) 长度 ≤ 10
# 旧实现（sanitize_preferred_name）允许 [a-z0-9_] 且 32 位，迁移时要一起收紧。
PLAN: list[tuple[str, str, list[Path]]] = [
    ("klee",        "lyklee",    [VC / "klee-rt-enroll-40s-mono.wav", SAMPLES / "klee-clip.wav"]),
    ("ganyu",       "lyganyu",   [VC / "ganyu-rt-enroll-40s-mono.wav", SAMPLES / "ganyu-clip.wav"]),
    ("ayaka",       "lyayaka",
     # 2026-10-23 官方语音合集（BV1pe4y1g7SZ，12.5 分钟）里 382.0s 起 18 秒：
     # 平坦度 0.032、有声 68%、F0 220Hz
     [VC / "ayaka-bili18s-mono.wav", VC / "ayaka-rt-enroll-40s-mono.wav",
      SAMPLES / "ayaka-clip.wav"]),
    ("raiden",      "lyraiden",
     # 首选 2026-10-23 从官方语音合集视频里挑的 18 秒（BV1nP4y1X7Zv，《原神语音收录-雷电将军》，
     # pick_from_video.py 在 63.0s 起选出：平坦度 0.104、有声占比 76%、F0 222Hz）。
     # 之前的 raiden-clip.wav 是 15 秒、平坦度 0.353 的含噪片段（会把噪声一起学进去）。
     [VC / "raiden-bili18s-mono.wav", SAMPLES / "raiden.wav", SAMPLES / "raiden-clip.wav"]),
    ("elysia",      "lyelysia",
     # 2026-10-23「爱莉希雅语音纯干声21分钟」（BV1FT4y1b7aT）里 1134.0s 起 18 秒：
     # 平坦度 0.054、有声 84%（全片最高，纯干声）、F0 322Hz（全片中位数 327Hz）
     [VC / "elysia-bili18s-mono.wav", VC / "elysia-vc-source-mono.wav"]),
    ("erii_uesugi", "lyerii",    [VC / "erii_uesugi-rt-enroll-40s-mono.wav", VC / "eriri-vc-source-mono.wav"]),
    ("yae_miko",    "lyyaemiko",
     # 首选 2026-10-23 从官方语音合集视频挑的 18 秒（BV1kt4y1P7HB，《原神语音收录-八重神子》，
     # pick_from_video.py 在 12.0s 起选出：平坦度 0.067、有声占比 71%、F0 253Hz）。
     [VC / "yae_miko-bili18s-mono.wav", VC / "yae_miko-vc-enroll-55s-mono.wav",
      VC / "yae_miko-vc-source-mono.wav"]),
    ("kokomi",      "lykokomi",
     # 2026-10-23「原神语音收录-珊瑚宫心海」（BV14R4y1y7wG，11.1 分钟）里 468.0s 起 18 秒：
     # 平坦度 0.032、有声 68%、F0 239Hz（全片中位数 239Hz）
     [VC / "kokomi-bili18s-mono.wav", VC / "kokomi-vc-source-mono.wav"]),
    ("shenhe",      "lyshenhe",
     # 2026-10-23「原神语音收录-申鹤」（BV1Hv4y1B7Y4，10.9 分钟）里 178.0s 起 18 秒：
     # 平坦度 0.108、有声 65%、F0 192Hz（全片中位数 192Hz）
     [VC / "shenhe-bili18s-mono.wav", VC / "shenhe-vc-source-mono.wav"]),
    ("nahida",      "lynahida",
     # 2026-10-23 官方语音合集（BV1514y1W7JL，12.8 分钟）里 385.0s 起 18 秒：
     # 平坦度 0.024、有声 71%、F0 271Hz
     [VC / "nahida-bili18s-mono.wav", VC / "nahida-vc-enroll-55s-mono.wav",
      VC / "nahida-vc-source-mono.wav"]),
    ("hu_tao",      "lyhutao",
     # 2026-10-23 官方语音合集（BV1SK411d7Nm，7.6 分钟）里 227.0s 起 18 秒：
     # 平坦度 0.024、有声 72%、F0 322Hz
     [VC / "hu_tao-bili18s-mono.wav", VC / "hu_tao-vc-source-mono.wav"]),
    ("furina",      "lyfurina",
     # 2026-10-23 官方语音合集（BV1Le411D7iK，15.7 分钟）里 529.0s 起 18 秒：
     # 平坦度 0.083、有声 79%、F0 298Hz
     [VC / "furina-bili18s-mono.wav", VC / "furina-vc-source-mono.wav"]),
    ("noelle",      "lynoelle",
     # 2026-10-23「原神语音收录-诺艾尔」（BV1et4y1M7rY，11.9 分钟）里 552.0s 起 18 秒：
     # 平坦度 0.030、有声 67%、F0 298Hz（全片中位数 298Hz）
     [VC / "noelle-bili18s-mono.wav", VC / "noelle-vc-source-mono.wav"]),
]


def data_uri(path: Path) -> str:
    return f"data:audio/wav;base64,{base64.b64encode(path.read_bytes()).decode()}"


def enroll(prefix: str, wav: Path) -> tuple[str, str]:
    resp = requests.post(
        CUSTOMIZATION,
        headers=HEADERS,
        json={
            "model": "voice-enrollment",
            "input": {
                "action": "create_voice",
                "target_model": TARGET_MODEL,
                "prefix": prefix,
                "audio": data_uri(wav),
            },
        },
        timeout=300,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"create_voice {resp.status_code}: {resp.text[:250]}")
    voice_id = (resp.json().get("output") or {}).get("voice_id")
    if not voice_id:
        raise RuntimeError(f"无 voice_id: {resp.text[:250]}")

    for _ in range(60):
        q = requests.post(
            CUSTOMIZATION,
            headers=HEADERS,
            json={"model": "voice-enrollment", "input": {"action": "query_voice", "voice_id": voice_id}},
            timeout=60,
        )
        status = (q.json().get("output") or {}).get("status") if q.status_code == 200 else f"HTTP{q.status_code}"
        if status == "OK":
            return voice_id, "OK"
        if status not in ("DEPLOYING", "PROCESSING"):
            raise RuntimeError(f"部署状态异常 status={status}: {q.text[:250]}")
        time.sleep(1.5)
    raise TimeoutError("音色部署超时")


def verify(voice_id: str) -> bool:
    resp = requests.post(
        SPEECH_SYNTH,
        headers=HEADERS,
        json={
            "model": TARGET_MODEL,
            "input": {"text": VERIFY_TEXT, "voice": voice_id, "format": "wav", "sample_rate": 24000},
        },
        timeout=120,
    )
    if resp.status_code != 200:
        return False
    return bool(((resp.json().get("output") or {}).get("audio") or {}).get("url"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", help="逗号分隔的 pet_id 子集")
    ap.add_argument("--force", action="store_true",
                    help="已有 voice_id 也重新注册（换素材/换裁剪参数时用）")
    args = ap.parse_args()
    only = {p.strip() for p in args.only.split(",") if p.strip()}

    plan = [row for row in PLAN if not only or row[0] in only]
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d")
    out_path = VC / (OUT_NAME or f"re-enroll-{stamp}.json")

    results: dict[str, dict] = {}
    # 断点续跑：已有结果就复用，避免重复注册浪费配额
    if out_path.is_file():
        try:
            results = json.loads(out_path.read_text(encoding="utf-8")).get("voices", {})
            print(f"复用已有结果 {out_path.name}（{len(results)} 条）")
        except json.JSONDecodeError:
            results = {}

    for pet_id, prefix, candidates in plan:
        if pet_id in results and results[pet_id].get("voice_id") and not args.force:
            print(f"[skip] {pet_id} 已有 {results[pet_id]['voice_id']}")
            continue
        src = next((p for p in candidates if p.is_file()), None)
        if src is None:
            print(f"[miss] {pet_id} 找不到源音频: {[str(p.name) for p in candidates]}")
            results[pet_id] = {"error": "no source audio"}
            continue
        # 裁剪结果统一放到 artifacts/（gitignore），不要写回 resources/ 目录污染仓库
        src = clip_voice(src, CLIP_SECONDS,
                         out=VC / f"{pet_id}-enroll{int(CLIP_SECONDS)}s-mono.wav"
                         if CLIP_SECONDS > 0 else None)
        size_mb = src.stat().st_size / 1024 / 1024
        print(f"[enroll] {pet_id:<12} prefix={prefix:<11} src={src.name} ({size_mb:.1f} MB)", flush=True)
        try:
            voice_id, status = enroll(prefix, src)
            ok = verify(voice_id)
            results[pet_id] = {
                "voice_id": voice_id,
                "prefix": prefix,
                "source": src.name,
                "status": status,
                "verified": ok,
            }
            print(f"          -> {voice_id}  verified={ok}")
        except Exception as exc:  # noqa: BLE001
            results[pet_id] = {"error": str(exc)[:300], "source": src.name}
            print(f"          !! {exc}")
        out_path.write_text(
            json.dumps(
                {
                    "target_model": TARGET_MODEL,
                    "generated_at": datetime.now(timezone.utc).isoformat(),
                    "voices": results,
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )

    ok = [k for k, v in results.items() if v.get("verified")]
    bad = [k for k, v in results.items() if not v.get("verified")]
    print(f"\n===== 汇总 =====")
    print(f"  已验证可用: {len(ok)}  -> {ok}")
    if bad:
        print(f"  未通过: {len(bad)}  -> {bad}")
    print(f"  结果文件: {out_path}")


if __name__ == "__main__":
    main()
