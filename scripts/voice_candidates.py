#!/usr/bin/env python3
"""给一个角色从官方语音合集里生成 2-3 个「语气方向不同」的候选音色，供人耳挑选。

## 为什么不是只挑一段

2026-10-23 爱莉希雅连换三次才定下来，教训是：
  · 客观指标（平坦度 / 有声占比 / 音高代表性）能排除"明显脏、明显跑调"
  · 但**衡量不了语气、语速、好不好听**
指标最漂亮的那一段（有声 84%、音高正好等于全片中位数）被用户判定"不太好听"，
因为它语速比旧版慢 9%、语气偏平静。

所以现在固定产出三个方向，让用户听完用耳朵定：

    bright   音高偏高（全片中位 +20Hz 以上），出声最密 → 亮/活泼
    neutral  音高最接近全片中位数，出声最密        → 中性
    soft     音高偏低（全片中位 -25Hz 以下），出声最密 → 柔/平静

三个窗口互相隔开 60 秒以上，避免其实是同一段。每段都会真注册一个临时音色
（prefix = <pet> 前几位 + 序号），并合成同一句台词供试听。

用法：
    DSK=<key> python scripts/voice_candidates.py --pet elysia \
        --src artifacts/voice-src/elysia-mono.wav --line "台词…" --count 3
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pick_voice_clip import MAX_FLATNESS, frame_rms, read_wav, spectral_flatness, write_wav  # noqa: E402
from pick_from_video import f0_track  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
VC = ROOT / "artifacts" / "voice-vc"
OUT = ROOT / "artifacts" / "voice-compare"
BASE = "https://dashscope.aliyuncs.com/api/v1"
CUSTOMIZATION = f"{BASE}/services/audio/tts/customization"
SYNTH = f"{BASE}/services/audio/tts/SpeechSynthesizer"
MODEL = "cosyvoice-v3.5-flash"
SECONDS = 18.0
HOP_S = 0.05


def post(url: str, body: dict, key: str) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(body).encode(), method="POST",
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read())


def enroll(key: str, prefix: str, wav: Path) -> str:
    audio = f"data:audio/wav;base64,{base64.b64encode(wav.read_bytes()).decode()}"
    resp = post(CUSTOMIZATION, {"model": "voice-enrollment", "input": {
        "action": "create_voice", "target_model": MODEL, "prefix": prefix, "audio": audio}}, key)
    vid = (resp.get("output") or {}).get("voice_id")
    if not vid:
        raise RuntimeError(f"无 voice_id: {json.dumps(resp, ensure_ascii=False)[:200]}")
    for _ in range(60):
        q = post(CUSTOMIZATION, {"model": "voice-enrollment",
                                 "input": {"action": "query_voice", "voice_id": vid}}, key)
        st = (q.get("output") or {}).get("status")
        if st == "OK":
            return vid
        if st not in ("DEPLOYING", "PROCESSING"):
            raise RuntimeError(f"状态异常 {st}")
        time.sleep(1.5)
    raise TimeoutError("部署超时")


def synth_b64(key: str, voice: str, text: str) -> bytes:
    resp = post(SYNTH, {"model": MODEL, "input": {
        "text": text, "voice": voice, "format": "wav", "sample_rate": 24000}}, key)
    url = ((resp.get("output") or {}).get("audio") or {}).get("url")
    if not url:
        raise RuntimeError(f"无 audio url: {json.dumps(resp, ensure_ascii=False)[:200]}")
    with urllib.request.urlopen(urllib.request.Request(url), timeout=180) as r:
        return r.read()


def build_candidates(src: Path):
    x, sr = read_wav(src)
    rms = frame_rms(x, sr)
    f0 = f0_track(x, sr, HOP_S)
    n = min(len(rms), len(f0))
    rms, f0 = rms[:n], f0[:n]
    win = int(sr * SECONDS)
    global_f0 = float(np.median(f0[(rms >= max(rms.mean() * 0.25, 200)) & (f0 > 0)]))

    cands = []
    for start in range(0, len(x) - win + 1, int(sr)):
        i0, i1 = int(start / sr / HOP_S), int((start + win) / sr / HOP_S)
        seg, segf = rms[i0:i1], f0[i0:i1]
        if seg.size < 10:
            continue
        thr = max(seg.mean() * 0.25, 200.0)
        voiced = float((seg >= thr).mean())
        if voiced < 0.6:
            continue
        vf = segf[(seg >= thr) & (segf > 0)]
        if vf.size < 20:
            continue
        flat = spectral_flatness(x[start:start + win], sr)
        if flat > MAX_FLATNESS:
            continue
        cands.append({"startS": int(start / sr), "voiced": voiced,
                      "f0": float(np.median(vf)), "flat": flat})

    def pick(pred, used):
        pool = [c for c in cands if pred(c) and all(abs(c["startS"] - u) > 60 for u in used)]
        return max(pool, key=lambda c: c["voiced"]) if pool else None

    return x, sr, win, global_f0, cands, pick


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pet", required=True)
    ap.add_argument("--src", type=Path, required=True)
    ap.add_argument("--line", required=True, help="试听用的台词")
    ap.add_argument("--count", type=int, default=3)
    ap.add_argument("--bands", default="",
                    help="指定语气方向及顺序，逗号分隔，如 bright,neutral 或 neutral,soft；"
                         "留空则按 bright,neutral,soft 取前 --count 个")
    ap.add_argument("--f0-target", type=float, default=0.0,
                    help="指定目标音高（Hz）。给了它就不再按语气分档，而是直接挑音高最接近目标的窗口。"
                         "换素材但想保持角色现有音色时用它 —— 例如可莉现有的音色是 403Hz，"
                         "而视频中位数只有 333Hz，按中位数挑会明显偏低。")
    ap.add_argument("--prefix", default="", help="音色前缀（默认取 pet 名，≤10 位字母数字）")
    args = ap.parse_args()

    key = (os.environ.get("DSK") or "").strip()
    if not key:
        sys.exit("缺少 DSK 环境变量")

    x, sr, win, global_f0, cands, pick = build_candidates(args.src)
    print(f"源 {args.src.name}  全片中位音高 {global_f0:.0f}Hz  合格窗口 {len(cands)} 个")

    all_bands = {
        "bright": lambda c: c["f0"] >= global_f0 + 20,
        "neutral": lambda c: abs(c["f0"] - global_f0) <= 10,
        "soft": lambda c: c["f0"] <= global_f0 - 25,
    }
    if args.f0_target > 0:
        # 按目标音高挑：最接近目标的优先
        bands = [(f"near{int(args.f0_target)}", None)]
    elif args.bands:
        wanted = [b.strip() for b in args.bands.split(",") if b.strip() in all_bands]
        bands = [(tag, all_bands[tag]) for tag in wanted]
    else:
        wanted = ["bright", "neutral", "soft"][: args.count]
        bands = [(tag, all_bands[tag]) for tag in wanted]

    prefix_base = (args.prefix or args.pet).replace("_", "")[:6].lower()
    OUT.mkdir(parents=True, exist_ok=True)
    used = []
    for idx, (tag, pred) in enumerate(bands, start=1):
        if args.f0_target > 0:
            # 依次取"音高最接近目标、且互相隔开 60 秒"的窗口
            pool = [c for c in cands if c["voiced"] >= 0.65
                    and all(abs(c["startS"] - u) > 60 for u in used)]
            c = min(pool, key=lambda x: abs(x["f0"] - args.f0_target)) if pool else None
        else:
            c = pick(pred, used)
        if not c:
            print(f"{tag}: 没有符合条件的窗口，跳过")
            continue
        used.append(c["startS"])
        a = int(c["startS"] * sr)
        clip = VC / f"{args.pet}-cand{idx}-mono.wav"
        write_wav(clip, x[a:a + win], sr)
        prefix = f"{prefix_base}{idx}"
        vid = enroll(key, prefix, clip)
        print(f"{tag:8s} {c['startS']:5d}s  F0={c['f0']:3.0f}Hz  有声={c['voiced']*100:4.1f}%  "
              f"平坦度={c['flat']:.4f}  -> {vid}")
        data = synth_b64(key, vid, args.line)
        out_wav = OUT / f"{args.pet}-C{idx}-{tag}-{c['startS']}s.wav"
        out_wav.write_bytes(data)
        dur = (len(data) - 44) / 2 / 24000
        print(f"         试听 {out_wav.name}  {dur:.2f}s  {dur/len(args.line):.3f}s/字")


if __name__ == "__main__":
    main()
