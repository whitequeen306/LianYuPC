"""工具层入口：导入即注册内置工具。

能力声明（`supported_tool_names()`）是 Java 侧切 AGENT 的路由依据。
仍需 Java 侧实现、Python 尚未支持的：`write_diary`（MQ）、`computer_task`（STOMP 桌面桥）。
"""

from app.tools import registry
from app.tools.registry import (
    ToolSpec,
    get_tool,
    resolve_tools,
    supported_tool_names,
    supported_tool_schemas,
)
from app.tools.memory_search_tool import MEMORY_SEARCH_TOOL  # noqa: F401 - 导入即注册
from app.tools.recent_activity_tool import RECENT_ACTIVITY_TOOL  # noqa: F401 - 导入即注册
from app.tools.time_tool import TIME_TOOL  # noqa: F401 - 导入即注册
from app.tools.weather_tool import WEATHER_TOOL  # noqa: F401 - 导入即注册

__all__ = [
    "MEMORY_SEARCH_TOOL",
    "RECENT_ACTIVITY_TOOL",
    "TIME_TOOL",
    "WEATHER_TOOL",
    "ToolSpec",
    "get_tool",
    "registry",
    "resolve_tools",
    "supported_tool_names",
    "supported_tool_schemas",
]
