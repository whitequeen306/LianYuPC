"""应用配置。

所有配置经 pydantic-settings 绑定环境变量（对齐 AGENTS 红线：不许零散散落），
出站 HTTP 一律显式超时（httpx.Timeout），禁止默认零超时。
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="LIANYU_AGENT_", env_file=".env", extra="ignore")

    # 服务
    host: str = "0.0.0.0"
    port: int = 8001

    # 多模态（与 Java 侧 lianyu.ai.multimodal.* 同源同值）
    multimodal_enabled: bool = True
    multimodal_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    multimodal_api_key: str = ""
    multimodal_model: str = "qwen3.7-flash"
    multimodal_max_tokens: int = 800
    multimodal_describe_max_tokens: int = 420

    # 出站超时红线（SsrfPinningClientFactory 语义的 Python 等价物）：
    # connect 15s / read 60s / write 30s / pool 30s —— 与 Java 侧一致
    outbound_timeout: tuple[float, float, float, float] = (15.0, 60.0, 30.0, 30.0)

    # 并发护栏（对齐 Java ai-chat bulkhead 语义：占槽上限 + 快速失败）
    desktop_observe_max_concurrency: int = 8

    # 主动发言链路（对齐 Java lianyu.chat.max-replies-per-turn 的生成预算）
    proactive_max_tokens: int = 1024

    # 隔舱（对齐 application.yml resilience4j.bulkhead：ai-chat 80/5s、ai-background 6/2s）
    bulkhead_interactive: int = 80
    bulkhead_interactive_wait: float = 5.0
    bulkhead_background: int = 6
    bulkhead_background_wait: float = 2.0

    # 语言门（对齐 AiChatService.LANGUAGE_GATE_MAX_RETRIES = 2）
    language_gate_max_retries: int = 2

    # 影子对比模式：true 时记录请求/响应到日志供新旧链路 diff
    shadow_mode: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
