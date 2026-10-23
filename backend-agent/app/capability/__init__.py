"""桌面能力层（capability layer）。

自研替代外借的 AgentAssistant MCP 引擎（`computer_task` 粗粒度黑盒）。

| 模块 | 职责 |
|---|---|
| `specs` | 能力清单与 schema（单一事实来源），导入即注册 |
| `budget` | 单任务的步数/视觉帧/墙钟/连续失败四重上限 |
| `policy` | Python 侧预过滤（角色上限 + 来源护栏）；权威判定在 Electron |
| `desktop_channel` | Python → Java 内网 → STOMP → Electron 的唯一通道（HMAC、fail-closed） |
| `handlers` | 统一执行器：预算 → 策略 → 下发 → 结果组织（错误码翻成行动建议） |

红线：
- 模型调用只在 Python；Electron 只做采集与输入注入，不做判断；
- 图片只进视觉模型，永不进文本上下文，也不落盘；
- 任何失败都返回可执行的说明字符串，不抛异常。
"""

from __future__ import annotations

from app.capability import budget, desktop_channel, handlers, policy
from app.capability.specs import (
    DESKTOP_SPECS,
    DESKTOP_TOOL_NAMES,
    capability_names,
    register_desktop_capabilities,
)

# 导入即注册（总开关在 register_desktop_capabilities 内判定）
register_desktop_capabilities()

__all__ = [
    "DESKTOP_SPECS",
    "DESKTOP_TOOL_NAMES",
    "budget",
    "capability_names",
    "desktop_channel",
    "handlers",
    "policy",
    "register_desktop_capabilities",
]
