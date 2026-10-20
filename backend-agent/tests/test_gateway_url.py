"""base_url 归一化：必须与旧 Java（ChatModelFactory + Spring AI）一致。

生产 vault 里同时存在带 `/v1` 和不带 `/v1` 的写法，只按
`<base>/chat/completions` 拼接会让不带 `/v1` 的代理站直接 404
（2026-10-20 生产事故：linzihan.fun / clove.dpdns.org 等）。
"""

import pytest

from app.gateway import normalize_openai_base_url as normalize


@pytest.mark.parametrize(
    ("base_url", "expected"),
    [
        # 最常见的 DeepSeek 写法（不带 /v1）
        ("https://api.deepseek.com", "https://api.deepseek.com/v1/chat/completions"),
        # 末尾带斜杠
        ("https://api.deepseek.com/", "https://api.deepseek.com/v1/chat/completions"),
        # 已经带 /v1：先去掉再统一追加，避免 /v1/v1
        ("https://api.deepseek.com/v1", "https://api.deepseek.com/v1/chat/completions"),
        ("https://api.deepseek.com/v1/", "https://api.deepseek.com/v1/chat/completions"),
        # 代理站两种写法都必须落到同一个端点
        ("https://clove.dpdns.org", "https://clove.dpdns.org/v1/chat/completions"),
        ("https://clove.dpdns.org/v1", "https://clove.dpdns.org/v1/chat/completions"),
        ("https://linzihan.fun", "https://linzihan.fun/v1/chat/completions"),
        # 带自定义路径前缀（OpenRouter 这类）
        ("https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1/chat/completions"),
        # DashScope 兼容模式
        (
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        ),
        # 非 /v1 的路径前缀原样保留（与 Java 行为一致）
        ("https://ai.smdnh.cn/v1", "https://ai.smdnh.cn/v1/chat/completions"),
    ],
)
def test_normalize_matches_java_rule(base_url, expected):
    assert normalize(base_url) == expected


def test_trailing_slashes_collapsed():
    assert normalize("https://x.test///") == "https://x.test/v1/chat/completions"


def test_empty_base_url_does_not_produce_broken_url():
    # 空值属于调用方错误，但函数本身不能崩（保持与 Java 一致的容错形态）
    assert normalize("") == "/v1/chat/completions"
