"""工具层入口：导入即注册内置工具（local）与桌面能力（desktop）。

能力声明（`supported_tool_names()`）是 Java 侧切 AGENT 的路由依据。

两段注册顺序不能颠倒：
1. `local`：本进程直接执行的工具（时间/天气/记忆/近况）；
2. `desktop`：经 Java 内网通道下发到用户 Electron 执行的 `desk_*`。
   它们**注册**在这里（schema 可用），但**是否给模型**由 `capability_names()` 按
   桌面端在线与否决定 —— 不在线时一个都不下发，避免模型对着不存在的桥反复调用。

仍需 Java 侧实现、Python 尚未支持的：`write_diary`（MQ 入队）。
"""

from app.tools import registry
from app.tools.registry import (
    ToolSpec,
    get_tool,
    names_of_kind,
    resolve_tools,
    supported_tool_names,
    supported_tool_schemas,
)
from app.tools.memory_search_tool import MEMORY_SEARCH_TOOL  # noqa: F401 - 导入即注册
from app.tools.recent_activity_tool import RECENT_ACTIVITY_TOOL  # noqa: F401 - 导入即注册
from app.tools.time_tool import TIME_TOOL  # noqa: F401 - 导入即注册
from app.tools.weather_tool import WEATHER_TOOL  # noqa: F401 - 导入即注册

# 桌面能力（desk_*）：导入即注册，受 `LIANYU_AGENT_DESKTOP_CAPABILITY_ENABLED` 控制
from app.capability import (  # noqa: E402 - 必须在 local 工具之后，避免注册顺序漂移
    DESKTOP_SPECS,
    DESKTOP_TOOL_NAMES,
    capability_names,
    register_desktop_capabilities,
)

__all__ = [
    "DESKTOP_SPECS",
    "DESKTOP_TOOL_NAMES",
    "MEMORY_SEARCH_TOOL",
    "RECENT_ACTIVITY_TOOL",
    "TIME_TOOL",
    "WEATHER_TOOL",
    "ToolSpec",
    "capability_names",
    "get_tool",
    "names_of_kind",
    "register_desktop_capabilities",
    "registry",
    "resolve_tools",
    "supported_tool_names",
    "supported_tool_schemas",
]
