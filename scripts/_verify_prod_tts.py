#!/usr/bin/env python3
"""部署后验证：在生产 agent 容器里用生产 key + 新注册的 CosyVoice 音色跑 TTS。

验三件事：
  1) /agent/tts/synthesize  整句合成出的是真 WAV（RIFF 头 + 字节数）
  2) /agent/tts/stream      流式端点能逐块吐 PCM，且首包延迟可接受
  3) 换一个不存在的音色      必须降级（204 / 0 块），不能 500

只读 + 一次性调用，不写库。音色用生产 key 注册出来的 klee。
"""
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
KLEE = "cosyvoice-v3-flash-lyklee-d59eb1c55dba454ea1e9d5939a68c0d3"
TEXT = "你好，我是可莉，今天也要一起去冒险吗？"

PROBE = f'''
import json, os, time, urllib.request, urllib.error

BASE = "http://127.0.0.1:8001"
KEY = os.environ.get("DSK", "")
VOICE = "{KLEE}"
TEXT = "{TEXT}"


def post(path, body):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(body).encode(),
        headers={{"Content-Type": "application/json", "X-User-Id": "0"}}, method="POST")
    return urllib.request.urlopen(req, timeout=120)


print("key present =", bool(KEY), "len =", len(KEY))

print("\\n1) 整句合成（生产 key + 新音色）")
try:
    r = post("/agent/tts/synthesize", {{"userId": 0, "apiKey": KEY, "voiceId": VOICE, "text": TEXT}})
    if r.status == 204:
        print("   FAIL 204 无音频")
    else:
        data = r.read()
        print("   status=%s mime=%s bytes=%d magic=%s" % (
            r.status, r.headers.get("Content-Type"), len(data), data[:4].hex()))
except urllib.error.HTTPError as e:
    print("   FAIL http=%s %s" % (e.code, e.read()[:200]))
except Exception as e:
    print("   FAIL %s: %s" % (type(e).__name__, e))

print("\\n2) 流式合成")
try:
    t0 = time.perf_counter()
    first = None
    chunks = 0
    total = 0
    r = post("/agent/tts/stream", {{"userId": 0, "apiKey": KEY, "voiceId": VOICE, "text": TEXT}})
    print("   status=%s mime=%s audio-mime=%s rate=%s" % (
        r.status, r.headers.get("Content-Type"),
        r.headers.get("X-Audio-Mime"), r.headers.get("X-Audio-Sample-Rate")))
    while True:
        buf = r.read(8192)
        if not buf:
            break
        if first is None:
            first = (time.perf_counter() - t0) * 1000
        chunks += 1
        total += len(buf)
    print("   首包=%s ms  块数=%d  字节=%d" % (
        None if first is None else round(first, 1), chunks, total))
except urllib.error.HTTPError as e:
    print("   FAIL http=%s %s" % (e.code, e.read()[:200]))
except Exception as e:
    print("   FAIL %s: %s" % (type(e).__name__, e))

print("\\n3) 不存在的音色必须降级（不能 500）")
bogus = "cosyvoice-v3-flash-doesnotexist-0000000000000000000000000000"
try:
    r = post("/agent/tts/synthesize", {{"userId": 0, "apiKey": KEY, "voiceId": bogus, "text": TEXT}})
    print("   整句: status=%s（204 = 正常降级）" % r.status)
except urllib.error.HTTPError as e:
    print("   整句: http=%s（4xx/5xx 都不该出现）" % e.code)
try:
    r = post("/agent/tts/stream", {{"userId": 0, "apiKey": KEY, "voiceId": bogus, "text": TEXT}})
    n = 0
    while True:
        buf = r.read(8192)
        if not buf:
            break
        n += len(buf)
    print("   流式: status=%s 字节=%d（0 = 正常降级）" % (r.status, n))
except urllib.error.HTTPError as e:
    print("   流式: http=%s（应为 200 + 0 字节）" % e.code)
'''


def load_env() -> dict:
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    return env


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    sftp = c.open_sftp()
    with sftp.file("/tmp/tts_prod_probe.py", "w") as f:
        f.write(PROBE)
    sftp.close()

    # 必须先在远端 shell 里 source .env，否则 $DASHSCOPE_API_KEY 是空的
    # （第一次跑就踩到：key present=False → 全 204，看着像"没声音"）
    cmd = ("set -a; . /opt/lianyu/.env; set +a; "
           "docker cp /tmp/tts_prod_probe.py lianyu-agent:/tmp/tts_prod_probe.py >/dev/null && "
           'docker exec -e DSK="$DASHSCOPE_API_KEY" lianyu-agent python /tmp/tts_prod_probe.py')
    _, out, _ = c.exec_command(cmd, timeout=600, get_pty=True)
    text = out.read().decode("utf-8", errors="replace")
    code = out.channel.recv_exit_status()
    sys.stdout.buffer.write(text.encode("utf-8", errors="replace"))
    print(f"\nexit={code}")
    c.close()


if __name__ == "__main__":
    main()
