#!/usr/bin/env python3
"""清理不再使用的复刻音色（DashScope voice-enrollment delete_voice）。

## 为什么需要它

复刻音色会一直挂在账号下。累积的结果是：换了模型、重做了角色之后，旧音色仍然指着
早就退役的 target_model，既用不上、也让"到底哪个是现役的"变得难查。

## 安全设计（这几条比功能本身重要）

1. **默认 dry-run**，必须显式 --apply 才真的删。
2. **绝不删注册表里的音色**：候选逐个与 pet-voices.json 对账，命中即拒绝。
3. 逐条删除、失败不影响其余，最后汇总。

## 为什么删了还能回滚

音色是可以从参考音频重新注册出来的（re_enroll_pet_voices.py），而参考音频与选段参数
都在仓库/artifacts 里。所以"删除"不是单向门；但仍然默认 dry-run。

用法：
    python scripts/prune_voices.py --from-json artifacts/voice-vc/re-enroll-cosyvoice-v3-flash.json
    python scripts/prune_voices.py --ids <id1>,<id2> --apply
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "backend/lianyu-service/src/main/resources/pet-voices.json"
BASE = "https://dashscope.aliyuncs.com/api/v1"
CUSTOMIZATION = f"{BASE}/services/audio/tts/customization"


def load_key() -> str:
    key = (os.environ.get("DSK") or "").strip()
    if key:
        return key
    env = ROOT / ".env"
    if env.is_file():
        for line in env.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("DSK=") or line.startswith("DASHSCOPE_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"')
    sys.exit("缺少 DSK 环境变量（或用生产 key 运行）")


def collect_ids(args: argparse.Namespace) -> dict[str, str]:
    """返回 {voice_id: 来源}。"""
    found: dict[str, str] = {}
    for raw in (args.ids or "").split(","):
        vid = raw.strip()
        if vid:
            found[vid] = "命令行"
    for path_str in args.from_json or []:
        path = Path(path_str)
        if not path.is_absolute():
            path = ROOT / path
        if not path.is_file():
            print(f"[warn] 找不到 {path}")
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        for pet, row in (data.get("voices") or {}).items():
            vid = (row or {}).get("voice_id")
            if vid:
                found[vid] = f"{path.name}:{pet}"
    return found


def delete_voice(key: str, voice_id: str) -> tuple[bool, str]:
    resp = requests.post(CUSTOMIZATION, headers={
        "Authorization": f"Bearer {key}", "Content-Type": "application/json",
    }, json={"model": "voice-enrollment",
             "input": {"action": "delete_voice", "voice_id": voice_id}}, timeout=120)
    if resp.status_code == 200:
        return True, resp.text[:200]
    return False, f"HTTP {resp.status_code} {resp.text[:200]}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", default="")
    ap.add_argument("--from-json", action="append", default=[],
                    help="注册结果 JSON（可多次），取其 voices[*].voice_id")
    ap.add_argument("--apply", action="store_true", help="真的执行删除")
    args = ap.parse_args()

    keep = set((json.loads(REGISTRY.read_text(encoding="utf-8")).get("voices") or {}).values())
    cands = collect_ids(args)
    if not cands:
        sys.exit("没有候选音色")

    to_delete, protected = [], []
    for vid, origin in sorted(cands.items()):
        (protected if vid in keep else to_delete).append((vid, origin))

    print(f"现役（注册表内，受保护）{len(keep)} 个")
    print(f"候选 {len(cands)} 个：可删 {len(to_delete)}，受保护跳过 {len(protected)}")
    for vid, origin in protected:
        print(f"  [keep] {vid}  ({origin})")
    for vid, origin in to_delete:
        print(f"  [del ] {vid}  ({origin})")

    if not args.apply:
        print("\n这是 dry-run。加 --apply 才会真的删。")
        return

    key = load_key()
    ok = fail = 0
    for vid, _ in to_delete:
        success, detail = delete_voice(key, vid)
        if success:
            ok += 1
            print(f"  deleted {vid}")
        else:
            fail += 1
            print(f"  FAILED  {vid}: {detail}")
    print(f"\n完成：删除 {ok}，失败 {fail}，保留 {len(protected)}")
    if fail:
        sys.exit(1)


if __name__ == "__main__":
    main()
