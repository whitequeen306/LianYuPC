"""兼容垫片：注册表的真实实现在 `app.tool_registry`。

为什么搬家：`app/capability/*` 需要 `ToolSpec`/`register`，而它一旦 `import app.tools.registry`
就会先执行 `app/tools/__init__.py`，后者又去 import `app.capability` 做注册 —— 形成循环。
把注册表提成**叶子模块**（不 import 任何 app 内部包）后，capability → registry 是单向依赖。

保留本文件是为了既有导入路径（`from app.tools.registry import ToolSpec`）不失效。
"""

from app.tool_registry import (  # noqa: F401 - re-export
    DangerLevel,
    ToolHandler,
    ToolKind,
    ToolSpec,
    get_tool,
    names_of_kind,
    register,
    resolve_tools,
    supported_tool_names,
    supported_tool_schemas,
)

__all__ = [
    "DangerLevel",
    "ToolHandler",
    "ToolKind",
    "ToolSpec",
    "get_tool",
    "names_of_kind",
    "register",
    "resolve_tools",
    "supported_tool_names",
    "supported_tool_schemas",
]
