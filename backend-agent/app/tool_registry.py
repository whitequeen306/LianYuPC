"""工具层注册表：`supported_tool_names()` 是 Java 侧路由与切换闸门的依据。

工具元数据（`danger` / `timeout_ms` / `needs_confirm`）**不进 OpenAI schema**
（模型看不到危险等级，避免它据此绕过策略），只给预算/策略层与 Java 参考。

> 2026-10-25：桌面能力层（`desk_*`，经 Java 内网通道下发到 Electron）已从代码里
> 整体删除，模型只保留聊天类工具。`kind` 字段保留是为了将来引入其它执行地时不用改表结构。

关于 `write_diary`：**不提供模型工具** —— 角色写日记由 Java 定时任务完成
（`CharacterDiaryService` 每晚扫当天活跃会话 → MQ → 生成 → 落库 + 推送通知），
与对话回合解耦。想让它更频繁，调 `lianyu.diary.*` 配置即可，不要加工具。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Literal

ToolHandler = Callable[[dict[str, Any]], Awaitable[str]]

ToolKind = Literal["local"]
DangerLevel = Literal["R0", "R1", "R2", "R3"]


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    parameters: dict[str, Any]
    handler: ToolHandler
    # ── 能力元数据（有默认值：既有工具零改动） ──
    kind: ToolKind = "local"
    danger: DangerLevel = "R0"
    timeout_ms: int = 3000
    needs_confirm: bool = False
    # 该能力依赖的外部事实；空表示随时可用
    requires: tuple[str, ...] = ()

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


def names_of_kind(kind: ToolKind) -> tuple[str, ...]:
    """按执行地取工具名（顺序稳定：注册顺序）。"""
    return tuple(name for name, spec in _registry.items() if spec.kind == kind)


def resolve_tools(names: list[str] | None) -> list[ToolSpec]:
    """按名取工具；未知名称忽略（调用方应先用 supported_tool_names 校验）。"""
    if not names:
        return []
    return [_registry[n] for n in names if n in _registry]


def supported_tool_schemas(names: list[str] | None) -> list[dict[str, Any]]:
    return [spec.to_openai_schema() for spec in resolve_tools(names)]


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
