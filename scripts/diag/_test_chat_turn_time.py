#!/usr/bin/env python3
"""Local API smoke: login → single chat → ask current time (Graph + get_current_time)."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = os.environ.get("LIANYU_API", "http://127.0.0.1:8080")
TOKEN_HEADER = "lianyu-token"


def load_dotenv() -> dict[str, str]:
    env_path = ROOT / ".env"
    out: dict[str, str] = {}
    if not env_path.exists():
        return out
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def http(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    if token:
        req.add_header(TOKEN_HEADER, token)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {err_body}") from e
    if not raw:
        return {}
    return json.loads(raw)


def redis_get(key: str, password: str) -> str:
    cmd = ["docker", "exec", "lianyu-redis", "redis-cli"]
    if password:
        cmd += ["-a", password, "--no-auth-warning"]
    cmd += ["GET", key]
    r = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if r.returncode != 0:
        raise RuntimeError(f"redis GET failed: {r.stderr or r.stdout}")
    return (r.stdout or "").strip()


def captcha_answer(password: str) -> tuple[str, int]:
    cap = http("GET", "/api/auth/captcha")
    data = cap.get("data") or {}
    captcha_id = data["captchaId"]
    answer = int(redis_get(f"captcha:{captcha_id}", password))
    return captcha_id, answer


def main() -> int:
    env = load_dotenv()
    redis_pw = env.get("REDIS_PASSWORD", "")

    # Prefer login; register only when LIANYU_TEST_REGISTER=1 (IP daily register limit is low).
    username = os.environ.get("LIANYU_TEST_USER") or env.get("LIANYU_TEST_USER") or "graph_smoke_1784362824"
    password = os.environ.get("LIANYU_TEST_PASSWORD") or env.get("LIANYU_TEST_PASSWORD") or "Test1234"
    do_register = os.environ.get("LIANYU_TEST_REGISTER") == "1"

    captcha_id, answer = captcha_answer(redis_pw)
    captcha = {"captchaId": captcha_id, "captchaAnswer": answer}

    if do_register:
        username = f"graph_smoke_{int(time.time())}"
        print(f"[1] register {username}")
        login = http(
            "POST",
            "/api/auth/register",
            {
                "username": username,
                "password": password,
                "nickname": "GraphSmoke",
                "captcha": captcha,
            },
        )
    else:
        print(f"[1] login as {username}")
        login = http(
            "POST",
            "/api/auth/login",
            {"username": username, "password": password, "captcha": captcha},
        )

    if login.get("code") not in (0, 200, None) and login.get("data") is None:
        # Result uses code=0 for ok in this project — tolerate both shapes
        pass
    data = login.get("data") or login
    token = data.get("token")
    if not token:
        raise RuntimeError(f"no token in login/register response: {login}")
    print(f"    userId={data.get('userId')} token=***")

    print("[2] resolve SINGLE conversation")
    convs = http("GET", "/api/conversation", token=token)
    singles = [
        c
        for c in (convs.get("data") or [])
        if str(c.get("mode", "")).upper() == "SINGLE" and c.get("id")
    ]
    if singles:
        conversation_id = singles[0]["id"]
        character_id = singles[0].get("characterId")
        print(f"    reuse conversationId={conversation_id} characterId={character_id}")
        # Avoid race with cold-open on brand-new conversations.
        time.sleep(1.5)
    else:
        created = http(
            "POST",
            "/api/character",
            {
                "name": "时间测试角色",
                "promptTemplate": (
                    "你是一个贴心助手。当用户询问当前时间、日期或星期几时，"
                    "必须调用 get_current_time 工具后再回答。"
                ),
                "settings": {"city_mode": "real", "city": "上海"},
            },
            token=token,
        )
        character_id = (created.get("data") or {}).get("id")
        if not character_id:
            raise RuntimeError(f"create character failed: {created}")
        print(f"    characterId={character_id}")
        conv = http(
            "POST",
            "/api/conversation",
            {"characterId": character_id, "mode": "SINGLE"},
            token=token,
        )
        conversation_id = (conv.get("data") or {}).get("id")
        if not conversation_id:
            raise RuntimeError(f"create conversation failed: {conv}")
        print(f"    conversationId={conversation_id}")
        print("    waiting for cold-open to settle...")
        time.sleep(5)

    print("[4] send: 现在几点了？")
    t0 = time.time()
    msg = http(
        "POST",
        f"/api/conversation/{conversation_id}/messages",
        {
            "provider": "platform",
            "model": "deepseek-v4-flash",
            "content": "现在几点了？",
        },
        token=token,
    )
    elapsed = time.time() - t0
    reply = (msg.get("data") or {}).get("content") or (msg.get("data") or {}).get("text")
    print(f"    elapsed={elapsed:.1f}s")
    print(f"    reply={reply}")
    print(json.dumps(msg, ensure_ascii=False, indent=2)[:2000])

    if not reply:
        print("FAIL: empty reply", file=sys.stderr)
        return 1
    print("OK: got model reply (check backend logs for ChatTurn / get_current_time)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        raise SystemExit(1)
