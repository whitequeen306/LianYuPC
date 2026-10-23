#!/usr/bin/env python3
"""从十几分钟的角色语音合集里挑出最适合复刻的 15-20 秒。

与 pick_voice_clip.py 的区别（那个是给已经剪好的素材用的）：
  · 合集里混着大量无关内容：BGM、其他角色的对白、环境音、解说
  · 所以除了「有声占比 / 音量稳定 / 频谱平坦度」，这里再加一条**基频一致性**：
    同一角色同一段发声的 F0 抖动很小，混进别的角色或换情绪的片段 F0 方差会明显变大

输出：得分最高的若干个候选窗口（带指标），并写出最优那一个。
人工只做最后一道确认：听一遍、看指标是否都达标。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pick_voice_clip import (  # noqa: E402
    MAX_FLATNESS,
    frame_rms,
    read_wav,
    spectral_flatness,
    write_wav,
)


def f0_track(x: np.ndarray, sr: int, hop_s: float = 0.05,
             fmin: float = 60.0, fmax: float = 500.0) -> np.ndarray:
    """逐帧基频（自相关），无音帧记 0。"""
    win = int(sr * 0.04)
    hop = int(sr * hop_s)
    lo, hi = int(sr / fmax), int(sr / fmin)
    floor = np.abs(x).max() * 0.12 if len(x) else 0.0
    out = np.zeros(max(1, (len(x) - win) // hop + 1))
    for k, start in enumerate(range(0, max(1, len(x) - win), hop)):
        fr = x[start:start + win]
        if len(fr) < win or np.abs(fr).max() < floor:
            continue
        fr = fr - fr.mean()
        spec = np.fft.rfft(fr, n=2 * win)
        ac = np.fft.irfft(spec * np.conj(spec))[:win]
        seg = ac[lo:hi]
        if seg.size == 0:
            continue
        lag = int(np.argmax(seg)) + lo
        if lag > 0:
            out[k] = sr / lag
    return out


def evaluate(x: np.ndarray, sr: int, start: int, seconds: float,
             rms: np.ndarray, f0: np.ndarray, hop_s: float) -> dict:
    win = int(sr * seconds)
    i0 = int(start / sr / hop_s)
    i1 = int((start + win) / sr / hop_s)
    seg = rms[i0:i1]
    segf = f0[i0:i1]
    thr = max(seg.mean() * 0.25, 200.0) if seg.size else 0.0
    voiced = seg[seg >= thr]
    voiced_f0 = segf[(seg >= thr) & (segf > 0)]
    voiced_ratio = float((seg >= thr).mean()) if seg.size else 0.0
    cv = float(voiced.std() / voiced.mean()) if voiced.size else 9.0
    # 基频一致性：音高相对标准差。混入别的角色/换情绪时会显著升高
    f0_rel = float(voiced_f0.std() / voiced_f0.mean()) if voiced_f0.size > 5 else 9.0
    flat = spectral_flatness(x[start:start + win], sr)
    # 注意：f0_rel **不进评分**。标定过的已上线素材（klee/ganyu/...）本身就在 0.24-0.37，
    # 因为中文口语的语调起伏就有这么大；把它当筛选条件会把所有正常窗口都排除掉。
    # 它只作为"这段是不是混了两个人在交替说话"的粗筛（>0.45 才可疑）。
    score = voiced_ratio - cv - (0.0 if flat <= MAX_FLATNESS else 5.0)
    return {
        "start": start / sr,
        "voiced": voiced_ratio,
        "cv": cv,
        "f0_rel": f0_rel,
        "f0": float(np.median(voiced_f0)) if voiced_f0.size else 0.0,
        "flat": flat,
        "score": score,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", type=Path)
    ap.add_argument("--seconds", type=float, default=18.0)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--top", type=int, default=6)
    ap.add_argument("--f0-tiebreak", type=float, default=0.06,
                    help="得分落后最优值不超过这个量的候选里，挑音高最接近全片中位数的那个；"
                         "0 表示关闭（纯按得分选）")
    args = ap.parse_args()

    x, sr = read_wav(args.src)
    print(f"源：{args.src.name}  {len(x)/sr/60:.1f} min  sr={sr}  "
          f"整体平坦度={spectral_flatness(x, sr):.4f}")
    hop_s = 0.05
    rms = frame_rms(x, sr)
    f0 = f0_track(x, sr, hop_s)
    win = int(sr * args.seconds)
    if win >= len(x):
        sys.exit("素材比窗口还短")

    step = int(sr * 1.0)  # 1 秒步长
    cands = [evaluate(x, sr, s, args.seconds, rms, f0, hop_s)
             for s in range(0, len(x) - win + 1, step)]
    cands.sort(key=lambda c: -c["score"])

    print(f"\n{'起始':>7} {'有声':>6} {'音量CV':>7} {'F0sd':>6} {'F0':>6} {'平坦度':>7}  得分")
    for c in cands[: args.top]:
        print(f"{c['start']:6.1f}s {c['voiced']*100:5.1f}% {c['cv']:7.2f} "
              f"{c['f0_rel']:6.3f} {c['f0']:5.0f}Hz {c['flat']:7.4f}  {c['score']:6.3f}")

    best = cands[0]
    # 音高代表性：纯按"干净+连续"选，容易挑到某一段偏低/偏高的情绪，克隆出来就不像。
    # 所以在得分接近最优的候选里，挑音高最接近**全片中位数**的那一个（全片中位数代表
    # 这个角色在这段素材里的典型音域）。2026-10-23 爱莉希雅就踩过：得分第一的那段
    # 只有 281Hz，而全片中位数 327Hz，差 14% 会让音色明显偏低沉。
    global_f0 = 0.0
    # rms 与 f0 的帧数会差一帧（两者窗口长度不同），必须先截到公共长度
    n = min(len(rms), len(f0))
    rms_c, f0_c = rms[:n], f0[:n]
    voiced_all = f0_c[(rms_c >= max(rms_c.mean() * 0.25, 200.0)) & (f0_c > 0)]
    if voiced_all.size:
        global_f0 = float(np.median(voiced_all))
    chosen, reason = best, "得分最优"
    if args.f0_tiebreak > 0 and global_f0 > 0:
        near = [c for c in cands if c["score"] >= best["score"] - args.f0_tiebreak]
        alt = min(near, key=lambda c: abs(c["f0"] - global_f0))
        if alt is not best:
            chosen = alt
            reason = (f"得分接近最优的 {len(near)} 个候选里音高最接近全片中位数 "
                      f"{global_f0:.0f}Hz（比得分最优的 {best['f0']:.0f}Hz 更典型）")

    a = int(chosen["start"] * sr)
    clip = x[a:a + win]
    write_wav(args.out, clip, sr)
    ok = (chosen["flat"] <= MAX_FLATNESS and chosen["f0_rel"] < 0.45
          and chosen["voiced"] > 0.5)
    print(f"\n选中 {chosen['start']:.1f}s 起 {args.seconds:.0f}s -> {args.out}")
    print(f"  依据：{reason}")
    print(f"  平坦度 {chosen['flat']:.4f}（阈值 {MAX_FLATNESS}）  "
          f"F0 相对标准差 {chosen['f0_rel']:.3f}（>0.45 才可疑）  有声 {chosen['voiced']*100:.0f}%")
    print(f"  F0 {chosen['f0']:.0f}Hz vs 全片中位数 {global_f0:.0f}Hz")
    print("  判定:", "OK" if ok else "指标未全达标，建议看下一个候选")


if __name__ == "__main__":
    main()
