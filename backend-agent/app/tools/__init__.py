"""工具层入口：导入即注册内置工具。

`supported_tool_names()` 是 Java 侧路由与切换闸门的依据。

当前只有一类工具：

1. `local`：本进程直接执行的工具（时间 / 天气 / 记忆检索 / 最近生活）。

> 2026-10-25：桌面能力层（`desk_*`，把动作下发到用户 Electron 执行）已从代码里整体删除。
> 模型现在只看得到上面这四个聊天类工具。删除范围：`app/capability`、
> `frontend/electron/capability`、Java 的 agent-bridge 控制器与内网执行通道。

关于 `write_diary`：**不提供模型工具** —— 角色写日记由 Java 定时任务完成
（`CharacterDiaryService` 每晚扫当天活跃会话 → MQ → 生成 → 落库 + 推送通知），
与对话回合解耦。想让它更频繁，调 `lianyu.diary.*` 配置即可，不要加工具。
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

__all__ = [
    "MEMORY_SEARCH_TOOL",
    "RECENT_ACTIVITY_TOOL",
    "TIME_TOOL",
    "WEATHER_TOOL",
    "ToolSpec",
    "get_tool",
    "names_of_kind",
    "registry",
    "resolve_tools",
    "supported_tool_names",
    "supported_tool_schemas",
]
