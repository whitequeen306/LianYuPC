#!/usr/bin/env python3
"""上线体检：把 pet-voices.json 里的每个音色都在生产环境真合成一遍。

为什么要逐个验：复刻音色**绑定 target_model**，注册表里任何一个 voice_id 写错、
或者模型名与音色不匹配，那个角色就是"完全没声音"——而这种故障不会报错，只会静默降级成
纯文本。所以每次改动音色后都应该跑一次全量。

用法：
    python scripts/verify_pet_voices_prod.py            # 全部 13 个
    python scripts/verify_pet_voices_prod.py --only ayaka,hu_tao
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "backend/lianyu-service/src/main/resources/pet-voices.json"

PROBE = '''
import json, os, time, urllib.request

BASE = "http://127.0.0.1:8001"
KEY = os.environ.get("DSK", "")
MODEL = os.environ["MODEL"]
VOICES = json.loads(os.environ["VOICES"])
TEXT = "你好呀，我在这里。"
SLEEP_BETWEEN = 0.4   # 连发 13 个合成请求容易撞上上游限流/DNS 抖动，放缓一点


def once(vid):
    body = json.dumps({"userId": 0, "apiKey": KEY, "model": MODEL,
                       "voiceId": vid, "text": TEXT}).encode()
    req = urllib.request.Request(
        BASE + "/agent/tts/stream", data=body,
        headers={"Content-Type": "application/json", "X-User-Id": "0"}, method="POST")
    total = 0
    with urllib.request.urlopen(req, timeout=180) as r:
        while True:
            buf = r.read(65536)
            if not buf:
                break
            total += len(buf)
    return total


bad = []
for pet, vid in VOICES.items():
    result = 0
    note = ""
    try:
        result = once(vid)
        if result <= 0:
            # 0 字节可能是上游瞬断（DNS/限流），隔一下再试一次，避免把抖动误判成音色坏了
            time.sleep(1.0)
            result = once(vid)
            note = " (重试后)"
    except Exception as exc:
        bad.append(pet)
        print("  %-12s FAIL %s: %s" % (pet, type(exc).__name__, str(exc)[:100]))
        time.sleep(SLEEP_BETWEEN)
        continue
    if result <= 0:
        bad.append(pet)
        print("  %-12s NO_AUDIO bytes=0%s" % (pet, note))
    else:
        print("  %-12s OK       bytes=%d%s" % (pet, result, note))
    time.sleep(SLEEP_BETWEEN)
print("BAD=" + (",".join(bad) if bad else "none"))
'''


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", help="逗号分隔的 pet_id 子集")
    args = ap.parse_args()

    reg = json.loads(REGISTRY.read_text(encoding="utf-8"))
    model = reg["model"]
    voices = dict(reg["voices"])
    only = {p.strip() for p in args.only.split(",") if p.strip()}
    if only:
        voices = {k: v for k, v in voices.items() if k in only}

    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    sftp = c.open_sftp()
    with sftp.file("/tmp/verify_pet_voices.py", "w") as f:
        f.write(PROBE)
    sftp.close()

    payload = json.dumps(voices, ensure_ascii=False).replace('"', '\\"')
    cmd = ("set -a; . /opt/lianyu/.env; set +a; "
           "docker cp /tmp/verify_pet_voices.py lianyu-agent:/tmp/verify_pet_voices.py >/dev/null && "
           f'docker exec -e DSK="$DASHSCOPE_API_KEY" -e MODEL="{model}" -e VOICES="{payload}" '
           "lianyu-agent python -X utf8 /tmp/verify_pet_voices.py")
    _, out, _ = c.exec_command(cmd, timeout=900, get_pty=True)
    text = out.read().decode("utf-8", "replace")
    dst = ROOT / "artifacts" / "_voice_verify.txt"
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(f"model={model}\n{text}", encoding="utf-8")
    c.close()

    bad = [ln for ln in text.splitlines() if "FAIL" in ln or "NO_AUDIO" in ln]
    print(f"model={model}  音色数={len(voices)}")
    for line in text.splitlines():
        if line.startswith("  "):
            print(line)
    print("结论:", "全部可用" if not bad else f"{len(bad)} 个有问题")
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    main()
