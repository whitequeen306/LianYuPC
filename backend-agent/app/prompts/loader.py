"""Prompt 资源加载器。

阶段 2 起，场景任务块从 Java 内联字面量迁移为本目录资源文件。
加载即校验占位符完整（缺变量名 → 启动期报错，不在请求期炸）。
"""

import functools
from pathlib import Path

PROMPTS_DIR = Path(__file__).parent


class MissingPlaceholderError(KeyError):
    """模板变量缺失（对齐 Java String.format 的缺参抛错语义）。"""


@functools.lru_cache(maxsize=None)
def load_prompt(name: str) -> str:
    """按名加载 prompt 资源；带缓存。文件缺失抛 FileNotFoundError。"""
    path = PROMPTS_DIR / f"{name}.md"
    return path.read_text(encoding="utf-8")


def render_prompt(name: str, *, leading_newlines: int = 2, **variables: object) -> str:
    """加载并填充 {placeholder}；多余/缺失变量都报错（fail-fast，防静默占位符泄漏进 prompt）。

    leading_newlines 对齐 Java text block 拼接惯例：Java 侧把任务块拼在
    systemPrompt 之后时，text block 开头带两个换行作为分隔符；该分隔符属于
    拼接逻辑而非模板内容，故资源文件不含前导空行、由本参数统一补。
    """
    template = load_prompt(name)
    expected = _extract_placeholders(template)
    missing = expected - variables.keys()
    if missing:
        raise MissingPlaceholderError(f"prompt '{name}' missing variables: {sorted(missing)}")
    rendered = template.format(**variables)
    return "\n" * leading_newlines + rendered


def _extract_placeholders(template: str) -> set[str]:
    import string

    return {field_name for _, field_name, _, _ in string.Formatter().parse(template) if field_name}


def clear_cache() -> None:
    load_prompt.cache_clear()


__all__ = [
    "MissingPlaceholderError",
    "clear_cache",
    "load_prompt",
    "render_prompt",
]
