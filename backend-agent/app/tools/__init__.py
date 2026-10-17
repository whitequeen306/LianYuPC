"""工具层入口：导入即注册内置工具。"""

from app.tools import registry
from app.tools.registry import (
    ToolSpec,
    get_tool,
    resolve_tools,
    supported_tool_names,
    supported_tool_schemas,
)
from app.tools.time_tool import TIME_TOOL  # noqa: F401 - 导入即注册
from app.tools.weather_tool import WEATHER_TOOL  # noqa: F401 - 导入即注册

__all__ = [
    "TIME_TOOL",
    "WEATHER_TOOL",
    "ToolSpec",
    "get_tool",
    "registry",
    "resolve_tools",
    "supported_tool_names",
    "supported_tool_schemas",
]
