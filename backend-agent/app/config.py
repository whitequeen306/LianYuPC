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

    # 工具（对齐 Java lianyu.tools.*）
    time_zone: str = "Asia/Shanghai"
    default_city: str = "上海"
    weather_base_url: str = "https://wttr.in"
    weather_cache_minutes: float = 15.0
    # 工具循环上限：Java 侧是框架内部递归**无计数器**（审计 A4），
    # Python 侧显式设上限并在日志/测试中固化
    max_tool_rounds: int = 3

    # 只读数据访问（总纲 D7：Python 只读 MySQL/Redis，不写）
    mysql_host: str = "mysql"
    mysql_port: int = 3306
    mysql_user: str = "lianyu"
    mysql_password: str = ""
    mysql_database: str = "lianyu"
    mysql_pool_size: int = 4
    mysql_timeout_seconds: float = 10.0
    redis_url: str = "redis://redis:6379/0"
    redis_timeout_seconds: float = 5.0

    # 记忆检索参数（对齐 Java lianyu.memory.*）
    memory_importance_threshold: float = 0.5
    memory_search_candidate_count: int = 20
    memory_like_boost_limit: int = 5
    memory_tool_top_k: int = 5
    memory_semantic_ttl_minutes: float = 15.0
    memory_recent_ttl_minutes: float = 60.0

    # 向量化 / 重排（P5 读路径）。base_url 可配置：修掉 Java 侧硬编码 DashScope 地址的缺陷
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-v3"
    embedding_dim: int = 1024
    rerank_base_url: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    rerank_api_key: str = ""
    rerank_model: str = "gte-rerank-v2"
    # 进程内向量检索（Milvus 缺失时的真实语义召回）
    memory_vector_ttl_hours: float = 168.0
    memory_vector_max_candidates: int = 50

    # 角色近况（对齐 CharacterRecentActivityService）
    recent_activity_cache_hours: float = 1.0
    recent_activity_max_lines: int = 5
    recent_activity_snippet_chars: int = 40

    # 影子对比模式：true 时记录请求/响应到日志供新旧链路 diff
    shadow_mode: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
