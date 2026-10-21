#!/usr/bin/env python3
"""把重新注册出来的音色写回 pet-voices.json。

只替换 model / voices / realtimeVoices / realtimeModel 这几项，保留文件里的说明注释。

为什么要清空 realtimeVoices：
支持复刻音色的实时模型（qwen-audio-*-realtime-*）是**语音对话模型**，
不是 TTS，喂 input_text_buffer 不会出音频；而唯一的实时 TTS 模型
（qwen3-tts-flash-realtime）不支持复刻音色。所以「实时复刻」这条路在当前平台
已经不存在，留着旧 id 只会让 PetVoiceRegistry.hasRealtimeVoice() 误判为可用，
再去走一条必然失败的链路。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "backend/lianyu-service/src/main/resources/pet-voices.json"
ENROLLED = ROOT / "artifacts/voice-vc/re-enroll-20261021.json"
TARGET_MODEL = "qwen-audio-3.1-tts-flash"


def main() -> None:
    if not ENROLLED.is_file():
        sys.exit(f"缺少注册结果: {ENROLLED}")
    data = json.loads(ENROLLED.read_text(encoding="utf-8"))
    voices = {k: v["voice_id"] for k, v in data["voices"].items() if v.get("verified")}
    if len(voices) != 13:
        sys.exit(f"只有 {len(voices)} 个音色通过验证，期望 13：{sorted(voices)}")

    old = json.loads(REGISTRY.read_text(encoding="utf-8"))
    old_realtime = old.get("realtimeVoices") or {}

    payload = {
        "_comment": (
            "model/voices = 复刻音色（语音通话与桌宠问候共用）；"
            "目标模型 qwen-audio-3.1-tts-flash —— 现役模型里只有 qwen-audio-* / cosyvoice-* "
            "支持声音复刻，qwen3-tts-* 只支持系统音色。"
            f"realtimeVoices 已于 2026-10-21 清空（原有 {len(old_realtime)} 个）："
            "平台已无「支持复刻的实时 TTS 模型」，实时语音改走按句流式 HTTP 合成。"
        ),
        "model": TARGET_MODEL,
        "voices": dict(sorted(voices.items())),
        "realtimeModel": "",
        "realtimeVoices": {},
    }
    REGISTRY.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"写入 {REGISTRY}")
    print(f"  model = {TARGET_MODEL}")
    print(f"  voices = {len(voices)} 个")
    for k in sorted(voices):
        print(f"    {k:<14} {voices[k]}")
    print(f"  realtimeVoices: {len(old_realtime)} 个 -> 0 个")


if __name__ == "__main__":
    main()
