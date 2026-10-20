"""通用阻塞文本回合：`/agent/text/complete`。

用于「Java 已组装好 system + user 文本、只差一次模型调用」的链路
（日记、朋友圈发帖、朋友圈评论）。与 ProactiveTurn 的区别：
- 没有历史数组、没有工具、没有语言门（这些链路的输出是 JSON 或短文案，
  由 Java 侧解析与校验，语言门不适用）
- 单次阻塞调用，返回原始文本，解析仍归 Java（保持行为不变）

这样迁移的收益：model 调用、熔断、隔舱、显式超时统一走 Python 网关；
风险很低，因为 prompt 与解析都留在原处。
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import get_settings
from app.gateway import ModelGateway, ModelGatewayError
from app.resilience import GatewayError, GatewayRejectedError

logger = logging.getLogger(__name__)


async def complete_text(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    system: str,
    user_text: str,
    history: list[dict[str, str]] | None = None,
    temperature: float,
    max_tokens: int | None = None,
    provider: str | None = None,
    lane_name: str = "background",
) -> dict[str, Any]:
    """返回 {"content": str} 或 {"error": str}。"""
    settings = get_settings()
    messages: list[dict[str, str]] = []
    if system:
        messages.append({"role": "system", "content": system})
    for item in (history or []):
        role = (item.get("role") or "user").lower()
        messages.append({"role": role, "content": item.get("content") or ""})
    messages.append({"role": "user", "content": user_text})

    try:
        result = await gateway.chat_messages(
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens or settings.proactive_max_tokens,
            provider=provider,
            lane_name=lane_name,
        )
    except GatewayRejectedError:
        return {"error": "upstream_circuit_open"}
    except GatewayError as exc:
        logger.warning("text complete failed: %s", exc)
        return {"error": str(exc)}
    except Exception as exc:  # noqa: BLE001 - 任何异常都返回错误码，由 Java 回退
        logger.warning("text complete unexpected error: %s", exc)
        return {"error": "unexpected_error"}

    content = result.content or ""
    if not content.strip():
        return {"error": "empty_content"}
    return {"content": content}


__all__ = ["complete_text"]
