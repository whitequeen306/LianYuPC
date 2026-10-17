"""工具层：Python 侧可用的服务端工具。

**能力声明（重要）**：本模块只实现**无外部依赖**的两个工具——
`get_current_time`、`get_weather`。Java 侧还有 `memory_search`（Milvus+embedding+rerank）、
`get_my_recent_life`（MySQL）、`write_diary`（RabbitMQ）、`computer_task`（STOMP 桌面桥），
它们都需要本服务当前不直连的基础设施，按总纲 D7 留到 P5。

因此 Java 适配层在切 `AGENT` 前必须调用 `SUPPORTED_TOOL_NAMES` 做**能力校验**：
若某条链路需要的工具不在列表内，就不该路由到 Python（否则相对 Java 会降级）。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Awaitable, Callable

ToolHandler = Callable[[dict[str, Any]], Awaitable[str]]


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    parameters: dict[str, Any]
    handler: ToolHandler

    def to_openai_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


_registry: dict[str, ToolSpec] = {}


def register(spec: ToolSpec) -> None:
    _registry[spec.name] = spec


def get_tool(name: str) -> ToolSpec | None:
    return _registry.get(name)


def supported_tool_names() -> frozenset[str]:
    return frozenset(_registry)


def resolve_tools(names: list[str] | None) -> list[ToolSpec]:
    """按名取工具；未知名称忽略（调用方应先用 supported_tool_names 校验）。"""
    if not names:
        return []
    return [_registry[n] for n in names if n in _registry]


def supported_tool_schemas(names: list[str] | None) -> list[dict[str, Any]]:
    return [spec.to_openai_schema() for spec in resolve_tools(names)]


__all__ = [
    "ToolHandler",
    "ToolSpec",
    "get_tool",
    "register",
    "resolve_tools",
    "supported_tool_names",
    "supported_tool_schemas",
]
