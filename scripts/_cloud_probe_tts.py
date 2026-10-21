#!/usr/bin/env python3
"""生产环境 TTS 落地验证：在 agent 容器内用真实上游 key 调 /agent/tts/synthesize。

不断言"必须成功"——key 对 qwen3-tts-vc-* 的合成能力已知缺失（404）。
这里只回答两个事实问题：
  1. 部署后的 Python TTS 模块能不能真的产出音频字节（非 VC 模型）
  2. 已知缺能力的那条路径，返回的是"可降级的 204"还是 5xx
"""
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]

PROBE = r'''
import json, os, urllib.request, urllib.error

KEY = os.environ.get("DSK", "")


def probe(model, voice, tag):
    body = json.dumps({
        "userId": 0, "apiKey": KEY, "model": model, "voiceId": voice,
        "text": "你好，我是林晚，今天也想和你说说话。", "languageType": "Chinese",
    }).encode()
    req = urllib.request.Request(
        "http://127.0.0.1:8001/agent/tts/synthesize", data=body,
        headers={"Content-Type": "application/json", "X-User-Id": "0"}, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=90)
        data = r.read()
        print(f"{tag} model={model} voice={voice} status={r.status} "
              f"mime={r.headers.get('Content-Type')} bytes={len(data)} "
              f"magic={data[:4].hex() if data else 'EMPTY'}")
    except urllib.error.HTTPError as e:
        print(f"{tag} model={model} voice={voice} HTTP {e.code} {e.read()[:160]}")
    except Exception as e:
        print(f"{tag} model={model} voice={voice} ERR {type(e).__name__}: {e}")


probe("qwen3-tts-flash", "Cherry", "A")
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
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    sftp = c.open_sftp()
    with sftp.file("/tmp/tts_probe.py", "w") as f:
        f.write(PROBE)
    sftp.close()

    cmd = (
        "set -a; . /opt/lianyu/.env; set +a; "
        "docker cp /tmp/tts_probe.py lianyu-agent:/tmp/tts_probe.py >/dev/null && "
        'docker exec -e DSK="$DASHSCOPE_API_KEY" lianyu-agent python /tmp/tts_probe.py'
    )
    _, out, _ = c.exec_command(cmd, timeout=300, get_pty=True)
    text = out.read().decode("utf-8", errors="replace")
    out.channel.recv_exit_status()
    sys.stdout.buffer.write(text.encode("utf-8", errors="replace"))
    c.close()
    print("\nTTS_PROBE_DONE")


if __name__ == "__main__":
    main()
