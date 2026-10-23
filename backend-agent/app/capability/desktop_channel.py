"""桌面能力通道：Python → Java 内网 → STOMP → Electron 的唯一下发路径。

方向是**单向的**：Python 发起、Electron 执行、结果原路回灌到本次 HTTP 响应。
之所以不让 Electron 直连 Python：

- backend-agent 在部署上「仅内网、不对外暴露端口」（docker-compose 注释即契约）；
- `agent` + `agent2` 双实例经 `agent-lb` 轮询，没有会话粘性，长连接会飘；
- 客户端出网被 `apiOrigin` / host 白名单约束，反向开洞等于新增信任边界。

红线：
1. **fail-closed** —— 没有共享密钥就不发请求（绝不裸奔）；
2. **显式超时** —— 每步都有 `timeout_ms`，到点就失败，绝不「等别人」；
3. **不抛异常** —— 通道失败一律映射成 `error_code`，让模型自然解释。
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.config import get_settings
from app.http_client import shared_client

logger = logging.getLogger(__name__)

EXEC_PATH = "/internal/agent/desktop/exec"
PROGRESS_PATH = "/internal/agent/desktop/progress"
ABORT_PATH = "/internal/agent/desktop/abort"

# 通道自身的错误码（与 Electron 侧的 10 个业务错误码区分开）
ERR_CHANNEL_NOT_CONFIGURED = "channel_not_configured"
ERR_CHANNEL_TIMEOUT = "channel_timeout"
ERR_CHANNEL_UNREACHABLE = "channel_unreachable"
ERR_CHANNEL_BAD_RESPONSE = "channel_bad_response"


@dataclass
class DesktopExecResult:
    ok: bool
    text: str = ""
    data: dict[str, Any] = field(default_factory=dict)
    images: list[dict[str, Any]] = field(default_factory=list)
    error_code: str = ""
    elapsed_ms: int = 0

    @property
    def has_image(self) -> bool:
        return bool(self.images)


def sign_body(secret: str, timestamp: str, body: bytes) -> str:
    """`sha256` HMAC over `{ts}.{body}`；Java 侧逐字节复算，签名不符即 401。"""
    mac = hmac.new(secret.encode("utf-8"), timestamp.encode("utf-8") + b"." + body, hashlib.sha256)
    return "sha256=" + mac.hexdigest()


def _decode(payload: Any) -> DesktopExecResult:
    if not isinstance(payload, dict):
        return DesktopExecResult(ok=False, error_code=ERR_CHANNEL_BAD_RESPONSE)
    images = payload.get("images")
    data = payload.get("data")
    text = payload.get("text") or payload.get("content") or ""
    if not text and isinstance(data, dict) and data:
        text = json.dumps(data, ensure_ascii=False)
    return DesktopExecResult(
        ok=bool(payload.get("ok")),
        text=str(text or ""),
        data=data if isinstance(data, dict) else {},
        images=images if isinstance(images, list) else [],
        error_code=str(payload.get("errorCode") or payload.get("error") or ""),
        elapsed_ms=int(payload.get("elapsedMs") or 0),
    )


class DesktopChannel:
    """一次任务内复用的通道对象（无状态，可安全共享）。"""

    def __init__(self, base_url: str | None = None, token: str | None = None) -> None:
        settings = get_settings()
        self._base_url = (base_url if base_url is not None else settings.internal_base_url).rstrip("/")
        self._token = token if token is not None else settings.internal_token
        self._window = settings.internal_signature_window_seconds

    @property
    def configured(self) -> bool:
        return bool(self._base_url and self._token)

    def _headers(self, user_id: int, body: bytes) -> dict[str, str]:
        ts = str(int(time.time()))
        return {
            "Content-Type": "application/json",
            "X-User-Id": str(user_id),
            "X-Agent-Ts": ts,
            "X-Agent-Sig": sign_body(self._token, ts, body),
        }

    async def _post(self, path: str, user_id: int, payload: dict[str, Any],
                    timeout_s: float) -> tuple[int, Any]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        client = shared_client("internal")
        resp = await client.post(
            f"{self._base_url}{path}",
            content=body,
            headers=self._headers(user_id, body),
            timeout=httpx.Timeout(connect=5.0, read=timeout_s, write=10.0, pool=5.0),
        )
        if resp.status_code == 204 or not resp.content:
            return resp.status_code, None
        return resp.status_code, resp.json()

    async def exec(  # noqa: A003 - 与 Java 侧方法名对齐
        self,
        *,
        user_id: int,
        session_id: str,
        step_id: str,
        tool: str,
        arguments: dict[str, Any],
        timeout_ms: int,
        evidence: str,
        danger: str = "",
        actor: dict[str, Any] | None = None,
    ) -> DesktopExecResult:
        if not self.configured:
            logger.error("desktop channel not configured (missing internal_base_url/token)")
            return DesktopExecResult(ok=False, error_code=ERR_CHANNEL_NOT_CONFIGURED)
        payload = {
            "userId": user_id,
            "sessionId": session_id,
            "stepId": step_id,
            "tool": tool,
            "args": arguments,
            "timeoutMs": int(timeout_ms),
            "danger": danger,
            "evidence": evidence,
            "actor": actor or {},
        }
        started = time.monotonic()
        try:
            status, body = await self._post(EXEC_PATH, user_id, payload, timeout_ms / 1000 + 10)
        except httpx.TimeoutException:
            logger.warning("desktop exec timeout: tool=%s step=%s", tool, step_id)
            return DesktopExecResult(ok=False, error_code=ERR_CHANNEL_TIMEOUT)
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning("desktop exec transport failed: tool=%s err=%s", tool, exc)
            return DesktopExecResult(ok=False, error_code=ERR_CHANNEL_UNREACHABLE)

        if status >= 500:
            logger.warning("desktop exec http %s: tool=%s", status, tool)
            return DesktopExecResult(ok=False, error_code=ERR_CHANNEL_UNREACHABLE)
        result = _decode(body)
        if result.elapsed_ms <= 0:
            result.elapsed_ms = int((time.monotonic() - started) * 1000)
        return result

    async def progress(self, *, user_id: int, session_id: str, step_id: str,
                       code: str, text: str) -> None:
        """进度旁路：失败绝不影响执行（进度丢一条远不如动作失败严重）。"""
        if not self.configured:
            return
        payload = {
            "userId": user_id,
            "sessionId": session_id,
            "stepId": step_id,
            "code": code,
            "text": text,
        }
        try:
            await self._post(PROGRESS_PATH, user_id, payload, 5.0)
        except (httpx.HTTPError, ValueError) as exc:
            logger.debug("desktop progress dropped: %s", exc)

    async def abort(self, *, user_id: int, session_id: str, step_id: str = "") -> None:
        if not self.configured:
            return
        payload = {"userId": user_id, "sessionId": session_id, "stepId": step_id}
        try:
            await self._post(ABORT_PATH, user_id, payload, 5.0)
        except (httpx.HTTPError, ValueError) as exc:
            logger.debug("desktop abort dropped: %s", exc)


_CHANNEL: DesktopChannel | None = None


def get_channel() -> DesktopChannel:
    global _CHANNEL
    if _CHANNEL is None:
        _CHANNEL = DesktopChannel()
    return _CHANNEL


def reset_channel(channel: DesktopChannel | None = None) -> None:
    """测试用：替换/清空单例。"""
    global _CHANNEL
    _CHANNEL = channel


__all__ = [
    "ABORT_PATH",
    "DesktopChannel",
    "DesktopExecResult",
    "ERR_CHANNEL_BAD_RESPONSE",
    "ERR_CHANNEL_NOT_CONFIGURED",
    "ERR_CHANNEL_TIMEOUT",
    "ERR_CHANNEL_UNREACHABLE",
    "EXEC_PATH",
    "PROGRESS_PATH",
    "get_channel",
    "reset_channel",
    "sign_body",
]
