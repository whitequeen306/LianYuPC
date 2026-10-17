"""单聊流式管道的端到端冒烟检查（容器内运行，不依赖真实 LLM）。

用法（agent 容器内）：
    docker cp scripts/agent-stream-smoke.py lianyu-agent:/tmp/smoke.py
    docker exec lianyu-agent python /tmp/smoke.py

原理：在 127.0.0.1:9999 起一个模拟 OpenAI 的 SSE 上游，再打本服务的
`/agent/chat-stream`，断言输出的帧符合 Java 契约：
: connected → {"content"}* →（可选 {"replace"}）→ [DONE]

为什么需要它：本地没有可用的真实 API Key（vault 里是 Jasypt 密文，只有 Java 能解），
所以用桩上游验证「解析 → 语言门 → 契约帧」这条链，避免接线错误长期潜伏。
"""

import json
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

AGENT_PORT = 8001
STUB_PORT = 9999


MODE = {"value": "zh"}


class Stub(BaseHTTPRequestHandler):
    """模拟上游 /chat/completions。

    payload 里 `mode` 控制返回内容：
    - "zh"（默认）：中文三帧
    - "en"：英文（用于触发语言门）
    - "error"：500
    """

    def do_POST(self):
        length = int(self.headers.get("content-length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")

        # 语言门纠错请求是阻塞调用（无 stream:true），返回整段 JSON
        if not body.get("stream"):
            text = "今晚有点想你"
            payload = json.dumps({"choices": [{"message": {"content": text}}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        mode = MODE["value"]
        if mode == "error":
            self.send_response(500)
            self.end_headers()
            return

        tokens = ("I ", "miss ", "you") if mode == "en" else ("我", "在", "呢")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for token in tokens:
            frame = {"choices": [{"delta": {"content": token}}]}
            self.wfile.write(f"data: {json.dumps(frame)}\n\n".encode())
            self.wfile.flush()
            time.sleep(0.01)
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, *args):
        pass


def post(payload):
    req = urllib.request.Request(
        f"http://127.0.0.1:{AGENT_PORT}/agent/chat-stream",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "X-User-Id": str(payload.get("userId", 1))},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode()


def frames(raw):
    return [line for line in raw.splitlines() if line.strip()]


def base_payload(**overrides):
    payload = {
        "userId": 1,
        "system": "你是温柔的角色",
        "messages": [{"role": "user", "content": "在吗"}],
        "textBaseUrl": f"http://127.0.0.1:{STUB_PORT}/v1",
        "textApiKey": "stub-key",
        "textModel": "stub-model",
        "characterId": 7,
    }
    payload.update(overrides)
    return payload


def main() -> int:
    server = HTTPServer(("127.0.0.1", STUB_PORT), Stub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    failures = []

    def check(name, condition, detail=""):
        print(f"  [{'PASS' if condition else 'FAIL'}] {name} {detail}")
        if not condition:
            failures.append(name)

    print("场景 1：普通流式")
    f1 = frames(post(base_payload()))
    check("首帧心跳", f1[0].startswith(": connected"), f1[0])
    check("内容增量 3 帧", sum(1 for f in f1 if '"content"' in f) == 3)
    check("末帧 [DONE]", f1[-1] == "data: [DONE]", f1[-1])
    check("无 replace", not any('"replace"' in f for f in f1))

    print("场景 2：期望中文 + 上游中文（不应纠错）")
    f2 = frames(post(base_payload(expectedLanguage="zh")))
    check("无 replace", not any('"replace"' in f for f in f2))

    print("场景 3：期望中文 + 上游英文（应纠错并 replace）")
    MODE["value"] = "en"
    f3 = frames(post(base_payload(expectedLanguage="zh")))
    replace_frames = [f for f in f3 if '"replace"' in f]
    check("有 1 个 replace 帧", len(replace_frames) == 1, str(replace_frames[:1]))
    check("replace 内容为中文", "今晚有点想你" in (replace_frames[0] if replace_frames else ""))
    check("末帧 [DONE]", f3[-1] == "data: [DONE]")

    print("场景 4：上游 500（应 error + [DONE]）")
    MODE["value"] = "error"
    f4 = frames(post(base_payload()))
    check("有 error 帧", any('"error"' in f for f in f4), str(f4[:2]))
    check("末帧 [DONE]", f4[-1] == "data: [DONE]")

    server.shutdown()
    print()
    if failures:
        print(f"FAILED: {failures}")
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
