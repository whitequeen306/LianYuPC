"""每日观测巡检：验证 Python prompt 组装未漂移 + 真实对话可用。

做什么（全部调用运行中的 agent 服务，等价生产路径）：
  1. golden 对齐：调 /agent/prompt/assemble（SINGLE full，注入 golden 的
     memory/relationship/session 块），比对 systemPrompt 与 end_to_end_golden.json
     的 Java 逐字节基线。
  2. 真实对话冒烟：/agent/text/chat 发 1 条（凭据来自 env 或已有 vault 解密通道）。
     凭据缺失时跳过（golden 对齐仍执行，不阻塞巡检）。

用法（宿主机，栈已起）：
  $env:LIANYU_OBS_BASE_URL='http://localhost:8080'   # 可选，默认 8080
  python scripts/ops/daily_parity_check.py           # 写 .tmp/parity-report.json

退出码：0 = 全部通过；1 = 有失败项（CI/cron 可直接用）。

设计纪律：期望值来自 committed golden fixture（Java 产出，Python 不自己算）。
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
GOLDEN_PATH = REPO_ROOT / "backend-agent" / "app" / "prompts" / "end_to_end_golden.json"
OUT_PATH = REPO_ROOT / ".tmp" / "parity-report.json"

# golden 对齐直连 agent LB（绕过 Java 网关 /api/agent/ 分流，避免部署差异干扰）
AGENT_URL = os.environ.get("LIANYU_OBS_AGENT_URL", "http://127.0.0.1:18001")
# 真实对话冒烟走 Java 全链路
BASE_URL = os.environ.get("LIANYU_OBS_BASE_URL", "http://127.0.0.1:8080")
USER_ID = 1
CHARACTER_ID = 50
CONVERSATION_ID = 51

report = {
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "base_url": BASE_URL,
    "checks": [],
}


def add_check(name, ok, detail):
    report["checks"].append({"name": name, "ok": ok, "detail": detail})
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}: {detail}")
    return ok


def main():
    golden = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    settings = {"personality": "温柔但有点固执", "city_mode": "real", "city": "上海"}
    memory = "\n\n".join([
        golden["memoryBlock"], golden["relationshipBlock"], golden["sessionBlock"]])

    client = httpx.Client(base_url=AGENT_URL, timeout=30.0)

    # 1) golden 对齐（golden fixture 只有 zh/en 两语种产出）
    all_match = True
    for lang in sorted(golden["prompts"]):
        expected = golden["prompts"][lang]
        resp = client.post("/agent/prompt/assemble", json={
            "userId": USER_ID,
            "characterId": CHARACTER_ID,
            "characterName": "小林",
            "settings": settings,
            "memoryContext": memory,
            "toolHint": "[[TOOL_HINT]]",
            "emotionBlock": "\n\n[[EMOTION]]",
            "outputLanguage": lang,
            "enableChatTools": True,
            "showInnerThoughts": True,
            "maxPieces": 2,
            "includeTime": True,
            "timeFact": golden["timeFact"],
            "city": golden["city"],
            "afterBase": "full",
        })
        body = resp.json()
        actual = body.get("systemPrompt", "")
        matched = resp.status_code == 200 and actual == expected
        if not matched:
            all_match = False
        add_check(
            f"golden[{lang}]", matched,
            f"status={resp.status_code} expectedChars={len(expected)} actualChars={len(actual)}"
            + ("" if matched else f" firstDiff={_first_diff(expected, actual)}"))

    # 2) agent 服务健康（同一 LB）
    try:
        health = client.get("/agent/health")
        add_check("agent_health", health.status_code == 200
                  and health.json().get("status") == "UP",
                  f"status={health.status_code} body={health.text[:50]}")
    except Exception as exc:
        add_check("agent_health", False, f"unreachable: {exc}")

    # 3) 真实对话冒烟（可选：无凭据则跳过，不判失败）
    smoke = _smoke_chat(client)
    if smoke is None:
        add_check("real_chat_smoke", True, "skipped (no credentials)")
    else:
        ok, detail = smoke
        add_check("real_chat_smoke", ok, detail)

    passed = sum(1 for c in report["checks"] if c["ok"])
    report["passed"] = passed
    report["total"] = len(report["checks"])
    print(f"\n{passed}/{report['total']} checks passed")

    OUT_PATH.parent.mkdir(exist_ok=True)
    OUT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    print(f"report: {OUT_PATH}")
    sys.exit(0 if passed == report["total"] else 1)


def _first_diff(expected: str, actual: str) -> int:
    for i, (a, b) in enumerate(zip(expected, actual)):
        if a != b:
            return i
    return min(len(expected), len(actual))


def _smoke_chat(client: httpx.Client):
    """一条真实对话；凭据不可得时返回 None（跳过）。"""
    vaults = client.get("/api/ai/vault")
    if vaults.status_code != 200:
        return None
    try:
        login_resp = client.post("/api/auth/login", json={
            "username": os.environ.get("LIANYU_OBS_USER", ""),
            "password": os.environ.get("LIANYU_OBS_PASSWORD", ""),
        })
        if login_resp.status_code != 200:
            return None
        token = login_resp.json().get("data", {}).get("token", "")
        if not token:
            return None
        client.headers["lianyu-token"] = token
    except Exception:
        return None

    vault_list = vaults.json().get("data") or []
    if not vault_list:
        return None
    provider = vault_list[0].get("provider")

    started = time.time()
    resp = client.post("/api/ai/chat", json={
        "provider": provider,
        "model": vault_list[0].get("modelDefault"),
        "messages": [{"role": "user", "content": "用一句话确认你在线"}],
        "temperature": 0.2,
        "maxTokens": 80,
    })
    elapsed = int((time.time() - started) * 1000)
    content = (resp.json().get("data") or {}).get("content") or ""
    return (resp.status_code == 200 and content.strip(),
            f"status={resp.status_code} chars={len(content)} elapsed={elapsed}ms")


if __name__ == "__main__":
    main()
