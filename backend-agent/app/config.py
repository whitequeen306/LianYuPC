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
    max_tool_rounds: int = 8


    # 允许的时钟偏差（秒），超窗即拒绝（防重放）
    internal_signature_window_seconds: int = 30

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

    # v3 召回（五路 + RRF + MMR，见 docs/context-memory-architecture-zh.md §6）。
    # 默认关：它依赖 V58 的新列与 ngram 索引，迁移完成后再开。
    # 2026-11-06 起默认走 v3：演练库 26 题上 recall@5 从 0.42 提到 0.68+，
    # 旧路径（画像槽位 + importance top20 + LIKE）只作为异常兜底。
    memory_recall_v3_enabled: bool = True
    # 召回的超时预算：超了就退回旧路径，宁可少几条也不要拖住整轮回复。
    # 向量路本地推理热态 10ms、Milvus 检索 3–5ms（首次检索 600ms，已在启动时预热），
    # 文本路 SQL 200–300ms，线上本地精排 6 条约 165ms，0.75s 留了余量。
    # 预算一旦被"收紧"，最先牺牲的是精排（见 recall() 里的剩余预算二次收紧），
    # 而不是整段 v3——退回旧路径比不精排糟得多。
    memory_recall_timeout_seconds: float = 0.75
    # 打点（mark_injected）是旁路写入，超时直接放弃，下一轮再补。
    memory_recall_mark_timeout_seconds: float = 0.3

    # 工具结果注入上限：一次网页/文件读取就能吃掉整轮预算，
    # 超长结果掐头留尾（见 app.tool_loop.clip_tool_output）。
    tool_output_max_chars: int = 1200

    # 历史按 token 裁剪（recent 槽）。默认关：先上线测量，再决定是否让它真正丢历史。
    history_trim_enabled: bool = True

    # 写入前语义去重（cos≥0.92 NOOP）。默认关：多一次 embedding 调用，先灰跑看误伤。
    memory_dedupe_similarity_enabled: bool = True
    # 灰区（0.75–0.92）是否多问一次模型；默认关，靠 diff 看误合并率再开
    memory_dedupe_llm_review_enabled: bool = True

    # 影子召回：v3 还没开时也跑一遍，只打覆盖率日志。默认关（每轮多一次查询）。
    memory_shadow_enabled: bool = False

    # 场景卡开关：它是"当前场景"的唯一载体，但接线出问题会污染每一轮 prompt。
    # 留一个总开关，线上异常时先关它回到"只有记忆块"的状态。
    scene_card_enabled: bool = True

    # 场景关闭时抽 episode。独立开关：抽取要花一次小模型调用，
    # 出问题时可单独关掉而不影响场景卡本身。
    scene_extract_enabled: bool = True

    # 向量化 / 重排（P5 读路径）。base_url 可配置：修掉 Java 侧硬编码 DashScope 地址的缺陷
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-v3"
    embedding_dim: int = 1024
    # 向量化后端：dashscope（外网，0.6–1.5s）或 local（进程内 ONNX，约 10ms）。
    # 本地后端让向量路第一次真正跑进召回预算；dashscope 留作回滚。
    memory_embedding_backend: str = "local"
    # 默认模型 gte-multilingual-base：中文 51 题 MRR 0.898（与 bge-base-zh 持平），
    # 但跨语言召回 4/4（bge-*-zh 只有 2–3/4）——产品支持 zh/en/ja，用户可能用英文或
    # 日文问，而记忆是当时那句话写的。int8 ONNX 340MB，2 线程单条约 9ms。
    local_embedding_model_dir: str = "/app/models/gte-multilingual-base"
    local_embedding_dim: int = 768
    local_embedding_max_length: int = 256
    local_embedding_threads: int = 2
    # 本地模型的余弦尺度：标定集上正样本最低 0.566、噪声最高 0.438 → 取 0.50。
    # 换模型必须重新标定（local/_threshold.py）。
    local_embedding_min_similarity: float = 0.50
    # 本地 cross-encoder 精排（结论见 docs §10.9）：49 题中文语料上「纯精排」是负收益
    # （recall@5 43→40），与融合分 5:5 混合才净赚（recall@5 43→44、MRR 0.799→0.845）。
    # 模型 gte-multilingual-reranker-base（int8 341MB），与 embedding 同族、同样支持 zh/en/ja。
    # 默认开：超时/缺模型都会自动退回融合顺序（见 local_rerank）。
    memory_rerank_local_enabled: bool = True
    local_rerank_model_dir: str = "/app/models/gte-multilingual-reranker-base"
    local_rerank_max_length: int = 256
    # 线上实测（E5-2697 v4，6 条候选）：2 线程 228ms、3 线程 164ms —— 老 Xeon 比开发机慢一倍，
    # 线程数按容器 CPU 上限给，少给就是白等。
    local_rerank_threads: int = 3
    # 只精排前 N 条：实测 6 条与 12 条质量持平（都是 recall@5 44/49），但便宜一半。
    local_rerank_max_candidates: int = 6
    # 混合权重：0 = 完全用融合分，1 = 完全用精排分。0.5 是 49 题扫描的最优点。
    local_rerank_weight: float = 0.5
    # 精排自己的时间预算：线上 6 条 164ms，留一倍余量；recall() 还会再按
    # 「本轮剩余预算」二次收紧，绝不拖垮整轮召回。
    local_rerank_timeout_seconds: float = 0.35
    rerank_base_url: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    rerank_api_key: str = ""
    rerank_model: str = "gte-rerank-v2"
    # 进程内向量检索（Milvus 缺失时的真实语义召回）
    # Milvus（与 Java lianyu.memory.milvus.* 对齐）
    milvus_host: str = "milvus"
    milvus_port: int = 19530
    milvus_collection: str = "memory_vectors_v2"
    # v3/v4：多带 layer/status/event_time 三个标量，检索时只捞 active 的行。
    # 双写期间 v2 仍是投影主键的来源（outbox 的 vec_id 记在 v2 上），v3 只做增量。
    # 集合名留空 = 按当前向量维度自动取（memory_vectors_d768）。集合维度建表时定死，
    # 换 embedding 模型必须换集合；自动命名保证不会把 512 维向量写进 1024 维集合。
    memory_vectors_v3_enabled: bool = True
    milvus_v3_collection: str = ""
    milvus_timeout_seconds: float = 10.0
    # outbox relay（Python 侧单写者）
    relay_enabled: bool = True
    relay_interval_seconds: float = 5.0
    relay_batch_size: int = 20
    relay_max_attempts: int = 8
    # 关系块：最近事件条数（对齐 Java listRecentEventSummaries 的 limit）
    relationship_recent_events: int = 5
    memory_vector_ttl_hours: float = 168.0
    memory_vector_max_candidates: int = 50
    # v3 召回的向量路：默认关，等 Milvus 里确实有向量、且 v2 集合升到带 status 的 v3 再开
    # 向量路需要 text-embedding-v3 的 key；没有 key 时它自己降级为空，不影响 ngram。
    memory_vector_recall_enabled: bool = True
    memory_vector_recall_top_k: int = 20
    # text-embedding-v3 的无关句余弦基线约 0.40–0.52（实测「今天吃了火锅」对两条记忆是 0.46/0.52），
    # 低于这个门槛的基本是噪声。0 表示按后端自动取（见 app/memory/embedding.py
    # DEFAULT_MIN_SIMILARITY）：不同模型的余弦尺度不可比，写死一个数换后端就全错。
    memory_vector_min_similarity: float = 0.0
    # 向量路单独限时：超时就只丢向量，别把 ngram/episode 一起拖垮。
    # 本地推理热态约 10ms，0.18s 只用来兜"模型冷启动/机器被别的项目抢满"的极端情况。
    memory_vector_timeout_seconds: float = 0.18

    # 记忆块（画像 + 关系 + 场景 + 召回）合起来的 token 上限；0 表示不设限
    memory_context_max_tokens: int = 1100

    # 主动浮现：主动开口时允许提一句快要忘记的事（每角色每周最多一次）
    memory_resurface_enabled: bool = True

    # 周记（跨天整合）：把一周 episode 收成一条 reflection。
    # 它由运维 cron / 测试端点触发，不挂在聊天链路上；默认开，异常可单独关。
    memory_consolidate_enabled: bool = True

    # 角色近况（对齐 CharacterRecentActivityService）
    recent_activity_cache_hours: float = 1.0
    recent_activity_max_lines: int = 5
    recent_activity_snippet_chars: int = 40

    # 影子对比模式：true 时记录请求/响应到日志供新旧链路 diff
    shadow_mode: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
