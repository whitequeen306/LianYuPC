#!/usr/bin/env python3
"""部署后生产验证（在服务器上跑，走完整 Java 链路）。

覆盖：
  1. 登录（验证码从 redis 取）
  2. 复用已有 vault / 角色 / 会话，跑 6 条阻塞对话（LangGraph 回合）
  3. 1 条 SSE 流式对话（帧契约：心跳 → content* → [DONE]）
  4. /api/community/push-catchup → 期望 200（本次新增垫片）
  5. 未知路由 → 期望 404（本次修复：原来 500）

只读 + 追加对话消息，不删任何数据。
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
USERNAME = "hjkl"
PASSWORD = "1216qwer"
ENV_FILE = "/opt/lianyu/.env"

TURNS = [
    "在吗，说句话给我听。",
    "今天有点累，随便聊两句吧。",
    "你晚饭吃了什么？",
    "外面在下雨，你那边呢？",
    "还记得我上次说想学做饭吗？",
    "最后一句，抱抱我。",
]


def load_env() -> dict:
    env = {}
    try:
        with open(ENV_FILE, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k.strip()] = v.strip().strip('"').strip("'")
    except FileNotFoundError:
        pass
    return env


def http(method, path, body=None, token=None, timeout=180):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["lianyu-token"] = token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", errors="replace")
            try:
                return r.status, json.loads(raw)
            except json.JSONDecodeError:
                return r.status, {"raw": raw[:300]}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw[:300]}
    except Exception as e:
        return -1, {"error": str(e)[:300]}


def redis_get(key: str, env: dict) -> str:
    pwd = env.get("REDIS_PASSWORD", "")
    out = subprocess.run(
        ["docker", "exec", "lianyu-redis", "redis-cli", "-a", pwd, "GET", key],
        capture_output=True, text=True, timeout=30,
    )
    return (out.stdout or "").strip()


def login(env: dict) -> str:
    st, body = http("GET", "/api/auth/captcha")
    cid = body["data"]["captchaId"]
    ans = int(redis_get("captcha:" + cid, env))
    st, body = http("POST", "/api/auth/login", {
        "username": USERNAME, "password": PASSWORD,
        "captcha": {"captchaId": cid, "captchaAnswer": ans}})
    if st != 200 or body.get("code") != 200:
        raise RuntimeError(f"login failed: {st} {json.dumps(body, ensure_ascii=False)[:200]}")
    return body["data"]["token"]


def pick_vault(token):
    st, body = http("GET", "/api/ai/vault", token=token)
    if st != 200 or body.get("code") != 200:
        return None, f"vault list failed {st} {str(body)[:150]}"
    items = body.get("data") or []
    for v in items:
        if (v.get("purpose") or "text") == "text" and v.get("provider"):
            return v, None
    return None, f"no text vault in {len(items)} entries"


def pick_conversation(token, provider):
    st, body = http("GET", "/api/conversation", token=token)
    if st == 200 and body.get("code") == 200:
        items = body.get("data") or []
        singles = [c for c in items if (c.get("mode") or "SINGLE") == "SINGLE"]
        if singles:
            return singles[0], None
    # 没有现成会话：建角色 + 会话
    st, body = http("POST", "/api/character", {
        "name": "林晚",
        "settings": {"personality": "温柔安静，说话慢，喜欢雨声和旧书。",
                     "speakingStyle": "轻柔、简短、口语化",
                     "city_mode": "real", "city": "上海", "showInnerThoughts": False},
        "promptTemplate": "你是林晚。只输出说出口的话，1-3 句，口语化，不要写动作描写。",
    }, token=token)
    if st != 200 or body.get("code") != 200:
        return None, f"character create failed {st} {str(body)[:150]}"
    ch = body["data"]
    st, body = http("POST", "/api/conversation",
                    {"characterId": ch["id"], "mode": "SINGLE", "title": "部署验证"},
                    token=token)
    if st != 200 or body.get("code") != 200:
        return None, f"conversation create failed {st} {str(body)[:150]}"
    return body["data"], None


def sse_turn(token, conv_id, provider, model):
    """流式回合：逐行读，统计帧并取正文。"""
    payload = json.dumps({
        "provider": provider, "model": model,
        "content": "用一句话说说你现在的心情。", "temperature": 0.8,
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE}/api/conversation/{conv_id}/messages/stream", data=payload,
        headers={"Content-Type": "application/json", "lianyu-token": token}, method="POST")
    frames, content, has_done, status = [], [], False, None
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            status = r.status
            for raw in r:
                line = raw.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                frames.append(line)
                if line.startswith("data:"):
                    body = line[5:].strip()
                    if body == "[DONE]":
                        has_done = True
                        continue
                    try:
                        obj = json.loads(body)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(obj, dict):
                        content.append(obj.get("content") or obj.get("replace") or "")
    except urllib.error.HTTPError as e:
        return {"status": e.code, "error": e.read().decode("utf-8", errors="replace")[:200]}
    except Exception as e:
        return {"status": -1, "error": str(e)[:200]}
    return {
        "status": status,
        "frames": len(frames),
        "has_content_frame": any('"content"' in f for f in frames),
        "has_replace_frame": any('"replace"' in f for f in frames),
        "has_done": has_done,
        "chars": len("".join(content)),
        "preview": "".join(content)[:60],
        "ms": int((time.time() - started) * 1000),
        "head": frames[:2],
        "tail": frames[-1:],
    }


def main() -> int:
    env = load_env()
    report = {"turns": []}
    token = login(env)
    print("LOGIN_OK", flush=True)

    vault, err = pick_vault(token)
    if not vault:
        print("VAULT_MISSING", err)
        return 2
    provider = vault["provider"]
    model = vault.get("modelDefault") or "qwen-plus"
    report["vault"] = {"id": vault.get("id"), "provider": provider, "model": model}
    print("VAULT_OK", report["vault"], flush=True)

    conv, err = pick_conversation(token, provider)
    if not conv:
        print("CONV_MISSING", err)
        return 3
    conv_id = conv["id"]
    report["conversation"] = {"id": conv_id, "characterId": conv.get("characterId")}
    print("CONVERSATION_OK", conv_id, flush=True)

    # ── 阻塞回合 ──
    for i, text in enumerate(TURNS, 1):
        started = time.time()
        st, body = http("POST", f"/api/conversation/{conv_id}/messages", {
            "provider": provider, "model": model,
            "content": text, "temperature": 0.8,
        }, token=token)
        msg = (body.get("data") or {}) if st == 200 else {}
        content = msg.get("content") or ""
        report["turns"].append({
            "i": i, "status": st, "code": body.get("code"), "chars": len(content),
            "ms": int((time.time() - started) * 1000),
            "preview": content[:40],
            "error": None if st == 200 else (body.get("message") or str(body)[:150]),
        })
        print(f"TURN {i:02d} {'OK ' if content.strip() else 'EMPTY'} {len(content):3d} chars "
              f"{report['turns'][-1]['ms']}ms {content[:30]!r}", flush=True)

    # ── 流式回合 ──
    stream = sse_turn(token, conv_id, provider, model)
    report["stream"] = stream
    print("STREAM", json.dumps(stream, ensure_ascii=False), flush=True)

    # ── 路由行为 ──
    st_pc, body_pc = http("POST", "/api/community/push-catchup", {}, token=token)
    st_un, _ = http("GET", "/api/definitely-not-a-route", token=token)
    report["routes"] = {"push_catchup": {"status": st_pc, "code": body_pc.get("code")},
                        "unknown": {"status": st_un}}
    print("ROUTES", json.dumps(report["routes"], ensure_ascii=False), flush=True)

    ok = sum(1 for t in report["turns"] if t["chars"] > 0)
    report["summary"] = {
        "total": len(report["turns"]), "non_empty": ok,
        "failed": sum(1 for t in report["turns"] if t["status"] != 200),
        "stream_ok": bool(stream.get("has_done") and stream.get("chars", 0) > 0),
        "push_catchup_200": st_pc == 200,
        "unknown_404": st_un == 404,
    }
    print("SUMMARY", json.dumps(report["summary"], ensure_ascii=False), flush=True)
    with open("/tmp/prod_verify_report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    good = (ok == len(report["turns"]) and report["summary"]["stream_ok"]
            and report["summary"]["push_catchup_200"] and report["summary"]["unknown_404"])
    return 0 if good else 6


if __name__ == "__main__":
    sys.exit(main())
